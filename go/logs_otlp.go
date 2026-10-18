package callisto

import (
	"encoding/json"
	"fmt"
	"math"
	"reflect"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

// Size guards from the log-shipping contract.
const (
	maxLogBodyChars      = 32768
	maxLogAttrValueChars = 8192
	maxLogAttributes     = 128
	maxLogAnyValueDepth  = 5
)

// otlpAnyValue is the OTLP JSON AnyValue. Exactly one field is set; all nil
// encodes as {} (a null list element).
type otlpAnyValue struct {
	StringValue *string          `json:"stringValue,omitempty"`
	BoolValue   *bool            `json:"boolValue,omitempty"`
	IntValue    *string          `json:"intValue,omitempty"`
	DoubleValue *float64         `json:"doubleValue,omitempty"`
	ArrayValue  *otlpArrayValue  `json:"arrayValue,omitempty"`
	KvlistValue *otlpKvlistValue `json:"kvlistValue,omitempty"`
}

type otlpKeyValue struct {
	Key   string       `json:"key"`
	Value otlpAnyValue `json:"value"`
}

type otlpArrayValue struct {
	Values []otlpAnyValue `json:"values"`
}

type otlpKvlistValue struct {
	Values []otlpKeyValue `json:"values"`
}

// otlpLogRecord is one OTLP JSON LogRecord. It is built on the caller's
// goroutine (a deep copy of the caller's attributes) and encoded by the worker.
type otlpLogRecord struct {
	TimeUnixNano         string         `json:"timeUnixNano"`
	ObservedTimeUnixNano string         `json:"observedTimeUnixNano"`
	SeverityNumber       int            `json:"severityNumber"`
	SeverityText         string         `json:"severityText"`
	Body                 otlpAnyValue   `json:"body"`
	Attributes           []otlpKeyValue `json:"attributes,omitempty"`
	TraceID              string         `json:"traceId,omitempty"`
	SpanID               string         `json:"spanId,omitempty"`
}

// severity maps a level to its OTLP severityNumber and severityText.
func severity(level LogLevel) (int, string) {
	switch level {
	case LogLevelDebug:
		return 5, "DEBUG"
	case LogLevelWarn:
		return 13, "WARN"
	case LogLevelError:
		return 17, "ERROR"
	case LogLevelFatal:
		return 21, "FATAL"
	default:
		return 9, "INFO"
	}
}

// buildLogRecord converts one log call into an OTLP record. Valid trace_id /
// span_id attributes are moved to the record's traceId / spanId.
func buildLogRecord(at time.Time, level LogLevel, message string, attrs map[string]any) *otlpLogRecord {
	ts := strconv.FormatInt(at.UnixNano(), 10)
	number, text := severity(level)
	body := truncateChars(message, maxLogBodyChars)
	rec := &otlpLogRecord{
		TimeUnixNano:         ts,
		ObservedTimeUnixNano: ts,
		SeverityNumber:       number,
		SeverityText:         text,
		Body:                 otlpAnyValue{StringValue: &body},
	}
	if len(attrs) == 0 {
		return rec
	}

	moved := map[string]bool{}
	if s, ok := attrs["trace_id"].(string); ok && validHexID(s, 32) {
		rec.TraceID = strings.ToLower(s)
		moved["trace_id"] = true
	}
	if s, ok := attrs["span_id"].(string); ok && validHexID(s, 16) {
		rec.SpanID = strings.ToLower(s)
		moved["span_id"] = true
	}

	keys := make([]string, 0, len(attrs))
	for k := range attrs {
		if !moved[k] {
			keys = append(keys, k)
		}
	}
	sort.Strings(keys)
	for _, k := range keys {
		if len(rec.Attributes) >= maxLogAttributes {
			break
		}
		if v, ok := toAnyValue(attrs[k], 1); ok {
			rec.Attributes = append(rec.Attributes, otlpKeyValue{Key: k, Value: v})
		}
	}
	return rec
}

// validHexID reports whether s is exactly n hex characters (any case) and not
// all zeros.
func validHexID(s string, n int) bool {
	if len(s) != n {
		return false
	}
	nonZero := false
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case c == '0':
		case c >= '1' && c <= '9', c >= 'a' && c <= 'f', c >= 'A' && c <= 'F':
			nonZero = true
		default:
			return false
		}
	}
	return nonZero
}

// truncateChars cuts s to at most limit characters (runes); a cut string ends
// with "…", which counts toward the limit.
func truncateChars(s string, limit int) string {
	if len(s) <= limit || utf8.RuneCountInString(s) <= limit {
		return s
	}
	n := 0
	for i := range s {
		if n == limit-1 {
			return s[:i] + "…"
		}
		n++
	}
	return s
}

func stringAnyValue(s string) otlpAnyValue {
	s = truncateChars(s, maxLogAttrValueChars)
	return otlpAnyValue{StringValue: &s}
}

func boolAnyValue(b bool) otlpAnyValue { return otlpAnyValue{BoolValue: &b} }

func intAnyValue(i int64) otlpAnyValue {
	s := strconv.FormatInt(i, 10)
	return otlpAnyValue{IntValue: &s}
}

func uintAnyValue(u uint64) otlpAnyValue {
	if u > math.MaxInt64 {
		// Out of OTLP's int64 range: keep the exact digits as a string.
		return stringAnyValue(strconv.FormatUint(u, 10))
	}
	return intAnyValue(int64(u))
}

func floatAnyValue(f float64) otlpAnyValue {
	switch {
	case math.IsNaN(f):
		return stringAnyValue("NaN")
	case math.IsInf(f, 1):
		return stringAnyValue("Infinity")
	case math.IsInf(f, -1):
		return stringAnyValue("-Infinity")
	}
	return otlpAnyValue{DoubleValue: &f}
}

func float32AnyValue(f float32) otlpAnyValue {
	// Go through the shortest float32 representation so 0.1 stays 0.1.
	v, err := strconv.ParseFloat(strconv.FormatFloat(float64(f), 'g', -1, 32), 64)
	if err != nil {
		v = float64(f)
	}
	return floatAnyValue(v)
}

// toAnyValue maps a Go value to an OTLP AnyValue. ok is false for nil (the
// attribute is omitted). depth is 1 for a top-level attribute value; lists and
// maps nested deeper than maxLogAnyValueDepth are stringified.
func toAnyValue(v any, depth int) (otlpAnyValue, bool) {
	switch x := v.(type) {
	case nil:
		return otlpAnyValue{}, false
	case string:
		return stringAnyValue(x), true
	case bool:
		return boolAnyValue(x), true
	case int:
		return intAnyValue(int64(x)), true
	case int8:
		return intAnyValue(int64(x)), true
	case int16:
		return intAnyValue(int64(x)), true
	case int32:
		return intAnyValue(int64(x)), true
	case int64:
		return intAnyValue(x), true
	case uint:
		return uintAnyValue(uint64(x)), true
	case uint8:
		return uintAnyValue(uint64(x)), true
	case uint16:
		return uintAnyValue(uint64(x)), true
	case uint32:
		return uintAnyValue(uint64(x)), true
	case uint64:
		return uintAnyValue(x), true
	case float32:
		return float32AnyValue(x), true
	case float64:
		return floatAnyValue(x), true
	case []byte:
		if utf8.Valid(x) {
			return stringAnyValue(string(x)), true
		}
		return stringAnyValue(fmt.Sprintf("%x", x)), true
	case time.Time:
		return stringAnyValue(x.Format(time.RFC3339Nano)), true
	case time.Duration:
		return stringAnyValue(x.String()), true
	case error:
		if isNilPointer(x) {
			return otlpAnyValue{}, false
		}
		return stringAnyValue(x.Error()), true
	case fmt.Stringer:
		if isNilPointer(x) {
			return otlpAnyValue{}, false
		}
		return stringAnyValue(x.String()), true
	}

	rv := reflect.ValueOf(v)
	for i := 0; i < 8 && (rv.Kind() == reflect.Pointer || rv.Kind() == reflect.Interface); i++ {
		if rv.IsNil() {
			return otlpAnyValue{}, false
		}
		rv = rv.Elem()
	}

	switch rv.Kind() {
	case reflect.Bool:
		return boolAnyValue(rv.Bool()), true
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
		return intAnyValue(rv.Int()), true
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64, reflect.Uintptr:
		return uintAnyValue(rv.Uint()), true
	case reflect.Float32:
		return float32AnyValue(float32(rv.Float())), true
	case reflect.Float64:
		return floatAnyValue(rv.Float()), true
	case reflect.String:
		return stringAnyValue(rv.String()), true
	case reflect.Slice, reflect.Array:
		if rv.Kind() == reflect.Slice && rv.IsNil() {
			return otlpAnyValue{}, false
		}
		if depth > maxLogAnyValueDepth {
			return stringAnyValue(stringifyValue(rv.Interface())), true
		}
		values := make([]otlpAnyValue, 0, rv.Len())
		for i := 0; i < rv.Len(); i++ {
			elem, _ := toAnyValue(rv.Index(i).Interface(), depth+1) // null → {}
			values = append(values, elem)
		}
		return otlpAnyValue{ArrayValue: &otlpArrayValue{Values: values}}, true
	case reflect.Map:
		if rv.IsNil() {
			return otlpAnyValue{}, false
		}
		if depth > maxLogAnyValueDepth {
			return stringAnyValue(stringifyValue(rv.Interface())), true
		}
		type entry struct {
			key string
			val reflect.Value
		}
		entries := make([]entry, 0, rv.Len())
		iter := rv.MapRange()
		for iter.Next() {
			entries = append(entries, entry{key: fmt.Sprint(iter.Key().Interface()), val: iter.Value()})
		}
		sort.Slice(entries, func(i, j int) bool { return entries[i].key < entries[j].key })
		values := make([]otlpKeyValue, 0, len(entries))
		for _, e := range entries {
			if av, ok := toAnyValue(e.val.Interface(), depth+1); ok {
				values = append(values, otlpKeyValue{Key: e.key, Value: av})
			}
		}
		return otlpAnyValue{KvlistValue: &otlpKvlistValue{Values: values}}, true
	}

	return stringAnyValue(stringifyValue(v)), true
}

// isNilPointer reports whether v is a typed nil pointer (calling a method on
// it could panic).
func isNilPointer(v any) bool {
	rv := reflect.ValueOf(v)
	return rv.Kind() == reflect.Pointer && rv.IsNil()
}

// stringifyValue renders a value that has no AnyValue mapping. JSON is tried
// first (it detects cycles); fmt is the fallback for non-JSON-able values.
func stringifyValue(v any) string {
	if b, err := json.Marshal(v); err == nil {
		return string(b)
	}
	rv := reflect.ValueOf(v)
	if rv.Kind() == reflect.Map || rv.Kind() == reflect.Slice || rv.Kind() == reflect.Array {
		// fmt does not detect cycles in containers; never risk unbounded recursion.
		return fmt.Sprintf("<%T>", v)
	}
	return fmt.Sprintf("%+v", v)
}

// logEnvelope holds the encoded parts of a request body around the records:
// prefix + record,record,... + suffix.
type logEnvelope struct {
	prefix []byte
	suffix []byte
}

func newLogEnvelope(serviceName, environment string) logEnvelope {
	str := func(s string) otlpAnyValue { return otlpAnyValue{StringValue: &s} }
	attrs := []otlpKeyValue{{Key: "service.name", Value: str(serviceName)}}
	if environment != "" {
		attrs = append(attrs, otlpKeyValue{Key: "deployment.environment.name", Value: str(environment)})
	}
	attrs = append(attrs,
		otlpKeyValue{Key: "telemetry.sdk.name", Value: str(sdkName)},
		otlpKeyValue{Key: "telemetry.sdk.language", Value: str(sdkLanguage)},
		otlpKeyValue{Key: "telemetry.sdk.version", Value: str(sdkVersion)},
	)
	resource, _ := json.Marshal(struct {
		Attributes []otlpKeyValue `json:"attributes"`
	}{attrs})
	scope, _ := json.Marshal(struct {
		Name    string `json:"name"`
		Version string `json:"version"`
	}{sdkName, sdkVersion})

	prefix := make([]byte, 0, 512)
	prefix = append(prefix, `{"resourceLogs":[{"resource":`...)
	prefix = append(prefix, resource...)
	prefix = append(prefix, `,"scopeLogs":[{"scope":`...)
	prefix = append(prefix, scope...)
	prefix = append(prefix, `,"logRecords":[`...)
	return logEnvelope{prefix: prefix, suffix: []byte(`]}]}]}`)}
}
