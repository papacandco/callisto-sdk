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
	maxLogNestedItems    = 128 // per nested list / map; extra items are dropped
	// maxLogRecordValues is the encoded-value budget of one record: scalars and
	// containers, at every level and across all attributes. A shared sub-map or
	// sub-slice counts each time it is visited.
	maxLogRecordValues  = 2048
	maxLogAnyValueDepth = 5
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
	budget := newValueBudget()
	for _, k := range keys {
		if len(rec.Attributes) >= maxLogAttributes || budget.spent() {
			break // budget spent: this and every later attribute are dropped
		}
		if v, ok := toAnyValueB(attrs[k], 1, budget); ok {
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

// valueBudget counts the encoded values left for one record.
type valueBudget struct{ left int }

func newValueBudget() *valueBudget { return &valueBudget{left: maxLogRecordValues} }

func (b *valueBudget) take() bool {
	if b.left <= 0 {
		return false
	}
	b.left--
	return true
}

func (b *valueBudget) spent() bool { return b.left <= 0 }

// toAnyValue maps a Go value to an OTLP AnyValue with a fresh record budget.
func toAnyValue(v any, depth int) (otlpAnyValue, bool) {
	return toAnyValueB(v, depth, newValueBudget())
}

// toAnyValueB maps a Go value to an OTLP AnyValue. ok is false for nil (the
// attribute is omitted) and when the record's value budget is spent. depth is 1
// for a top-level attribute value; lists and maps nested deeper than
// maxLogAnyValueDepth are stringified (within the same budget).
func toAnyValueB(v any, depth int, budget *valueBudget) (otlpAnyValue, bool) {
	if !budget.take() {
		return otlpAnyValue{}, false
	}
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
			return stringAnyValue(stringifyValue(rv.Interface(), budget)), true
		}
		n := min(rv.Len(), maxLogNestedItems)
		values := make([]otlpAnyValue, 0, n)
		for i := 0; i < n && !budget.spent(); i++ {
			elem, _ := toAnyValueB(rv.Index(i).Interface(), depth+1, budget) // null → {}
			values = append(values, elem)
		}
		return otlpAnyValue{ArrayValue: &otlpArrayValue{Values: values}}, true
	case reflect.Map:
		if rv.IsNil() {
			return otlpAnyValue{}, false
		}
		if depth > maxLogAnyValueDepth {
			return stringAnyValue(stringifyValue(rv.Interface(), budget)), true
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
		values := make([]otlpKeyValue, 0, min(len(entries), maxLogNestedItems))
		for _, e := range entries {
			if len(values) >= maxLogNestedItems || budget.spent() {
				break
			}
			if av, ok := toAnyValueB(e.val.Interface(), depth+1, budget); ok {
				values = append(values, otlpKeyValue{Key: e.key, Value: av})
			}
		}
		return otlpAnyValue{KvlistValue: &otlpKvlistValue{Values: values}}, true
	}

	return stringAnyValue(stringifyValue(v, budget)), true
}

// isNilPointer reports whether v is a typed nil pointer (calling a method on
// it could panic).
func isNilPointer(v any) bool {
	rv := reflect.ValueOf(v)
	return rv.Kind() == reflect.Pointer && rv.IsNil()
}

// stringifyValue renders a value that has no AnyValue mapping (or a container
// nested too deep) as JSON. It walks the value itself instead of calling
// json.Marshal on it, so the work is bounded: every nested item takes one value
// from the record budget and the output stops past maxLogAttrValueChars. A
// shared or cyclic structure therefore cannot explode. A cut ends with "…".
func stringifyValue(v any, budget *valueBudget) string {
	rv := reflect.ValueOf(v)
	switch rv.Kind() {
	case reflect.Complex64, reflect.Complex128, reflect.Func, reflect.Chan, reflect.UnsafePointer:
		return fmt.Sprintf("%+v", v)
	}
	w := &boundedJSON{budget: budget, limit: maxLogAttrValueChars}
	if !w.value(rv, false, 0) && w.cut {
		w.sb.WriteString("…")
	}
	return w.sb.String()
}

// boundedJSON writes a reflect.Value as JSON (map keys sorted, like
// encoding/json) within a value budget and a character limit.
type boundedJSON struct {
	sb     strings.Builder
	chars  int
	limit  int
	budget *valueBudget
	cut    bool // stopped because the budget was spent
}

var (
	jsonMarshalerType = reflect.TypeOf((*json.Marshaler)(nil)).Elem()
	textMarshalerType = reflect.TypeOf((*interface{ MarshalText() ([]byte, error) })(nil)).Elem()
)

func (w *boundedJSON) write(s string) bool {
	if w.chars > w.limit {
		return false
	}
	w.sb.WriteString(s)
	w.chars += utf8.RuneCountInString(s)
	return true
}

func (w *boundedJSON) marshal(rv reflect.Value) bool {
	if b, err := json.Marshal(rv.Interface()); err == nil {
		return w.write(string(b))
	}
	b, _ := json.Marshal(fmt.Sprintf("%+v", rv.Interface()))
	return w.write(string(b))
}

// value writes rv; count is true for a nested item (the root already took its
// value). It returns false once the output must stop.
func (w *boundedJSON) value(rv reflect.Value, count bool, depth int) bool {
	if count && !w.budget.take() {
		w.cut = true
		return false
	}
	if !rv.IsValid() {
		return w.write("null")
	}
	if depth > 64 {
		return w.write(`"…"`)
	}
	if rv.Kind() == reflect.Pointer || rv.Kind() == reflect.Interface || rv.Kind() == reflect.Map || rv.Kind() == reflect.Slice {
		if rv.IsNil() {
			return w.write("null")
		}
	}
	if rv.CanInterface() && (rv.Type().Implements(jsonMarshalerType) || rv.Type().Implements(textMarshalerType)) {
		return w.marshal(rv)
	}
	switch rv.Kind() {
	case reflect.Pointer, reflect.Interface:
		return w.value(rv.Elem(), false, depth+1)
	case reflect.Bool, reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
		reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64, reflect.Uintptr,
		reflect.Float32, reflect.Float64, reflect.String:
		if !rv.CanInterface() {
			return w.write("null")
		}
		return w.marshal(rv)
	case reflect.Slice, reflect.Array:
		if rv.Kind() == reflect.Slice && rv.Type().Elem().Kind() == reflect.Uint8 && rv.CanInterface() {
			return w.marshal(rv) // []byte → base64, like encoding/json
		}
		if !w.write("[") {
			return false
		}
		for i := 0; i < rv.Len(); i++ {
			if i > 0 && !w.write(",") {
				return false
			}
			if !w.value(rv.Index(i), true, depth+1) {
				return false
			}
		}
		return w.write("]")
	case reflect.Map:
		keys := rv.MapKeys()
		names := make([]string, len(keys))
		order := make([]int, len(keys))
		for i, k := range keys {
			names[i] = fmt.Sprint(k.Interface())
			order[i] = i
		}
		sort.Slice(order, func(i, j int) bool { return names[order[i]] < names[order[j]] })
		if !w.write("{") {
			return false
		}
		for n, i := range order {
			if n > 0 && !w.write(",") {
				return false
			}
			key, _ := json.Marshal(names[i])
			if !w.write(string(key)+":") || !w.value(rv.MapIndex(keys[i]), true, depth+1) {
				return false
			}
		}
		return w.write("}")
	case reflect.Struct:
		if !w.write("{") {
			return false
		}
		first := true
		t := rv.Type()
		for i := 0; i < t.NumField(); i++ {
			f := t.Field(i)
			if !f.IsExported() {
				continue
			}
			name := f.Name
			if tag, ok := f.Tag.Lookup("json"); ok {
				tagName, _, _ := strings.Cut(tag, ",")
				if tagName == "-" {
					continue
				}
				if tagName != "" {
					name = tagName
				}
			}
			if !first && !w.write(",") {
				return false
			}
			first = false
			key, _ := json.Marshal(name)
			if !w.write(string(key)+":") || !w.value(rv.Field(i), true, depth+1) {
				return false
			}
		}
		return w.write("}")
	default:
		b, _ := json.Marshal(fmt.Sprintf("<%s>", rv.Type()))
		return w.write(string(b))
	}
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
