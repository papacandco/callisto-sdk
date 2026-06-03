package callisto

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

// newTestClient spins up an httptest server with the given handler and returns a
// Client pointed at it. Credentials are explicit so environment variables never
// interfere. The server and client are cleaned up automatically.
func newTestClient(t *testing.T, handler http.HandlerFunc) (*Client, *httptest.Server) {
	t.Helper()
	srv := httptest.NewServer(handler)
	t.Cleanup(srv.Close)
	c, err := NewClient(Options{ClientID: "cid", APIKey: "secretkey", BaseURL: srv.URL})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	t.Cleanup(c.Close)
	return c, srv
}

// reqBody decodes a request's JSON body into a map.
func reqBody(t *testing.T, r *http.Request) map[string]any {
	t.Helper()
	b, _ := io.ReadAll(r.Body)
	if len(b) == 0 {
		return nil
	}
	var m map[string]any
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatalf("decode request body: %v (raw=%s)", err, b)
	}
	return m
}

// writeJSON writes a JSON response with the given status.
func writeJSON(w http.ResponseWriter, status int, body string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = io.WriteString(w, body)
}

// assertBasicAuth fails the test unless the request carries the expected Basic
// auth credentials.
func assertBasicAuth(t *testing.T, r *http.Request) {
	t.Helper()
	user, pass, ok := r.BasicAuth()
	if !ok || user != "cid" || pass != "secretkey" {
		t.Fatalf("basic auth = (%q,%q,%v), want (cid,secretkey,true)", user, pass, ok)
	}
}

// fakeSender is an ErrorSender that records every send for assertions. When ch
// is non-nil it signals once per send so tests can wait deterministically.
type fakeSender struct {
	mu       sync.Mutex
	dsns     []string
	payloads []map[string]any
	err      error
	ch       chan struct{}
}

func (f *fakeSender) Send(dsn string, payload map[string]any) error {
	f.mu.Lock()
	f.dsns = append(f.dsns, dsn)
	f.payloads = append(f.payloads, payload)
	err := f.err
	f.mu.Unlock()
	if f.ch != nil {
		f.ch <- struct{}{}
	}
	return err
}

func (f *fakeSender) count() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.payloads)
}

func (f *fakeSender) last() (string, map[string]any) {
	f.mu.Lock()
	defer f.mu.Unlock()
	n := len(f.payloads)
	if n == 0 {
		return "", nil
	}
	return f.dsns[n-1], f.payloads[n-1]
}

// waitForSends blocks until n sends have been signalled on f.ch, or fails after
// a short timeout.
func waitForSends(t *testing.T, f *fakeSender, n int) {
	t.Helper()
	deadline := time.After(2 * time.Second)
	for i := 0; i < n; i++ {
		select {
		case <-f.ch:
		case <-deadline:
			t.Fatalf("timed out waiting for send %d/%d (got %d)", i+1, n, f.count())
		}
	}
}
