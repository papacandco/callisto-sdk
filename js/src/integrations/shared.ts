/** Minimal surface the integrations need from a CallistoClient. */
export interface ErrorCapturer {
  captureException(
    error: unknown,
    level?: string,
    extra?: Record<string, unknown> | null,
  ): void;
}

/** Common options for the Express and NestJS integrations. */
export interface CallistoIntegrationOptions {
  /** Return false to skip reporting a given error. Default: report everything. */
  shouldReport?(err: unknown): boolean;
  /** Level for reported events. Default: "error". */
  level?: string | ((err: unknown) => string);
}

/** Request metadata attached to a reported event. No body/headers/query. */
export interface RequestInfo {
  method: string;
  path: string;
}

function resolveLevel(
  level: CallistoIntegrationOptions["level"],
  err: unknown,
): string {
  if (typeof level === "function") return level(err);
  if (typeof level === "string") return level;
  return "error";
}

/**
 * Decide-and-report. Never throws. Attaches request method/path as extra
 * (`http_method` / `http_path`) — deliberately no body, headers, query, or PII.
 */
export function reportRequestError(
  client: ErrorCapturer,
  err: unknown,
  req: RequestInfo,
  options: CallistoIntegrationOptions = {},
): void {
  try {
    if (options.shouldReport && !options.shouldReport(err)) return;
    const level = resolveLevel(options.level, err);
    client.captureException(err, level, {
      http_method: req.method,
      http_path: req.path,
    });
  } catch {
    // Reporting must never disturb the host's error path.
  }
}
