using System;
using System.Buffers;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;

namespace Callisto.Sdk.Logs;

/// <summary>Log severities, mapped to OTLP severity numbers 5 / 9 / 13 / 17 / 21.</summary>
public enum CallistoLogLevel
{
    Debug,
    Info,
    Warn,
    Error,
    Fatal,
}

/// <summary>Builds the OTLP JSON for log records and batches.</summary>
internal static class OtlpEncoder
{
    public const int MaxBodyChars = 32_768;
    public const int MaxStringChars = 8_192;
    public const int MaxAttributes = 128;

    /// <summary>Items kept per nested list / map; the extra ones are dropped.</summary>
    public const int MaxNestedItems = 128;

    /// <summary>
    /// Encoded values (scalars + containers) per record, across all attributes and nesting
    /// levels. A sub-list or sub-map referenced several times counts each time it is visited.
    /// </summary>
    public const int MaxRecordValues = 2048;

    /// <summary>The encoded values left for one record.</summary>
    internal sealed class Budget
    {
        private int _left = MaxRecordValues;

        public bool Spent => _left <= 0;

        public bool Take()
        {
            if (_left <= 0)
            {
                return false;
            }

            _left--;
            return true;
        }
    }
    public const int MaxDepth = 5;
    private const string Ellipsis = "…";

    private static readonly JsonWriterOptions WriterOptions = new()
    {
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    private static readonly JsonSerializerOptions StringifyOptions = new()
    {
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    public static (int Number, string Text) Severity(CallistoLogLevel level) => level switch
    {
        CallistoLogLevel.Debug => (5, "DEBUG"),
        CallistoLogLevel.Warn => (13, "WARN"),
        CallistoLogLevel.Error => (17, "ERROR"),
        CallistoLogLevel.Fatal => (21, "FATAL"),
        _ => (9, "INFO"),
    };

    public static bool TryParseLevel(string? level, out CallistoLogLevel parsed)
    {
        switch (level?.Trim().ToLowerInvariant())
        {
            case "debug": parsed = CallistoLogLevel.Debug; return true;
            case "info": parsed = CallistoLogLevel.Info; return true;
            case "warn": case "warning": parsed = CallistoLogLevel.Warn; return true;
            case "error": parsed = CallistoLogLevel.Error; return true;
            case "fatal": parsed = CallistoLogLevel.Fatal; return true;
            default: parsed = CallistoLogLevel.Info; return false;
        }
    }

    public static string UnixNanos(DateTimeOffset time)
        => ((time.UtcTicks - DateTimeOffset.UnixEpoch.UtcTicks) * 100L).ToString(CultureInfo.InvariantCulture);

    /// <summary>The batch envelope up to the opening of <c>logRecords</c>.</summary>
    public static string Prefix(string serviceName, string? environment)
    {
        var buffer = new ArrayBufferWriter<byte>(512);
        using (var w = new Utf8JsonWriter(buffer, WriterOptions))
        {
            w.WriteStartObject();
            w.WriteStartArray("resourceLogs");
            w.WriteStartObject();
            w.WriteStartObject("resource");
            w.WriteStartArray("attributes");
            WriteStringAttribute(w, "service.name", serviceName);
            if (environment is not null)
            {
                WriteStringAttribute(w, "deployment.environment.name", environment);
            }

            WriteStringAttribute(w, "telemetry.sdk.name", SdkInfo.Name);
            WriteStringAttribute(w, "telemetry.sdk.language", SdkInfo.Language);
            WriteStringAttribute(w, "telemetry.sdk.version", SdkInfo.Version);
            w.WriteEndArray();
            w.WriteEndObject();
            w.WriteStartArray("scopeLogs");
            w.WriteStartObject();
            w.WriteStartObject("scope");
            w.WriteString("name", SdkInfo.Name);
            w.WriteString("version", SdkInfo.Version);
            w.WriteEndObject();
            w.WriteStartArray("logRecords");
            // Placeholder element so the writer accepts the unbalanced output; cut below.
            w.WriteNullValue();
            w.Flush();
        }

        var json = Encoding.UTF8.GetString(buffer.WrittenSpan);
        return json.Substring(0, json.Length - "null".Length);
    }

    public const string Suffix = "]}]}]}";

    public static string Batch(string prefix, IReadOnlyList<string> records)
        => prefix + string.Join(",", records) + Suffix;

    public static string Record(
        CallistoLogLevel level,
        string? message,
        IReadOnlyDictionary<string, object?>? attributes,
        DateTimeOffset time)
    {
        var (number, text) = Severity(level);
        var nanos = UnixNanos(time);

        string? traceId = null;
        string? spanId = null;
        var kept = new List<KeyValuePair<string, object?>>();

        if (attributes is not null)
        {
            foreach (var kvp in attributes)
            {
                if (kvp.Key == "trace_id" && traceId is null && IsTraceHex(kvp.Value, 32, out var t))
                {
                    traceId = t;
                    continue;
                }

                if (kvp.Key == "span_id" && spanId is null && IsTraceHex(kvp.Value, 16, out var s))
                {
                    spanId = s;
                    continue;
                }

                if (kvp.Value is null || kept.Count >= MaxAttributes)
                {
                    continue;
                }

                kept.Add(kvp);
            }
        }

        var buffer = new ArrayBufferWriter<byte>(256);
        using (var w = new Utf8JsonWriter(buffer, WriterOptions))
        {
            w.WriteStartObject();
            w.WriteString("timeUnixNano", nanos);
            w.WriteString("observedTimeUnixNano", nanos);
            w.WriteNumber("severityNumber", number);
            w.WriteString("severityText", text);
            w.WritePropertyName("body");
            w.WriteStartObject();
            w.WriteString("stringValue", Clip(message ?? string.Empty, MaxBodyChars));
            w.WriteEndObject();

            if (kept.Count > 0)
            {
                // Once the record's value budget is spent, later attributes are dropped.
                var budget = new Budget();
                w.WriteStartArray("attributes");
                foreach (var kvp in kept)
                {
                    if (!budget.Take())
                    {
                        break;
                    }

                    w.WriteStartObject();
                    w.WriteString("key", Clean(kvp.Key ?? string.Empty));
                    w.WritePropertyName("value");
                    WriteAnyValue(w, kvp.Value, 1, budget);
                    w.WriteEndObject();
                }

                w.WriteEndArray();
            }

            if (traceId is not null)
            {
                w.WriteString("traceId", traceId);
            }

            if (spanId is not null)
            {
                w.WriteString("spanId", spanId);
            }

            w.WriteEndObject();
        }

        return Encoding.UTF8.GetString(buffer.WrittenSpan);
    }

    private static void WriteStringAttribute(Utf8JsonWriter w, string key, string value)
    {
        w.WriteStartObject();
        w.WriteString("key", key);
        w.WritePropertyName("value");
        w.WriteStartObject();
        w.WriteString("stringValue", Clean(value));
        w.WriteEndObject();
        w.WriteEndObject();
    }

    private static bool IsTraceHex(object? value, int length, out string normalized)
    {
        normalized = string.Empty;
        if (value is not string s || s.Length != length)
        {
            return false;
        }

        var allZero = true;
        foreach (var c in s)
        {
            if (!Uri.IsHexDigit(c))
            {
                return false;
            }

            if (c != '0')
            {
                allZero = false;
            }
        }

        if (allZero)
        {
            return false;
        }

        normalized = s.ToLowerInvariant();
        return true;
    }

    /// <summary>
    /// Writes one OTLP <c>AnyValue</c> object. <paramref name="depth"/> starts at 1. The caller
    /// has already taken this value from <paramref name="budget"/>.
    /// </summary>
    private static void WriteAnyValue(Utf8JsonWriter w, object? value, int depth, Budget budget)
    {
        w.WriteStartObject();
        switch (value)
        {
            case null:
                break;
            case string s:
                w.WriteString("stringValue", Clip(s, MaxStringChars));
                break;
            case bool b:
                w.WriteBoolean("boolValue", b);
                break;
            case sbyte or byte or short or ushort or int or uint or long:
                w.WriteString("intValue", Convert.ToInt64(value, CultureInfo.InvariantCulture).ToString(CultureInfo.InvariantCulture));
                break;
            case ulong u:
                if (u <= long.MaxValue)
                {
                    w.WriteString("intValue", u.ToString(CultureInfo.InvariantCulture));
                }
                else
                {
                    w.WriteString("stringValue", u.ToString(CultureInfo.InvariantCulture));
                }

                break;
            case float f:
                WriteDouble(w, f);
                break;
            case double d:
                WriteDouble(w, d);
                break;
            case decimal m:
                WriteDouble(w, (double)m);
                break;
            case char c:
                w.WriteString("stringValue", Clean(c.ToString()));
                break;
            case Enum e:
                w.WriteString("stringValue", Clip(e.ToString(), MaxStringChars));
                break;
            case DateTime dt:
                w.WriteString("stringValue", dt.ToString("O", CultureInfo.InvariantCulture));
                break;
            case DateTimeOffset dto:
                w.WriteString("stringValue", dto.ToString("O", CultureInfo.InvariantCulture));
                break;
            case IDictionary dict:
                if (depth > MaxDepth)
                {
                    w.WriteString("stringValue", BoundedJson.Of(value, budget));
                    break;
                }

                w.WritePropertyName("kvlistValue");
                w.WriteStartObject();
                w.WriteStartArray("values");
                var kept = 0;
                foreach (DictionaryEntry entry in dict)
                {
                    if (kept >= MaxNestedItems || budget.Spent)
                    {
                        break;
                    }

                    if (WriteKeyValue(w, Convert.ToString(entry.Key, CultureInfo.InvariantCulture), entry.Value, depth, budget))
                    {
                        kept++;
                    }
                }

                w.WriteEndArray();
                w.WriteEndObject();
                break;
            case IEnumerable<KeyValuePair<string, object?>> pairs:
                if (depth > MaxDepth)
                {
                    w.WriteString("stringValue", BoundedJson.Of(value, budget));
                    break;
                }

                w.WritePropertyName("kvlistValue");
                w.WriteStartObject();
                w.WriteStartArray("values");
                var keptPairs = 0;
                foreach (var kvp in pairs)
                {
                    if (keptPairs >= MaxNestedItems || budget.Spent)
                    {
                        break;
                    }

                    if (WriteKeyValue(w, kvp.Key, kvp.Value, depth, budget))
                    {
                        keptPairs++;
                    }
                }

                w.WriteEndArray();
                w.WriteEndObject();
                break;
            case IEnumerable list:
                if (depth > MaxDepth)
                {
                    w.WriteString("stringValue", BoundedJson.Of(value, budget));
                    break;
                }

                w.WritePropertyName("arrayValue");
                w.WriteStartObject();
                w.WriteStartArray("values");
                var items = 0;
                foreach (var item in list)
                {
                    if (items++ >= MaxNestedItems || !budget.Take())
                    {
                        break;
                    }

                    WriteAnyValue(w, item, depth + 1, budget);
                }

                w.WriteEndArray();
                w.WriteEndObject();
                break;
            default:
                w.WriteString("stringValue", Clip(SafeToString(value), MaxStringChars));
                break;
        }

        w.WriteEndObject();
    }

    /// <summary>
    /// Writes one key/value; returns false (and writes nothing) for a null value or once the
    /// budget is spent.
    /// </summary>
    private static bool WriteKeyValue(Utf8JsonWriter w, string? key, object? value, int depth, Budget budget)
    {
        if (value is null || !budget.Take())
        {
            return false;
        }

        w.WriteStartObject();
        w.WriteString("key", Clean(key ?? string.Empty));
        w.WritePropertyName("value");
        WriteAnyValue(w, value, depth + 1, budget);
        w.WriteEndObject();
        return true;
    }

    private static void WriteDouble(Utf8JsonWriter w, double d)
    {
        if (double.IsNaN(d))
        {
            w.WriteString("stringValue", "NaN");
        }
        else if (double.IsPositiveInfinity(d))
        {
            w.WriteString("stringValue", "Infinity");
        }
        else if (double.IsNegativeInfinity(d))
        {
            w.WriteString("stringValue", "-Infinity");
        }
        else
        {
            w.WriteNumber("doubleValue", d);
        }
    }

    /// <summary>
    /// JSON of a container nested too deep, written by hand instead of with
    /// <see cref="JsonSerializer"/>: every nested item takes one value from the record budget and
    /// the output stops past <see cref="MaxStringChars"/>, so a shared or cyclic structure costs
    /// a bounded amount of work. A cut ends with "…".
    /// </summary>
    private sealed class BoundedJson
    {
        private readonly StringBuilder _sb = new();
        private readonly Budget _budget;
        private readonly HashSet<object> _active = new(ReferenceEqualityComparer.Instance);
        private bool _cut;

        private BoundedJson(Budget budget)
        {
            _budget = budget;
        }

        public static string Of(object value, Budget budget)
        {
            var w = new BoundedJson(budget);
            if (!w.Value(value, count: false) && w._cut)
            {
                w._sb.Append('…');
            }

            return Clip(w._sb.ToString(), MaxStringChars);
        }

        private bool Write(string s)
        {
            if (_sb.Length > MaxStringChars)
            {
                return false;
            }

            _sb.Append(s);
            return true;
        }

        private static string Json(object? value)
        {
            try
            {
                return value is null ? "null" : JsonSerializer.Serialize(value, value.GetType(), StringifyOptions);
            }
            catch
            {
                return JsonSerializer.Serialize(value is null ? "null" : SafeToString(value), StringifyOptions);
            }
        }

        private bool Value(object? value, bool count)
        {
            if (count && !_budget.Take())
            {
                _cut = true;
                return false;
            }

            if (value is null or string or bool or char or Enum or DateTime or DateTimeOffset ||
                value.GetType().IsPrimitive || value is decimal ||
                value is not (IDictionary or IEnumerable<KeyValuePair<string, object?>> or IEnumerable))
            {
                return Write(Json(value));
            }

            if (!_active.Add(value) || _active.Count > 64)
            {
                _active.Remove(value);
                return Write(value is IEnumerable and not (IDictionary or IEnumerable<KeyValuePair<string, object?>>) ? "[\"…\"]" : "{}");
            }

            try
            {
                switch (value)
                {
                    case IDictionary dict:
                    {
                        if (!Write("{"))
                        {
                            return false;
                        }

                        var first = true;
                        foreach (DictionaryEntry entry in dict)
                        {
                            if ((!first && !Write(",")) ||
                                !Write(Json(Convert.ToString(entry.Key, CultureInfo.InvariantCulture) ?? string.Empty) + ":") ||
                                !Value(entry.Value, count: true))
                            {
                                return false;
                            }

                            first = false;
                        }

                        return Write("}");
                    }

                    case IEnumerable<KeyValuePair<string, object?>> pairs:
                    {
                        if (!Write("{"))
                        {
                            return false;
                        }

                        var first = true;
                        foreach (var kvp in pairs)
                        {
                            if ((!first && !Write(",")) || !Write(Json(kvp.Key ?? string.Empty) + ":") ||
                                !Value(kvp.Value, count: true))
                            {
                                return false;
                            }

                            first = false;
                        }

                        return Write("}");
                    }

                    default:
                    {
                        if (!Write("["))
                        {
                            return false;
                        }

                        var first = true;
                        foreach (var item in (IEnumerable)value)
                        {
                            if ((!first && !Write(",")) || !Value(item, count: true))
                            {
                                return false;
                            }

                            first = false;
                        }

                        return Write("]");
                    }
                }
            }
            finally
            {
                _active.Remove(value);
            }
        }
    }

    private static string SafeToString(object value)
    {
        try
        {
            return Convert.ToString(value, CultureInfo.InvariantCulture) ?? string.Empty;
        }
        catch
        {
            return value.GetType().FullName ?? "object";
        }
    }

    /// <summary>Cuts to <paramref name="max"/> characters, the last one being "…".</summary>
    internal static string Clip(string value, int max)
    {
        if (value.Length > max)
        {
            var keep = max - 1;
            if (keep > 0 && char.IsHighSurrogate(value[keep - 1]))
            {
                keep--;
            }

            value = value.Substring(0, keep) + Ellipsis;
        }

        return Clean(value);
    }

    /// <summary>Replaces lone surrogates (which the JSON writer rejects) with U+FFFD.</summary>
    private static string Clean(string value)
    {
        var bad = false;
        for (var i = 0; i < value.Length; i++)
        {
            var c = value[i];
            if (char.IsHighSurrogate(c) && i + 1 < value.Length && char.IsLowSurrogate(value[i + 1]))
            {
                i++;
            }
            else if (char.IsSurrogate(c))
            {
                bad = true;
                break;
            }
        }

        if (!bad)
        {
            return value;
        }

        var sb = new StringBuilder(value.Length);
        for (var i = 0; i < value.Length; i++)
        {
            var c = value[i];
            if (char.IsHighSurrogate(c) && i + 1 < value.Length && char.IsLowSurrogate(value[i + 1]))
            {
                sb.Append(c).Append(value[i + 1]);
                i++;
            }
            else
            {
                sb.Append(char.IsSurrogate(c) ? '�' : c);
            }
        }

        return sb.ToString();
    }
}
