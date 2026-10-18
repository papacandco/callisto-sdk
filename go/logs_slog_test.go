package callisto

import (
	"context"
	"encoding/json"
	"errors"
	"io/fs"
	"log/slog"
	"reflect"
	"testing"
	"time"
)

// attrJSON returns a decoded record attribute re-encoded as compact JSON.
func attrJSON(t *testing.T, rec map[string]any, key string) string {
	t.Helper()
	v, ok := recordAttrs(rec)[key]
	if !ok {
		return ""
	}
	b, _ := json.Marshal(v)
	return string(b)
}

func TestSlogHandlerLevels(t *testing.T) {
	sender := newFakeLogSender()
	l, _ := newTestLogger(t, sender)
	log := slog.New(l.Handler())
	ctx := context.Background()

	log.Log(ctx, slog.LevelDebug-4, "trace")
	log.Debug("debug")
	log.Info("info")
	log.Log(ctx, slog.LevelInfo+2, "info+2")
	log.Warn("warn")
	log.Error("error")
	log.Log(ctx, slog.LevelError+4, "error+4")
	mustFlush(t, l)

	recs := decodeRecords(t, sender.requests()[0].Body)
	want := []string{"DEBUG", "DEBUG", "INFO", "INFO", "WARN", "ERROR", "ERROR"}
	if len(recs) != len(want) {
		t.Fatalf("records = %d, want %d", len(recs), len(want))
	}
	for i, w := range want {
		if recs[i]["severityText"] != w {
			t.Errorf("%v → %v, want %s", recs[i]["body"], recs[i]["severityText"], w)
		}
	}
}

type userValuer struct{ id int }

func (u userValuer) LogValue() slog.Value {
	return slog.GroupValue(slog.Int("id", u.id), slog.String("role", "admin"))
}

func TestSlogHandlerAttrsAndGroups(t *testing.T) {
	sender := newFakeLogSender()
	clock := &fakeClock{now: time.Unix(1, 0)}
	l, _ := newTestLogger(t, sender, func(c *LoggerConfig) { c.Clock = clock.Now })

	log := slog.New(l.Handler()).
		With("app", "checkout").
		WithGroup("http").
		With(slog.String("method", "POST")).
		WithGroup("empty-unless-used")

	log.Info("paid",
		slog.Int("status", 201),
		slog.Group("", slog.String("inlined", "yes")),
		slog.Group("nothing"),
		slog.Any("user", userValuer{id: 7}),
		slog.Duration("took", 1500*time.Millisecond),
		slog.Bool("ok", true),
	)
	mustFlush(t, l)

	rec := decodeRecords(t, sender.requests()[0].Body)[0]
	if got := attrJSON(t, rec, "app"); got != `{"stringValue":"checkout"}` {
		t.Errorf("app = %s", got)
	}

	// Decode http into a plain tree to compare structurally.
	httpAttr := recordAttrs(rec)["http"]
	got := plainAnyValue(httpAttr)
	want := map[string]any{
		"method": "POST",
		"empty-unless-used": map[string]any{
			"status":  "201",
			"inlined": "yes",
			"user":    map[string]any{"id": "7", "role": "admin"},
			"took":    "1.5s",
			"ok":      true,
		},
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("http group = %#v\nwant %#v", got, want)
	}
	if rec["timeUnixNano"] == "1000000000" {
		t.Errorf("slog record time should be used, not the logger clock")
	}
}

// plainAnyValue turns a decoded OTLP AnyValue into plain Go values (intValue
// stays a string, as on the wire).
func plainAnyValue(v any) any {
	m, ok := v.(map[string]any)
	if !ok {
		return v
	}
	for _, k := range []string{"stringValue", "boolValue", "intValue", "doubleValue"} {
		if x, ok := m[k]; ok {
			return x
		}
	}
	if kv, ok := m["kvlistValue"].(map[string]any); ok {
		out := map[string]any{}
		for _, e := range kv["values"].([]any) {
			entry := e.(map[string]any)
			out[entry["key"].(string)] = plainAnyValue(entry["value"])
		}
		return out
	}
	if arr, ok := m["arrayValue"].(map[string]any); ok {
		var out []any
		for _, e := range arr["values"].([]any) {
			out = append(out, plainAnyValue(e))
		}
		return out
	}
	return nil
}

func TestSlogHandlerEmptyGroupOmitted(t *testing.T) {
	sender := newFakeLogSender()
	l, _ := newTestLogger(t, sender)
	slog.New(l.Handler()).WithGroup("g").Info("no attrs")
	mustFlush(t, l)
	rec := decodeRecords(t, sender.requests()[0].Body)[0]
	if _, ok := rec["attributes"]; ok {
		t.Errorf("an unused group should be omitted: %v", rec["attributes"])
	}
}

func TestSlogHandlerTraceCorrelation(t *testing.T) {
	sender := newFakeLogSender()
	l, _ := newTestLogger(t, sender)
	slog.New(l.Handler()).Info("traced",
		"trace_id", "5B8EFFF798038103D269B633813FC60C",
		"span_id", "eee19b7ec3c1b174")
	mustFlush(t, l)
	rec := decodeRecords(t, sender.requests()[0].Body)[0]
	if rec["traceId"] != "5b8efff798038103d269b633813fc60c" || rec["spanId"] != "eee19b7ec3c1b174" {
		t.Errorf("traceId/spanId = %v/%v", rec["traceId"], rec["spanId"])
	}
}

func TestSlogHandlerExceptionFields(t *testing.T) {
	sender := newFakeLogSender()
	l, _ := newTestLogger(t, sender)
	pathErr := &fs.PathError{Op: "open", Path: "/etc/app.conf", Err: errors.New("denied")}
	slog.New(l.Handler()).Error("config failed", "err", pathErr)
	mustFlush(t, l)

	rec := decodeRecords(t, sender.requests()[0].Body)[0]
	if got := attrJSON(t, rec, "exception.type"); got != `{"stringValue":"*fs.PathError"}` {
		t.Errorf("exception.type = %s", got)
	}
	if got := attrJSON(t, rec, "exception.message"); got != `{"stringValue":"open /etc/app.conf: denied"}` {
		t.Errorf("exception.message = %s", got)
	}
	if got := attrJSON(t, rec, "err"); got != `{"stringValue":"open /etc/app.conf: denied"}` {
		t.Errorf("err = %s", got)
	}
}

func TestSlogHandlerNoRecursion(t *testing.T) {
	sender := newFakeLogSender()
	var h slog.Handler
	sender.onSend = func(ctx context.Context) {
		// A logger inside the send path (an HTTP library, a RoundTripper)
		// writing to the same handler, with and without the request context.
		slog.New(h).InfoContext(ctx, "from the send path, with ctx")
		slog.New(h).Info("from the send path, no ctx")
	}
	l, _ := newTestLogger(t, sender)
	h = l.Handler()

	slog.New(h).Info("original")
	mustFlush(t, l)
	mustFlush(t, l)

	reqs := sender.requests()
	if len(reqs) != 1 {
		t.Fatalf("requests = %d, want 1 (records from the send path must be dropped)", len(reqs))
	}
	if recs := decodeRecords(t, reqs[0].Body); len(recs) != 1 {
		t.Errorf("records = %d, want 1", len(recs))
	}
}

func TestSlogHandlerDisabledLogger(t *testing.T) {
	clearLogsEnv(t)
	l, err := NewLogger(LoggerConfig{})
	if err != nil {
		t.Fatal(err)
	}
	h := l.Handler()
	if h.Enabled(context.Background(), slog.LevelError) {
		t.Error("handler of a disabled logger should be disabled")
	}
	slog.New(h).Error("ignored", "k", "v")
}
