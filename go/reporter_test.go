package callisto

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"testing"
)

const testDSN = "https://app.callistosignal.com/ingest/app-1?key=pub-key"

// newReportingClient returns a client with error reporting enabled against a
// fake sender, plus the API server handler.
func newReportingClient(t *testing.T, sender *fakeSender, handler http.HandlerFunc) *Client {
	t.Helper()
	t.Setenv("CALLISTO_ERROR_DSN", "")
	c, srv := newTestClient(t, handler)
	_ = srv
	rc, err := NewClient(Options{
		ClientID: "cid", APIKey: "secretkey", BaseURL: c.transport.cfg.baseURL,
		ErrorDSN: testDSN, ErrorSender: sender, Environment: "test",
	})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	t.Cleanup(rc.Close)
	return rc
}

func TestReporterCapturesTransportError(t *testing.T) {
	sender := &fakeSender{ch: make(chan struct{}, 4)}
	c := newReportingClient(t, sender, func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 404, `{"message":"not found"}`)
	})

	_, err := c.Balance.Get(context.Background(), BalanceParams{})
	// The original error must still propagate.
	var nf *NotFoundError
	if !errors.As(err, &nf) {
		t.Fatalf("call err = %v, want *NotFoundError", err)
	}

	waitForSends(t, sender, 1)
	dsn, payload := sender.last()
	if dsn != testDSN {
		t.Errorf("posted to %q, want %q", dsn, testDSN)
	}
	if payload["type"] != "NotFoundError" || payload["level"] != "error" {
		t.Errorf("payload type/level = %v/%v", payload["type"], payload["level"])
	}
	if payload["culprit"] != "GET /sms/balance" {
		t.Errorf("culprit = %v", payload["culprit"])
	}
	req, _ := payload["request"].(map[string]any)
	if req["method"] != "GET" || req["path"] != "/sms/balance" {
		t.Errorf("request = %v", payload["request"])
	}
	ctxMap, _ := payload["context"].(map[string]any)
	if ctxMap["status_code"] != 404 {
		t.Errorf("status_code = %v, want 404", ctxMap["status_code"])
	}
	sdk, _ := ctxMap["sdk"].(map[string]any)
	if sdk["language"] != "go" || sdk["name"] != sdkName {
		t.Errorf("sdk = %v", sdk)
	}
}

func TestReporterNoCredentialOrRequestBodyLeak(t *testing.T) {
	sender := &fakeSender{ch: make(chan struct{}, 4)}
	c := newReportingClient(t, sender, func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 422, `{"message":"bad request"}`)
	})

	// This request body carries PII (phone + message) and credentials are on the
	// wire as Basic auth — none of it may appear in the reported payload.
	_, _ = c.SMS.Send(context.Background(), SendSMSParams{
		Sender: "Acme", To: []string{"+2250799999999"}, Message: "secret-message-text",
	})

	waitForSends(t, sender, 1)
	_, payload := sender.last()
	raw, err := json.Marshal(payload)
	if err != nil {
		t.Fatal(err)
	}
	blob := string(raw)
	for _, forbidden := range []string{
		"secretkey",           // API key
		"cid",                 // client ID
		"Authorization",       // auth header
		"+2250799999999",      // recipient PII
		"secret-message-text", // message content
	} {
		if strings.Contains(blob, forbidden) {
			t.Errorf("payload leaked %q: %s", forbidden, blob)
		}
	}
	// Sanity: the payload was actually built.
	if !strings.Contains(blob, "ValidationError") {
		t.Errorf("expected ValidationError type in payload: %s", blob)
	}
}

func TestReporterDisabledWithoutDSN(t *testing.T) {
	t.Setenv("CALLISTO_ERROR_DSN", "")
	sender := &fakeSender{}
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 500, `{"message":"x"}`)
	})
	// Re-point the transport's reporter? Instead build a fresh client with the
	// sender but NO DSN, confirming it never sends.
	noDSN, err := NewClient(Options{
		ClientID: "cid", APIKey: "secretkey", BaseURL: c.transport.cfg.baseURL,
		ErrorSender: sender, // provided, but no DSN → disabled
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(noDSN.Close)
	if noDSN.reporter.Enabled() {
		t.Fatal("reporter should be disabled without a DSN")
	}

	_, _ = noDSN.Balance.Get(context.Background(), BalanceParams{})
	noDSN.reporter.Flush()
	if sender.count() != 0 {
		t.Errorf("sender called %d times, want 0", sender.count())
	}
}

func TestReporterSwallowsSenderFailure(t *testing.T) {
	sender := &fakeSender{ch: make(chan struct{}, 4), err: errors.New("network down")}
	c := newReportingClient(t, sender, func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, `{"credit":1,"currency":"X"}`)
	})

	// CaptureException must never panic even when the sender fails.
	c.CaptureException(errors.New("boom"), WithLevel("warning"))
	waitForSends(t, sender, 1)
	_, payload := sender.last()
	if payload["level"] != "warning" || payload["message"] != "boom" {
		t.Errorf("payload = %v", payload)
	}
}

func TestCaptureMessage(t *testing.T) {
	sender := &fakeSender{ch: make(chan struct{}, 4)}
	c := newReportingClient(t, sender, func(w http.ResponseWriter, r *http.Request) {})

	c.CaptureMessage("hello", "info")
	waitForSends(t, sender, 1)
	_, payload := sender.last()
	if payload["message"] != "hello" || payload["level"] != "info" {
		t.Errorf("payload = %v", payload)
	}
}

func TestCaptureExceptionConstrainsLevel(t *testing.T) {
	sender := &fakeSender{ch: make(chan struct{}, 4)}
	c := newReportingClient(t, sender, func(w http.ResponseWriter, r *http.Request) {})

	c.CaptureException(errors.New("x"), WithLevel("bogus"))
	waitForSends(t, sender, 1)
	_, payload := sender.last()
	if payload["level"] != "error" {
		t.Errorf("level = %v, want error (bogus constrained)", payload["level"])
	}
}

func TestSetUserAttachedToEvents(t *testing.T) {
	sender := &fakeSender{ch: make(chan struct{}, 4)}
	c := newReportingClient(t, sender, func(w http.ResponseWriter, r *http.Request) {})

	c.SetUser(map[string]any{"id": "u1"})
	c.CaptureMessage("m", "info")
	waitForSends(t, sender, 1)
	_, payload := sender.last()
	user, _ := payload["user"].(map[string]any)
	if user["id"] != "u1" {
		t.Errorf("user = %v", payload["user"])
	}
}
