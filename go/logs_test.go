package callisto

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"math"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"
	"unicode/utf8"
)

// Shared cross-SDK credential vector.
const (
	testLogsPK = "pk_00112233445566778899aabbccddeeff"
	testLogsSK = "sk_000102030405060708090a0b0c0d0e0f1011121314151617"
)

// fakeLogResult scripts one Send outcome.
type fakeLogResult struct {
	status     int
	retryAfter string
	err        error
}

// fakeLogSender records every request. Results are consumed in order; once
// exhausted every send returns 200. When gate is non-nil the first send blocks
// until gate is closed (or the request context ends), after signalling entered.
type fakeLogSender struct {
	mu      sync.Mutex
	reqs    []*LogRequest
	results []fakeLogResult
	sent    chan struct{}
	gate    chan struct{}
	entered chan struct{}
	onSend  func(ctx context.Context)
	gated   bool
}

func newFakeLogSender(results ...fakeLogResult) *fakeLogSender {
	return &fakeLogSender{results: results, sent: make(chan struct{}, 10000)}
}

func (f *fakeLogSender) Send(ctx context.Context, req *LogRequest) (*LogResponse, error) {
	f.mu.Lock()
	f.reqs = append(f.reqs, req)
	var res fakeLogResult
	if len(f.results) > 0 {
		res, f.results = f.results[0], f.results[1:]
	} else {
		res = fakeLogResult{status: 200}
	}
	gate, first := f.gate, !f.gated
	f.gated = true
	onSend := f.onSend
	f.mu.Unlock()

	if onSend != nil {
		onSend(ctx)
	}
	if gate != nil && first {
		if f.entered != nil {
			f.entered <- struct{}{}
		}
		select {
		case <-gate:
		case <-ctx.Done():
			f.sent <- struct{}{}
			return nil, ctx.Err()
		}
	}
	f.sent <- struct{}{}
	if res.err != nil {
		return nil, res.err
	}
	return &LogResponse{StatusCode: res.status, RetryAfter: res.retryAfter}, nil
}

func (f *fakeLogSender) requests() []*LogRequest {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]*LogRequest(nil), f.reqs...)
}

// batchSizes returns the number of records in each request.
func (f *fakeLogSender) batchSizes(t *testing.T) []int {
	t.Helper()
	var out []int
	for _, r := range f.requests() {
		out = append(out, len(decodeRecords(t, r.Body)))
	}
	return out
}

func waitLogSends(t *testing.T, f *fakeLogSender, n int) {
	t.Helper()
	deadline := time.After(2 * time.Second)
	for i := 0; i < n; i++ {
		select {
		case <-f.sent:
		case <-deadline:
			t.Fatalf("timed out waiting for log send %d/%d", i+1, n)
		}
	}
}

// fakeClock is a settable, race-safe clock.
type fakeClock struct {
	mu  sync.Mutex
	now time.Time
}

func (c *fakeClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *fakeClock) Advance(d time.Duration) {
	c.mu.Lock()
	c.now = c.now.Add(d)
	c.mu.Unlock()
}

// waitRecorder records retry waits and returns immediately.
type waitRecorder struct {
	mu    sync.Mutex
	waits []time.Duration
}

func (w *waitRecorder) wait(ctx context.Context, d time.Duration) bool {
	w.mu.Lock()
	w.waits = append(w.waits, d)
	w.mu.Unlock()
	return ctx.Err() == nil
}

func (w *waitRecorder) all() []time.Duration {
	w.mu.Lock()
	defer w.mu.Unlock()
	return append([]time.Duration(nil), w.waits...)
}

func clearLogsEnv(t *testing.T) {
	t.Helper()
	for _, k := range []string{
		"CALLISTO_LOGS_ACCESS_KEY", "CALLISTO_LOGS_SECRET", "CALLISTO_LOGS_ENDPOINT",
		"CALLISTO_SERVICE_NAME", "OTEL_SERVICE_NAME", "CALLISTO_ENVIRONMENT",
	} {
		t.Setenv(k, "")
	}
}

// newTestLogger builds an enabled logger on a fake sender, a 1h interval (the
// timer never fires) and instant retry waits.
func newTestLogger(t *testing.T, sender *fakeLogSender, mutate ...func(*LoggerConfig)) (*Logger, *waitRecorder) {
	t.Helper()
	clearLogsEnv(t)
	cfg := LoggerConfig{AccessKey: testLogsPK, Secret: testLogsSK, Sender: sender, ServiceName: "svc"}
	for _, m := range mutate {
		m(&cfg)
	}
	waits := &waitRecorder{}
	l, err := newLogger(cfg, loggerTuning{interval: time.Hour, wait: waits.wait})
	if err != nil {
		t.Fatalf("newLogger: %v", err)
	}
	t.Cleanup(func() {
		sender.mu.Lock()
		gate := sender.gate
		sender.gate = nil
		sender.mu.Unlock()
		if gate != nil {
			select {
			case <-gate:
			default:
				close(gate)
			}
		}
		_ = l.Close(context.Background())
	})
	return l, waits
}

func mustFlush(t *testing.T, l *Logger) {
	t.Helper()
	if err := l.Flush(context.Background()); err != nil {
		t.Fatalf("Flush: %v", err)
	}
}

func decodeBody(t *testing.T, body []byte) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(body, &m); err != nil {
		t.Fatalf("decode body: %v (%s)", err, body)
	}
	return m
}

func decodeRecords(t *testing.T, body []byte) []map[string]any {
	t.Helper()
	m := decodeBody(t, body)
	rl := m["resourceLogs"].([]any)[0].(map[string]any)
	sl := rl["scopeLogs"].([]any)[0].(map[string]any)
	var out []map[string]any
	for _, r := range sl["logRecords"].([]any) {
		out = append(out, r.(map[string]any))
	}
	return out
}

// recordAttrs maps a decoded record's attributes by key.
func recordAttrs(rec map[string]any) map[string]any {
	out := map[string]any{}
	attrs, _ := rec["attributes"].([]any)
	for _, a := range attrs {
		kv := a.(map[string]any)
		out[kv["key"].(string)] = kv["value"]
	}
	return out
}

// singleRecord logs through fn, flushes and returns the one shipped record.
func singleRecord(t *testing.T, fn func(l *Logger)) map[string]any {
	t.Helper()
	sender := newFakeLogSender()
	l, _ := newTestLogger(t, sender)
	fn(l)
	mustFlush(t, l)
	reqs := sender.requests()
	if len(reqs) != 1 {
		t.Fatalf("requests = %d, want 1", len(reqs))
	}
	recs := decodeRecords(t, reqs[0].Body)
	if len(recs) != 1 {
		t.Fatalf("records = %d, want 1", len(recs))
	}
	return recs[0]
}

const goldenLogsPayload = `{
  "resourceLogs": [{
    "resource": { "attributes": [
      { "key": "service.name", "value": { "stringValue": "checkout" } },
      { "key": "deployment.environment.name", "value": { "stringValue": "production" } },
      { "key": "telemetry.sdk.name", "value": { "stringValue": "callisto-sdk" } },
      { "key": "telemetry.sdk.language", "value": { "stringValue": "LANGUAGE" } },
      { "key": "telemetry.sdk.version", "value": { "stringValue": "VERSION" } }
    ]},
    "scopeLogs": [{
      "scope": { "name": "callisto-sdk", "version": "VERSION" },
      "logRecords": [{
        "timeUnixNano": "1760745600000000000",
        "observedTimeUnixNano": "1760745600000000000",
        "severityNumber": 9,
        "severityText": "INFO",
        "body": { "stringValue": "order paid" },
        "attributes": [
          { "key": "order_id", "value": { "intValue": "42" } },
          { "key": "paid", "value": { "boolValue": true } }
        ],
        "traceId": "5b8efff798038103d269b633813fc60c",
        "spanId": "eee19b7ec3c1b174"
      }]
    }]
  }]
}`

func TestLogsGoldenPayload(t *testing.T) {
	sender := newFakeLogSender()
	clock := &fakeClock{now: time.Unix(1760745600, 0)}
	l, _ := newTestLogger(t, sender, func(c *LoggerConfig) {
		c.ServiceName = "checkout"
		c.Environment = "production"
		c.Clock = clock.Now
	})

	l.Info("order paid", map[string]any{
		"order_id": 42,
		"paid":     true,
		"trace_id": "5B8EFFF798038103D269B633813FC60C",
		"span_id":  "EEE19B7EC3C1B174",
	})
	mustFlush(t, l)

	reqs := sender.requests()
	if len(reqs) != 1 {
		t.Fatalf("requests = %d, want 1", len(reqs))
	}
	want := strings.NewReplacer("LANGUAGE", "go", "VERSION", sdkVersion).Replace(goldenLogsPayload)
	got := decodeBody(t, reqs[0].Body)
	if !reflect.DeepEqual(got, decodeBody(t, []byte(want))) {
		t.Errorf("payload mismatch\n got: %s\nwant: %s", reqs[0].Body, want)
	}
}

func TestLogsGoldenOmitsEnvironmentWhenUnset(t *testing.T) {
	sender := newFakeLogSender()
	l, _ := newTestLogger(t, sender)
	l.Info("x", nil)
	mustFlush(t, l)
	body := string(sender.requests()[0].Body)
	if strings.Contains(body, "deployment.environment.name") {
		t.Errorf("environment attribute present without an environment: %s", body)
	}
	if strings.Contains(body, `"attributes":[`+`]`) || strings.Contains(body, `"traceId"`) {
		t.Errorf("empty attributes or absent trace id should be omitted: %s", body)
	}
	if !strings.Contains(body, `"service.name","value":{"stringValue":"svc"}`) {
		t.Errorf("service.name missing: %s", body)
	}
}

func TestLogsAuthHeaderAndEndpoint(t *testing.T) {
	sender := newFakeLogSender()
	l, _ := newTestLogger(t, sender, func(c *LoggerConfig) {
		c.Endpoint = "https://logs.example.test/custom/v1/logs"
	})
	l.Info("hello", nil)
	mustFlush(t, l)

	req := sender.requests()[0]
	wantAuth := "Basic " + base64.StdEncoding.EncodeToString([]byte(testLogsPK+":"+testLogsSK))
	if got := req.Header.Get("Authorization"); got != wantAuth {
		t.Errorf("Authorization = %q, want %q", got, wantAuth)
	}
	if req.Endpoint != "https://logs.example.test/custom/v1/logs" {
		t.Errorf("endpoint = %q", req.Endpoint)
	}
	if got := req.Header.Get("Content-Type"); got != "application/json" {
		t.Errorf("Content-Type = %q", got)
	}
	if got := req.Header.Get("User-Agent"); got != "callisto-sdk-go/"+sdkVersion {
		t.Errorf("User-Agent = %q", got)
	}
}

func TestLogsDefaultEndpoint(t *testing.T) {
	clearLogsEnv(t)
	l, err := NewLogger(LoggerConfig{AccessKey: testLogsPK, Secret: testLogsSK, Sender: newFakeLogSender()})
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close(context.Background())
	if l.settings.endpoint != DefaultLogsEndpoint || DefaultLogsEndpoint != "https://logs.callistosignal.com/v1/logs" {
		t.Errorf("endpoint = %q", l.settings.endpoint)
	}
	if l.settings.serviceName != "unknown_service" {
		t.Errorf("service name = %q, want unknown_service", l.settings.serviceName)
	}
}

func TestLogsDefaultHTTPSenderOnTheWire(t *testing.T) {
	clearLogsEnv(t)
	type captured struct {
		path, auth, contentType, userAgent string
		body                               []byte
	}
	got := make(chan captured, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		got <- captured{r.URL.Path, r.Header.Get("Authorization"), r.Header.Get("Content-Type"), r.Header.Get("User-Agent"), b}
		writeJSON(w, 200, `{}`)
	}))
	defer srv.Close()

	l, err := NewLogger(LoggerConfig{AccessKey: testLogsPK, Secret: testLogsSK, Endpoint: srv.URL + "/v1/logs"})
	if err != nil {
		t.Fatal(err)
	}
	l.Warn("on the wire", map[string]any{"k": "v"})
	if err := l.Close(context.Background()); err != nil {
		t.Fatalf("Close: %v", err)
	}

	c := <-got
	user, pass, _ := (&http.Request{Header: http.Header{"Authorization": {c.auth}}}).BasicAuth()
	if c.path != "/v1/logs" || user != testLogsPK || pass != testLogsSK {
		t.Errorf("path/auth = %q %q %q", c.path, user, pass)
	}
	if c.contentType != "application/json" || c.userAgent != "callisto-sdk-go/"+sdkVersion {
		t.Errorf("headers = %q %q", c.contentType, c.userAgent)
	}
	recs := decodeRecords(t, c.body)
	if len(recs) != 1 || recs[0]["severityText"] != "WARN" {
		t.Errorf("records = %v", recs)
	}
}

func TestLogsSeverityMapping(t *testing.T) {
	sender := newFakeLogSender()
	l, _ := newTestLogger(t, sender)
	l.Debug("d", nil)
	l.Info("i", nil)
	l.Warn("w", nil)
	l.Error("e", nil)
	l.Fatal("f", nil)
	l.Log("WARNING", "alias", nil)
	l.Log("bogus", "unknown", nil)
	mustFlush(t, l)

	recs := decodeRecords(t, sender.requests()[0].Body)
	want := []struct {
		num  float64
		text string
	}{{5, "DEBUG"}, {9, "INFO"}, {13, "WARN"}, {17, "ERROR"}, {21, "FATAL"}, {13, "WARN"}, {9, "INFO"}}
	if len(recs) != len(want) {
		t.Fatalf("records = %d", len(recs))
	}
	for i, w := range want {
		if recs[i]["severityNumber"] != w.num || recs[i]["severityText"] != w.text {
			t.Errorf("record %d severity = %v %v, want %v %v", i, recs[i]["severityNumber"], recs[i]["severityText"], w.num, w.text)
		}
	}
}

type testStringer struct{}

func (testStringer) String() string { return "stringer!" }

type testStatus string

func TestLogsAnyValueMapping(t *testing.T) {
	var nilErr *testErr
	// Five nested maps are kept as kvlists; the sixth is stringified.
	deep := map[string]any{"l2": map[string]any{"l3": map[string]any{"l4": map[string]any{"l5": map[string]any{"l6": map[string]any{"l7": "x"}}}}}}
	rec := singleRecord(t, func(l *Logger) {
		l.Info("m", map[string]any{
			"int":      42,
			"int64":    int64(-9000000000),
			"uint64":   uint64(math.MaxUint64),
			"float":    1.5,
			"float32":  float32(0.1),
			"nan":      math.NaN(),
			"inf":      math.Inf(1),
			"ninf":     math.Inf(-1),
			"bool":     false,
			"str":      "",
			"nil":      nil,
			"nilerr":   nilErr,
			"list":     []any{1, "two", nil, []int{3}},
			"map":      map[string]any{"b": 2, "a": map[string]any{"x": true}, "gone": nil},
			"stringer": testStringer{},
			"named":    testStatus("ok"),
			"err":      errors.New("boom"),
			"deep":     deep,
		})
	})
	a := recordAttrs(rec)

	check := func(key string, want string) {
		t.Helper()
		got, _ := json.Marshal(a[key])
		if string(got) != want {
			t.Errorf("%s = %s, want %s", key, got, want)
		}
	}
	check("int", `{"intValue":"42"}`)
	check("int64", `{"intValue":"-9000000000"}`)
	check("uint64", `{"stringValue":"18446744073709551615"}`)
	check("float", `{"doubleValue":1.5}`)
	check("float32", `{"doubleValue":0.1}`)
	check("nan", `{"stringValue":"NaN"}`)
	check("inf", `{"stringValue":"Infinity"}`)
	check("ninf", `{"stringValue":"-Infinity"}`)
	check("bool", `{"boolValue":false}`)
	check("str", `{"stringValue":""}`)
	check("list", `{"arrayValue":{"values":[{"intValue":"1"},{"stringValue":"two"},{},{"arrayValue":{"values":[{"intValue":"3"}]}}]}}`)
	check("map", `{"kvlistValue":{"values":[{"key":"a","value":{"kvlistValue":{"values":[{"key":"x","value":{"boolValue":true}}]}}},{"key":"b","value":{"intValue":"2"}}]}}`)
	check("stringer", `{"stringValue":"stringer!"}`)
	check("named", `{"stringValue":"ok"}`)
	check("err", `{"stringValue":"boom"}`)
	node := a["deep"].(map[string]any)
	for level := 1; level <= maxLogAnyValueDepth; level++ {
		kv, ok := node["kvlistValue"].(map[string]any)
		if !ok {
			t.Fatalf("deep level %d is not a kvlist: %v", level, node)
		}
		node = kv["values"].([]any)[0].(map[string]any)["value"].(map[string]any)
	}
	if node["stringValue"] != `{"l7":"x"}` {
		t.Errorf("level 6 = %v, want it stringified", node)
	}
	for _, k := range []string{"nil", "nilerr"} {
		if _, ok := a[k]; ok {
			t.Errorf("%s should be omitted", k)
		}
	}
}

type testErr struct{}

func (*testErr) Error() string { return "never called on nil" }

func TestLogsTraceAndSpanIDs(t *testing.T) {
	cases := []struct {
		name            string
		traceID, spanID any
		moveTrace       bool
		moveSpan        bool
	}{
		{"valid", "5B8EFFF798038103D269B633813FC60C", "EEE19B7EC3C1B174", true, true},
		{"short trace", "5b8efff798038103d269b633813fc60", "eee19b7ec3c1b174", false, true},
		{"non hex", "zz8efff798038103d269b633813fc60c", "eee19b7ec3c1b17z", false, false},
		{"all zeros", "00000000000000000000000000000000", "0000000000000000", false, false},
		{"not a string", 12345, 6789, false, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rec := singleRecord(t, func(l *Logger) {
				l.Info("m", map[string]any{"trace_id": tc.traceID, "span_id": tc.spanID})
			})
			a := recordAttrs(rec)
			_, traceAttr := a["trace_id"]
			_, spanAttr := a["span_id"]
			if tc.moveTrace != (rec["traceId"] == strings.ToLower(fmt.Sprint(tc.traceID))) || tc.moveTrace == traceAttr {
				t.Errorf("trace: traceId=%v attr=%v", rec["traceId"], a["trace_id"])
			}
			if tc.moveSpan != (rec["spanId"] == strings.ToLower(fmt.Sprint(tc.spanID))) || tc.moveSpan == spanAttr {
				t.Errorf("span: spanId=%v attr=%v", rec["spanId"], a["span_id"])
			}
		})
	}
}

func TestLogsBatchSendsAt100(t *testing.T) {
	sender := newFakeLogSender()
	l, _ := newTestLogger(t, sender)
	for i := 0; i < 100; i++ {
		l.Info("r", nil)
	}
	waitLogSends(t, sender, 1)
	if got := sender.batchSizes(t); !reflect.DeepEqual(got, []int{100}) {
		t.Fatalf("batches = %v, want [100]", got)
	}

	for i := 0; i < 7; i++ {
		l.Info("rest", nil)
	}
	mustFlush(t, l)
	if got := sender.batchSizes(t); !reflect.DeepEqual(got, []int{100, 7}) {
		t.Errorf("batches after flush = %v, want [100 7]", got)
	}
}

func TestLogsIntervalSends(t *testing.T) {
	clearLogsEnv(t)
	sender := newFakeLogSender()
	l, err := newLogger(LoggerConfig{AccessKey: testLogsPK, Secret: testLogsSK, Sender: sender},
		loggerTuning{interval: 10 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close(context.Background())
	l.Info("tick", nil)
	waitLogSends(t, sender, 1)
}

func TestLogsBatchCutAt500(t *testing.T) {
	sender := newFakeLogSender()
	sender.gate = make(chan struct{})
	sender.entered = make(chan struct{}, 1)
	l, _ := newTestLogger(t, sender)

	for i := 0; i < 100; i++ {
		l.Info("first", nil)
	}
	<-sender.entered // the worker is now held inside the first send
	for i := 0; i < 1100; i++ {
		l.Info("backlog", nil)
	}
	close(sender.gate)
	mustFlush(t, l)

	if got := sender.batchSizes(t); !reflect.DeepEqual(got, []int{100, 500, 500, 100}) {
		t.Errorf("batches = %v, want [100 500 500 100]", got)
	}
}

func TestLogsBatchCutAtOneMegabyte(t *testing.T) {
	sender := newFakeLogSender()
	l, _ := newTestLogger(t, sender)
	big := strings.Repeat("a", 30000)
	for i := 0; i < 40; i++ {
		l.Info(big, nil)
	}
	mustFlush(t, l)

	total := 0
	reqs := sender.requests()
	if len(reqs) < 2 {
		t.Fatalf("requests = %d, want the ~1.2 MB backlog split", len(reqs))
	}
	for _, r := range reqs {
		if len(r.Body) > logsMaxBatchBytes {
			t.Errorf("batch is %d bytes, over %d", len(r.Body), logsMaxBatchBytes)
		}
		total += len(decodeRecords(t, r.Body))
	}
	if total != 40 {
		t.Errorf("records shipped = %d, want 40", total)
	}
}

func TestLogsTruncation(t *testing.T) {
	rec := singleRecord(t, func(l *Logger) {
		l.Info(strings.Repeat("é", 40000), map[string]any{
			"long":   strings.Repeat("x", 10000),
			"nested": []any{strings.Repeat("y", 9000)},
			"short":  "fine",
		})
	})
	body := rec["body"].(map[string]any)["stringValue"].(string)
	if n := utf8.RuneCountInString(body); n != maxLogBodyChars || !strings.HasSuffix(body, "…") {
		t.Errorf("body runes = %d (suffix ok=%v), want %d ending in …", n, strings.HasSuffix(body, "…"), maxLogBodyChars)
	}
	a := recordAttrs(rec)
	long := a["long"].(map[string]any)["stringValue"].(string)
	if n := utf8.RuneCountInString(long); n != maxLogAttrValueChars || !strings.HasSuffix(long, "…") {
		t.Errorf("attr runes = %d, want %d ending in …", n, maxLogAttrValueChars)
	}
	nested := a["nested"].(map[string]any)["arrayValue"].(map[string]any)["values"].([]any)[0].(map[string]any)["stringValue"].(string)
	if utf8.RuneCountInString(nested) != maxLogAttrValueChars {
		t.Errorf("nested string not truncated: %d", utf8.RuneCountInString(nested))
	}
	if a["short"].(map[string]any)["stringValue"] != "fine" {
		t.Errorf("short value changed")
	}
}

func TestLogsAttributeCap(t *testing.T) {
	attrs := map[string]any{}
	for i := 0; i < 200; i++ {
		attrs[fmt.Sprintf("k%03d", i)] = i
	}
	rec := singleRecord(t, func(l *Logger) { l.Info("m", attrs) })
	got := rec["attributes"].([]any)
	if len(got) != maxLogAttributes {
		t.Fatalf("attributes = %d, want %d", len(got), maxLogAttributes)
	}
	if last := got[len(got)-1].(map[string]any)["key"]; last != "k127" {
		t.Errorf("kept attributes are not the first 128 by key: last = %v", last)
	}
}

func TestLogs400DropsWithoutRetry(t *testing.T) {
	for _, status := range []int{400, 413, 415, 500} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			sender := newFakeLogSender(fakeLogResult{status: status})
			l, waits := newTestLogger(t, sender)
			l.Info("a", nil)
			l.Info("b", nil)
			mustFlush(t, l)
			if n := len(sender.requests()); n != 1 {
				t.Errorf("attempts = %d, want 1", n)
			}
			if len(waits.all()) != 0 {
				t.Errorf("waited %v, want no retry", waits.all())
			}
			if l.DroppedCount() != 2 {
				t.Errorf("dropped = %d, want 2", l.DroppedCount())
			}
		})
	}
}

func TestLogs429RetryAfterThenSuccess(t *testing.T) {
	sender := newFakeLogSender(fakeLogResult{status: 429, retryAfter: "1"})
	l, waits := newTestLogger(t, sender)
	l.Info("retry me", nil)
	mustFlush(t, l)

	reqs := sender.requests()
	if len(reqs) != 2 {
		t.Fatalf("attempts = %d, want 2", len(reqs))
	}
	if string(reqs[0].Body) != string(reqs[1].Body) {
		t.Errorf("retry body differs from the first attempt")
	}
	if got := waits.all(); !reflect.DeepEqual(got, []time.Duration{time.Second}) {
		t.Errorf("waits = %v, want [1s]", got)
	}
	if l.DroppedCount() != 0 {
		t.Errorf("dropped = %d, want 0", l.DroppedCount())
	}
}

func TestLogsRetryAfterParsing(t *testing.T) {
	cases := map[string]time.Duration{"": 5 * time.Second, "soon": 5 * time.Second, "-3": 5 * time.Second, " 2 ": 2 * time.Second, "600": 60 * time.Second}
	for in, want := range cases {
		if got := parseLogsRetryAfter(in); got != want {
			t.Errorf("parseLogsRetryAfter(%q) = %v, want %v", in, got, want)
		}
	}
}

func TestLogsThreeFailedAttemptsDrop(t *testing.T) {
	netErr := errors.New("connection refused")
	sender := newFakeLogSender(fakeLogResult{err: netErr}, fakeLogResult{err: netErr}, fakeLogResult{err: netErr})
	l, waits := newTestLogger(t, sender)
	l.Info("lost", nil)
	mustFlush(t, l)

	if n := len(sender.requests()); n != 3 {
		t.Errorf("attempts = %d, want 3", n)
	}
	if got := waits.all(); !reflect.DeepEqual(got, []time.Duration{time.Second, 5 * time.Second}) {
		t.Errorf("waits = %v, want [1s 5s]", got)
	}
	if l.DroppedCount() != 1 {
		t.Errorf("dropped = %d, want 1", l.DroppedCount())
	}

	// 503 three times is dropped the same way.
	sender2 := newFakeLogSender(fakeLogResult{status: 503}, fakeLogResult{status: 503, retryAfter: "2"}, fakeLogResult{status: 503})
	l2, waits2 := newTestLogger(t, sender2)
	l2.Info("lost", nil)
	mustFlush(t, l2)
	if n := len(sender2.requests()); n != 3 || l2.DroppedCount() != 1 {
		t.Errorf("503: attempts = %d dropped = %d, want 3 and 1", n, l2.DroppedCount())
	}
	if got := waits2.all(); !reflect.DeepEqual(got, []time.Duration{5 * time.Second, 2 * time.Second}) {
		t.Errorf("503 waits = %v, want [5s 2s]", got)
	}
}

func TestLogsAuthFailurePauses(t *testing.T) {
	for _, status := range []int{401, 403} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			sender := newFakeLogSender(fakeLogResult{status: status})
			clock := &fakeClock{now: time.Unix(1760745600, 0)}
			l, waits := newTestLogger(t, sender, func(c *LoggerConfig) { c.Clock = clock.Now })

			l.Info("rejected", nil)
			mustFlush(t, l)
			if len(sender.requests()) != 1 || len(waits.all()) != 0 {
				t.Fatalf("attempts = %d waits = %v, want 1 and none", len(sender.requests()), waits.all())
			}

			clock.Advance(30 * time.Second)
			l.Info("during pause", nil)
			mustFlush(t, l)
			if len(sender.requests()) != 1 {
				t.Errorf("sent during the pause")
			}
			if l.DroppedCount() != 2 {
				t.Errorf("dropped = %d, want 2", l.DroppedCount())
			}

			clock.Advance(31 * time.Second)
			l.Info("after pause", nil)
			mustFlush(t, l)
			if len(sender.requests()) != 2 {
				t.Errorf("attempts after the pause = %d, want 2", len(sender.requests()))
			}
		})
	}
}

func TestLogsFullQueueDropsAndCounts(t *testing.T) {
	sender := newFakeLogSender()
	sender.gate = make(chan struct{})
	sender.entered = make(chan struct{}, 1)
	l, _ := newTestLogger(t, sender)

	for i := 0; i < 100; i++ {
		l.Info("first", nil)
	}
	<-sender.entered
	start := time.Now()
	for i := 0; i < logsMaxQueue+7; i++ {
		l.Info("fill", nil)
	}
	if time.Since(start) > time.Second {
		t.Errorf("log calls blocked on a full queue")
	}
	if got := l.DroppedCount(); got != 7 {
		t.Errorf("dropped = %d, want 7", got)
	}
	close(sender.gate)
	mustFlush(t, l)

	total := 0
	for _, n := range sender.batchSizes(t) {
		total += n
	}
	if total != 100+logsMaxQueue {
		t.Errorf("shipped = %d, want %d", total, 100+logsMaxQueue)
	}
}

func TestLogsFlushIsBounded(t *testing.T) {
	sender := newFakeLogSender()
	sender.gate = make(chan struct{})
	l, _ := newTestLogger(t, sender, func(c *LoggerConfig) { c.FlushTimeout = 50 * time.Millisecond })
	l.Info("stuck", nil)

	start := time.Now()
	if err := l.Flush(context.Background()); err == nil {
		t.Error("Flush returned nil although the send never finished")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	_ = l.Flush(ctx)
	if time.Since(start) > time.Second {
		t.Errorf("Flush took %v, want it bounded", time.Since(start))
	}
}

func TestLogsCloseIsIdempotentAndStopsLogging(t *testing.T) {
	sender := newFakeLogSender()
	l, _ := newTestLogger(t, sender)
	l.Info("before close", nil)
	if err := l.Close(context.Background()); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if n := len(sender.requests()); n != 1 {
		t.Fatalf("Close did not flush: %d requests", n)
	}
	select {
	case <-l.done:
	default:
		t.Fatal("worker still running after Close")
	}
	l.Info("after close", nil)
	if err := l.Flush(context.Background()); err != nil || l.Close(context.Background()) != nil {
		t.Errorf("calls after Close should be nil no-ops")
	}
	if n := len(sender.requests()); n != 1 {
		t.Errorf("sent after Close: %d requests", n)
	}
}

type panicStringer struct{}

func (panicStringer) String() string { panic("boom") }

func TestLogsNeverPanics(t *testing.T) {
	sender := newFakeLogSender()
	l, _ := newTestLogger(t, sender)
	cyclic := map[string]any{}
	cyclic["self"] = cyclic
	l.Info("bad", map[string]any{"p": panicStringer{}})
	l.Info("cyclic", map[string]any{"c": cyclic})
	mustFlush(t, l)
	if l.DroppedCount() != 1 {
		t.Errorf("dropped = %d, want 1 (the panicking record)", l.DroppedCount())
	}
	if n := len(sender.requests()); n != 1 {
		t.Errorf("requests = %d, want 1 (the cyclic record still ships)", n)
	}
}

func TestLogsDisabledWithoutCredentials(t *testing.T) {
	clearLogsEnv(t)
	sender := newFakeLogSender()
	l, err := NewLogger(LoggerConfig{Sender: sender, Endpoint: "not a url"})
	if err != nil {
		t.Fatalf("NewLogger without credentials: %v", err)
	}
	if l.Enabled() {
		t.Fatal("logger should be disabled")
	}
	if l.queue != nil || l.done != nil {
		t.Fatal("a disabled logger must not start its worker goroutine")
	}
	l.Info("ignored", map[string]any{"a": 1})
	l.Log(LogLevelError, "ignored", nil)
	if l.Flush(context.Background()) != nil || l.Close(context.Background()) != nil {
		t.Error("Flush/Close on a disabled logger should return nil")
	}
	if len(sender.requests()) != 0 || l.DroppedCount() != 0 {
		t.Errorf("disabled logger sent or counted something")
	}

	var nilLogger *Logger
	nilLogger.Info("nil receiver", nil)
	_ = nilLogger.Flush(context.Background())
	_ = nilLogger.Close(context.Background())
	if nilLogger.Enabled() || nilLogger.DroppedCount() != 0 {
		t.Error("nil logger should be a no-op")
	}
}

func TestLogsConfigurationErrors(t *testing.T) {
	cases := []struct {
		name string
		cfg  LoggerConfig
		env  map[string]string
	}{
		{name: "only access key", cfg: LoggerConfig{AccessKey: testLogsPK}},
		{name: "only secret", cfg: LoggerConfig{Secret: testLogsSK}},
		{name: "only access key from env", env: map[string]string{"CALLISTO_LOGS_ACCESS_KEY": testLogsPK}},
		{name: "uppercase hex", cfg: LoggerConfig{AccessKey: "pk_" + strings.ToUpper(testLogsPK[3:]), Secret: testLogsSK}},
		{name: "wrong prefix", cfg: LoggerConfig{AccessKey: "sk_00112233445566778899aabbccddeeff", Secret: testLogsSK}},
		{name: "short secret", cfg: LoggerConfig{AccessKey: testLogsPK, Secret: testLogsSK[:40]}},
		{name: "messaging key", cfg: LoggerConfig{AccessKey: "cid", Secret: "secretkey"}},
		{name: "relative endpoint", cfg: LoggerConfig{AccessKey: testLogsPK, Secret: testLogsSK, Endpoint: "/v1/logs"}},
		{name: "ftp endpoint", cfg: LoggerConfig{AccessKey: testLogsPK, Secret: testLogsSK, Endpoint: "ftp://logs.example/v1/logs"}},
		{name: "bad endpoint from env", cfg: LoggerConfig{AccessKey: testLogsPK, Secret: testLogsSK}, env: map[string]string{"CALLISTO_LOGS_ENDPOINT": "logs.example"}},
		{name: "http endpoint", cfg: LoggerConfig{AccessKey: testLogsPK, Secret: testLogsSK, Endpoint: "http://logs.example/v1/logs"}},
		{name: "http endpoint from env", cfg: LoggerConfig{AccessKey: testLogsPK, Secret: testLogsSK}, env: map[string]string{"CALLISTO_LOGS_ENDPOINT": "http://logs.callistosignal.com/v1/logs"}},
		{name: "http lookalike localhost", cfg: LoggerConfig{AccessKey: testLogsPK, Secret: testLogsSK, Endpoint: "http://localhost.evil.example/v1/logs"}},
		{name: "http non-loopback ip", cfg: LoggerConfig{AccessKey: testLogsPK, Secret: testLogsSK, Endpoint: "http://127.0.0.2/v1/logs"}},
		{name: "https without host", cfg: LoggerConfig{AccessKey: testLogsPK, Secret: testLogsSK, Endpoint: "https:///v1/logs"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			clearLogsEnv(t)
			for k, v := range tc.env {
				t.Setenv(k, v)
			}
			l, err := NewLogger(tc.cfg)
			if err == nil {
				_ = l.Close(context.Background())
				t.Fatal("expected a configuration error")
			}
			if l != nil {
				t.Error("logger returned together with an error")
			}
			if strings.Contains(err.Error(), testLogsSK[3:]) {
				t.Errorf("error message leaks the secret: %v", err)
			}
		})
	}
}

func TestLogsEnvFallback(t *testing.T) {
	clearLogsEnv(t)
	t.Setenv("CALLISTO_LOGS_ACCESS_KEY", testLogsPK)
	t.Setenv("CALLISTO_LOGS_SECRET", testLogsSK)
	t.Setenv("CALLISTO_LOGS_ENDPOINT", "http://localhost:4318/v1/logs")
	t.Setenv("OTEL_SERVICE_NAME", "from-otel")
	t.Setenv("CALLISTO_ENVIRONMENT", "staging")
	l, err := NewLogger(LoggerConfig{Sender: newFakeLogSender()})
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close(context.Background())
	s := l.settings
	if !s.enabled || s.endpoint != "http://localhost:4318/v1/logs" || s.serviceName != "from-otel" || s.environment != "staging" {
		t.Errorf("settings = %+v", s)
	}

	t.Setenv("CALLISTO_SERVICE_NAME", "from-callisto")
	l2, _ := NewLogger(LoggerConfig{Sender: newFakeLogSender()})
	defer l2.Close(context.Background())
	if l2.settings.serviceName != "from-callisto" {
		t.Errorf("CALLISTO_SERVICE_NAME should win over OTEL_SERVICE_NAME, got %q", l2.settings.serviceName)
	}
}

func TestClientLogsNoopWithoutKey(t *testing.T) {
	clearLogsEnv(t)
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {})
	if c.Logs == nil || c.Logs.Enabled() {
		t.Fatal("client.Logs should be a non-nil, disabled logger")
	}
	c.Logs.Info("no-op", nil)
}

func TestLogsEndpointHTTPSRuleAccepts(t *testing.T) {
	for _, endpoint := range []string{
		"https://logs.example/v1/logs",
		"HTTPS://logs.example/v1/logs",
		"http://localhost/v1/logs",
		"http://localhost:4318/v1/logs",
		"http://LOCALHOST:4318/v1/logs",
		"http://127.0.0.1:4318/v1/logs",
		"http://[::1]:4318/v1/logs",
		"http://logs.localhost/v1/logs",
	} {
		t.Run(endpoint, func(t *testing.T) {
			clearLogsEnv(t)
			l, err := NewLogger(LoggerConfig{AccessKey: testLogsPK, Secret: testLogsSK, Endpoint: endpoint, Sender: newFakeLogSender()})
			if err != nil {
				t.Fatalf("endpoint %q rejected: %v", endpoint, err)
			}
			defer l.Close(context.Background())
			if !l.Enabled() {
				t.Error("logger should be enabled")
			}
		})
	}
}

// captureStdLog redirects the standard logger (the channel NewClient warns on)
// for the duration of the test.
func captureStdLog(t *testing.T) *strings.Builder {
	t.Helper()
	buf := &strings.Builder{}
	prevOut, prevFlags := log.Writer(), log.Flags()
	log.SetOutput(buf)
	log.SetFlags(0)
	t.Cleanup(func() {
		log.SetOutput(prevOut)
		log.SetFlags(prevFlags)
	})
	return buf
}

func TestClientWithBadLogsSettingsWarnsOnceAndDisablesLogs(t *testing.T) {
	cases := []struct {
		name string
		opts Options
		env  map[string]string
	}{
		{name: "only secret from env", env: map[string]string{"CALLISTO_LOGS_SECRET": testLogsSK}},
		{name: "only access key from env", env: map[string]string{"CALLISTO_LOGS_ACCESS_KEY": testLogsPK}},
		{name: "malformed secret from env", env: map[string]string{"CALLISTO_LOGS_ACCESS_KEY": testLogsPK, "CALLISTO_LOGS_SECRET": testLogsSK[:40]}},
		{name: "swapped pair from env", env: map[string]string{"CALLISTO_LOGS_ACCESS_KEY": testLogsSK, "CALLISTO_LOGS_SECRET": testLogsPK}},
		{name: "http endpoint from env", env: map[string]string{
			"CALLISTO_LOGS_ACCESS_KEY": testLogsPK, "CALLISTO_LOGS_SECRET": testLogsSK,
			"CALLISTO_LOGS_ENDPOINT": "http://logs.example/v1/logs",
		}},
		{name: "half pair as options", opts: Options{LogsAccessKey: testLogsPK}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			clearLogsEnv(t)
			for k, v := range tc.env {
				t.Setenv(k, v)
			}
			buf := captureStdLog(t)
			opts := tc.opts
			opts.ClientID, opts.APIKey = "cid", "secretkey"
			c, err := NewClient(opts)
			if err != nil {
				t.Fatalf("NewClient must not fail on Logs settings: %v", err)
			}
			defer c.Close()
			if c.Logs == nil || c.Logs.Enabled() {
				t.Fatal("client.Logs should be a non-nil, disabled logger")
			}
			c.Logs.Info("dropped", nil)
			out := buf.String()
			if n := strings.Count(out, "\n"); n != 1 {
				t.Fatalf("warnings = %d, want 1: %q", n, out)
			}
			if !strings.Contains(out, "logs are disabled") {
				t.Errorf("warning text = %q", out)
			}
			for _, secret := range []string{testLogsPK[3:], testLogsSK[3:], testLogsSK[3:40]} {
				if strings.Contains(out, secret) {
					t.Errorf("warning leaks a credential: %q", out)
				}
			}
		})
	}
}

func TestClientWithValidLogsSettingsDoesNotWarn(t *testing.T) {
	clearLogsEnv(t)
	buf := captureStdLog(t)
	c, err := NewClient(Options{
		ClientID: "cid", APIKey: "secretkey",
		LogsAccessKey: testLogsPK, LogsSecret: testLogsSK,
		LogsEndpoint: "http://localhost:4318/v1/logs", LogSender: newFakeLogSender(),
	})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if !c.Logs.Enabled() {
		t.Error("client.Logs should be enabled")
	}
	if buf.Len() != 0 {
		t.Errorf("unexpected warning: %q", buf.String())
	}
}

func TestClientWithoutMessagingCredentialsStillFails(t *testing.T) {
	clearLogsEnv(t)
	t.Setenv("CALLISTO_CLIENT_ID", "")
	t.Setenv("CALLISTO_API_KEY", "")
	if _, err := NewClient(Options{LogsAccessKey: testLogsPK, LogsSecret: testLogsSK}); err == nil {
		t.Fatal("NewClient without messaging credentials should fail")
	}
}

func TestLogsNestedContainersAreCapped(t *testing.T) {
	list := make([]any, 300)
	for i := range list {
		list[i] = i
	}
	m := map[string]any{}
	for i := 0; i < 300; i++ {
		m[fmt.Sprintf("k%03d", i)] = i
	}
	av, ok := toAnyValue(map[string]any{"list": list, "map": m, "small": []int{1, 2}}, 1)
	if !ok || av.KvlistValue == nil {
		t.Fatal("expected a kvlist")
	}
	byKey := map[string]otlpAnyValue{}
	for _, kv := range av.KvlistValue.Values {
		byKey[kv.Key] = kv.Value
	}
	if n := len(byKey["list"].ArrayValue.Values); n != maxLogNestedItems {
		t.Errorf("nested list items = %d, want %d", n, maxLogNestedItems)
	}
	entries := byKey["map"].KvlistValue.Values
	if len(entries) != maxLogNestedItems || entries[len(entries)-1].Key != "k127" {
		t.Errorf("nested map entries = %d (last %q), want %d ending at k127", len(entries), entries[len(entries)-1].Key, maxLogNestedItems)
	}
	if n := len(byKey["small"].ArrayValue.Values); n != 2 {
		t.Errorf("small list items = %d, want 2", n)
	}
	top, _ := toAnyValue(list, 1)
	if n := len(top.ArrayValue.Values); n != maxLogNestedItems {
		t.Errorf("top-level list items = %d, want %d", n, maxLogNestedItems)
	}
}

func TestClientCloseClosesLogger(t *testing.T) {
	clearLogsEnv(t)
	sender := newFakeLogSender()
	c, err := NewClient(Options{
		ClientID: "cid", APIKey: "secretkey",
		LogsAccessKey: testLogsPK, LogsSecret: testLogsSK, LogSender: sender,
		ServiceName: "api", Environment: "production",
	})
	if err != nil {
		t.Fatal(err)
	}
	c.Logs.Info("queued", nil)
	c.Close()
	if n := len(sender.requests()); n != 1 {
		t.Fatalf("Close did not flush the logger: %d requests", n)
	}
	if c.Logs.Enabled() {
		t.Error("logger still enabled after client Close")
	}
	body := string(sender.requests()[0].Body)
	if !strings.Contains(body, `"stringValue":"api"`) || !strings.Contains(body, `"stringValue":"production"`) {
		t.Errorf("client service name / environment not applied: %s", body)
	}
}

func TestLogsIsolatedFromMessagingCredentials(t *testing.T) {
	clearLogsEnv(t)
	const msgID, msgKey = "msg-client-0001", "msg-apikey-0001"
	var mu sync.Mutex
	var messagingDump strings.Builder
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		mu.Lock()
		fmt.Fprintf(&messagingDump, "%s %v %s", r.URL.String(), r.Header, b)
		mu.Unlock()
		writeJSON(w, 200, `{"credit":1,"currency":"XOF"}`)
	}))
	defer srv.Close()

	sender := newFakeLogSender()
	c, err := NewClient(Options{
		ClientID: msgID, APIKey: msgKey, BaseURL: srv.URL,
		LogsAccessKey: testLogsPK, LogsSecret: testLogsSK, LogSender: sender,
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := c.Balance.Get(context.Background(), BalanceParams{}); err != nil {
		t.Fatal(err)
	}
	c.Logs.Info("hello", map[string]any{"k": "v"})
	c.Close()

	reqs := sender.requests()
	if len(reqs) != 1 {
		t.Fatalf("log requests = %d", len(reqs))
	}
	logsDump := fmt.Sprintf("%s %v %s", reqs[0].Endpoint, reqs[0].Header, reqs[0].Body)
	msgBasic := base64.StdEncoding.EncodeToString([]byte(msgID + ":" + msgKey))
	for _, forbidden := range []string{msgID, msgKey, msgBasic, "client_id", "api_key"} {
		if strings.Contains(logsDump, forbidden) {
			t.Errorf("logs request leaks %q: %s", forbidden, logsDump)
		}
	}
	mu.Lock()
	dump := messagingDump.String()
	mu.Unlock()
	logsBasic := base64.StdEncoding.EncodeToString([]byte(testLogsPK + ":" + testLogsSK))
	for _, forbidden := range []string{testLogsSK, testLogsPK, logsBasic} {
		if strings.Contains(dump, forbidden) {
			t.Errorf("messaging request leaks %q: %s", forbidden, dump)
		}
	}
	if !strings.Contains(dump, "/sms/balance") {
		t.Errorf("messaging request not captured: %s", dump)
	}
}

// countAnyValues counts the encoded AnyValues (scalars and containers) under v.
func countAnyValues(v otlpAnyValue) int {
	n := 1
	if v.ArrayValue != nil {
		for _, e := range v.ArrayValue.Values {
			n += countAnyValues(e)
		}
	}
	if v.KvlistValue != nil {
		for _, kv := range v.KvlistValue.Values {
			n += countAnyValues(kv.Value)
		}
	}
	return n
}

func TestLogsSharedSubSlicesCannotExplodeTheRecord(t *testing.T) {
	for _, levels := range []int{5, 12} {
		t.Run(fmt.Sprint(levels), func(t *testing.T) {
			var shared any = func() []any {
				s := make([]any, 128)
				for i := range s {
					s[i] = i
				}
				return s
			}()
			for i := 1; i < levels; i++ {
				next := make([]any, 128)
				for j := range next {
					next[j] = shared // the same slice, 128 times, at every level
				}
				shared = next
			}
			attrs := map[string]any{"a": shared, "b": map[string]any{"c": shared}, "z_later": "dropped"}
			start := time.Now()
			rec := buildLogRecord(time.Unix(0, 0), LogLevelInfo, "bomb", attrs)
			elapsed := time.Since(start)
			if elapsed > 100*time.Millisecond {
				t.Errorf("encoding took %v, want well under 100ms", elapsed)
			}
			total := 0
			for _, kv := range rec.Attributes {
				if kv.Key == "z_later" {
					t.Error("an attribute after the spent budget should be dropped")
				}
				total += countAnyValues(kv.Value)
			}
			if total == 0 || total > maxLogRecordValues {
				t.Errorf("encoded values = %d, want 1..%d", total, maxLogRecordValues)
			}
		})
	}
}

func TestLogsValueBudgetDropsLaterAttributes(t *testing.T) {
	attrs := map[string]any{}
	for i := 0; i < 30; i++ {
		list := make([]int, 100)
		attrs[fmt.Sprintf("k%02d", i)] = list
	}
	rec := buildLogRecord(time.Unix(0, 0), LogLevelInfo, "m", attrs)
	total := 0
	for _, kv := range rec.Attributes {
		total += countAnyValues(kv.Value)
	}
	if total != maxLogRecordValues {
		t.Errorf("encoded values = %d, want %d", total, maxLogRecordValues)
	}
	// 20 full lists (20 x 101 = 2020 values), then the 21st is cut after 27 items.
	if len(rec.Attributes) != 21 || len(rec.Attributes[20].Value.ArrayValue.Values) != 27 {
		t.Errorf("attributes = %d, last list = %d items", len(rec.Attributes), len(rec.Attributes[len(rec.Attributes)-1].Value.ArrayValue.Values))
	}
}

func TestLogsTooDeepContainerIsStringifiedWithinBounds(t *testing.T) {
	huge := make([]int, 10000)
	deep := map[string]any{"l2": map[string]any{"l3": map[string]any{"l4": map[string]any{"l5": map[string]any{"l6": huge}}}}}
	av, _ := toAnyValue(deep, 1)
	for i := 0; i < maxLogAnyValueDepth; i++ {
		av = av.KvlistValue.Values[0].Value
	}
	if av.StringValue == nil {
		t.Fatal("level 6 should be stringified")
	}
	s := *av.StringValue
	if n := utf8.RuneCountInString(s); n > maxLogAttrValueChars || !strings.HasSuffix(s, "…") || !strings.HasPrefix(s, "[0,0,0") {
		t.Errorf("stringified = %d chars, prefix %q", n, s[:min(len(s), 10)])
	}
}
