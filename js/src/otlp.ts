/**
 * OTLP JSON encoding for log records (the OTLP/HTTP logs JSON mapping that
 * callisto-logs ingests). Pure functions: no I/O, no state.
 */

export type LogLevel = "debug" | "info" | "warn" | "error" | "fatal";

/** Attributes attached to a log line: a flat or nested map. */
export type LogAttributes = Record<string, unknown>;

export type AnyValue =
  | { stringValue: string }
  | { boolValue: boolean }
  | { intValue: string }
  | { doubleValue: number }
  | { arrayValue: { values: AnyValue[] } }
  | { kvlistValue: { values: KeyValue[] } }
  | Record<string, never>;

export interface KeyValue {
  key: string;
  value: AnyValue;
}

export interface OtlpLogRecord {
  timeUnixNano: string;
  observedTimeUnixNano: string;
  severityNumber: number;
  severityText: string;
  body: AnyValue;
  attributes?: KeyValue[];
  traceId?: string;
  spanId?: string;
}

export const MAX_BODY_CHARS = 32_768;
export const MAX_ATTRIBUTE_VALUE_CHARS = 8_192;
export const MAX_ATTRIBUTES = 128;
/** Containers nested deeper than this are stringified. */
export const MAX_DEPTH = 5;

const ELLIPSIS = "…";

const SEVERITY: Record<LogLevel, { number: number; text: string }> = {
  debug: { number: 5, text: "DEBUG" },
  info: { number: 9, text: "INFO" },
  warn: { number: 13, text: "WARN" },
  error: { number: 17, text: "ERROR" },
  fatal: { number: 21, text: "FATAL" },
};

const INT64_MIN = -(2n ** 63n);
const INT64_MAX = 2n ** 63n - 1n;

const TRACE_ID_RE = /^[0-9a-fA-F]{32}$/;
const SPAN_ID_RE = /^[0-9a-fA-F]{16}$/;

/** Normalize a level name; unknown values fall back to `info`. */
export function normalizeLogLevel(level: unknown): LogLevel {
  const name = typeof level === "string" ? level.toLowerCase() : "";
  if (name === "warning") return "warn";
  return name in SEVERITY ? (name as LogLevel) : "info";
}

/**
 * Cut `value` to at most `max` characters, the last one being `…`. Never
 * leaves a lone high surrogate at the cut.
 */
export function truncate(value: string, max: number): string {
  if (value.length <= max) return value;
  let head = value.slice(0, max - 1);
  const last = head.charCodeAt(head.length - 1);
  if (last >= 0xd800 && last <= 0xdbff) head = head.slice(0, -1);
  return head + ELLIPSIS;
}

/** String(value), never throwing (a hostile toString yields a placeholder). */
export function safeString(value: unknown): string {
  try {
    return String(value);
  } catch {
    return "[unserializable]";
  }
}

function stringify(value: unknown): string {
  try {
    const json = JSON.stringify(value, (_k, v: unknown) =>
      typeof v === "bigint" ? v.toString() : v,
    );
    if (typeof json === "string") return json;
  } catch {
    // circular or hostile: fall through
  }
  return safeString(value);
}

function stringValue(value: string): AnyValue {
  return { stringValue: truncate(value, MAX_ATTRIBUTE_VALUE_CHARS) };
}

/**
 * Map a JS value to an OTLP AnyValue. Returns undefined for null/undefined
 * (the caller omits the attribute, or writes `{}` for a list element).
 * `depth` is 1 for a top-level attribute value.
 */
export function toAnyValue(value: unknown, depth = 1): AnyValue | undefined {
  if (value === null || value === undefined) return undefined;
  switch (typeof value) {
    case "string":
      return stringValue(value);
    case "boolean":
      return { boolValue: value };
    case "number":
      if (Number.isNaN(value)) return { stringValue: "NaN" };
      if (value === Infinity) return { stringValue: "Infinity" };
      if (value === -Infinity) return { stringValue: "-Infinity" };
      if (Number.isSafeInteger(value)) return { intValue: String(value) };
      return { doubleValue: value };
    case "bigint":
      return value >= INT64_MIN && value <= INT64_MAX
        ? { intValue: value.toString() }
        : stringValue(value.toString());
    case "object":
      break;
    default:
      return stringValue(safeString(value));
  }

  if (value instanceof Date) {
    return stringValue(Number.isNaN(value.getTime()) ? "Invalid Date" : value.toISOString());
  }
  if (value instanceof Error) {
    return stringValue(`${value.name}: ${value.message}`);
  }
  if (depth > MAX_DEPTH) {
    return stringValue(value instanceof Map ? stringify(Object.fromEntries(value)) : stringify(value));
  }
  if (Array.isArray(value) || value instanceof Set) {
    const values: AnyValue[] = [];
    for (const item of value) values.push(toAnyValue(item, depth + 1) ?? {});
    return { arrayValue: { values } };
  }
  const entries: Iterable<[unknown, unknown]> =
    value instanceof Map ? value.entries() : Object.entries(value as object);
  return { kvlistValue: { values: toKeyValues(entries, depth + 1, Infinity) } };
}

function toKeyValues(
  entries: Iterable<[unknown, unknown]>,
  depth: number,
  limit: number,
): KeyValue[] {
  const out: KeyValue[] = [];
  for (const [key, raw] of entries) {
    if (out.length >= limit) break;
    const encoded = toAnyValue(raw, depth);
    if (encoded !== undefined) out.push({ key: safeString(key), value: encoded });
  }
  return out;
}

function validId(value: unknown, re: RegExp): string | undefined {
  if (typeof value !== "string" || !re.test(value) || /^0+$/.test(value)) return undefined;
  return value.toLowerCase();
}

/** Nanoseconds since the epoch, as the decimal string OTLP JSON expects. */
export function unixNano(epochMs: number): string {
  const whole = Math.floor(epochMs);
  const micros = Math.round((epochMs - whole) * 1000);
  return (BigInt(whole) * 1_000_000n + BigInt(micros) * 1000n).toString();
}

/** Build one OTLP log record. Callers must guard against throws. */
export function buildLogRecord(
  level: LogLevel,
  message: unknown,
  attributes: LogAttributes | null | undefined,
  epochMs: number,
): OtlpLogRecord {
  const severity = SEVERITY[level];
  const time = unixNano(epochMs);
  const record: OtlpLogRecord = {
    timeUnixNano: time,
    observedTimeUnixNano: time,
    severityNumber: severity.number,
    severityText: severity.text,
    body: { stringValue: truncate(safeString(message), MAX_BODY_CHARS) },
  };

  if (attributes && typeof attributes === "object") {
    let traceId: string | undefined;
    let spanId: string | undefined;
    const rest: Array<[string, unknown]> = [];
    for (const [key, value] of Object.entries(attributes)) {
      const id =
        key === "trace_id" ? validId(value, TRACE_ID_RE)
        : key === "span_id" ? validId(value, SPAN_ID_RE)
        : undefined;
      if (id === undefined) rest.push([key, value]);
      else if (key === "trace_id") traceId = id;
      else spanId = id;
    }
    const encoded = toKeyValues(rest, 1, MAX_ATTRIBUTES);
    if (encoded.length > 0) record.attributes = encoded;
    if (traceId) record.traceId = traceId;
    if (spanId) record.spanId = spanId;
  }
  return record;
}

export interface ResourceInfo {
  serviceName: string;
  environment?: string;
  sdkName: string;
  sdkLanguage: string;
  sdkVersion: string;
}

/**
 * The JSON text around a batch's records: `prefix + records.join(",") + suffix`
 * is one `ExportLogsServiceRequest` with one resourceLogs and one scopeLogs.
 * Built once per logger so each record is serialized exactly once.
 */
export function envelope(info: ResourceInfo): { prefix: string; suffix: string } {
  const attributes: KeyValue[] = [
    { key: "service.name", value: { stringValue: info.serviceName } },
  ];
  if (info.environment) {
    attributes.push({
      key: "deployment.environment.name",
      value: { stringValue: info.environment },
    });
  }
  attributes.push(
    { key: "telemetry.sdk.name", value: { stringValue: info.sdkName } },
    { key: "telemetry.sdk.language", value: { stringValue: info.sdkLanguage } },
    { key: "telemetry.sdk.version", value: { stringValue: info.sdkVersion } },
  );
  const resource = JSON.stringify({ attributes });
  const scope = JSON.stringify({ name: info.sdkName, version: info.sdkVersion });
  return {
    prefix: `{"resourceLogs":[{"resource":${resource},"scopeLogs":[{"scope":${scope},"logRecords":[`,
    suffix: "]}]}]}",
  };
}
