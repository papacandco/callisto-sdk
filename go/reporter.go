package callisto

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"reflect"
	"runtime"
	"strings"
	"time"
)

const (
	sdkName     = "callisto-sdk"
	sdkLanguage = "go"
	sdkVersion  = "0.1.0"
)

// validLevels are the only error levels accepted by the ingest contract.
var validLevels = map[string]bool{
	"fatal":   true,
	"error":   true,
	"warning": true,
	"info":    true,
}

// forbiddenKeys must never appear in a reported payload (PII / secrets rule).
var forbiddenKeys = map[string]bool{
	"client_id":     true,
	"api_key":       true,
	"authorization": true,
	"body_sent":     true,
	"request_body":  true,
}

// reporterTimeout is the per-send HTTP timeout for the default sender.
const reporterTimeout = 3 * time.Second

// ErrorSender posts a JSON error payload to the ingest DSN. Implementations
// must POST directly to dsn and return a non-nil error on any failure or any
// non-202 response. Injectable via Options.ErrorSender (mainly for testing).
type ErrorSender interface {
	Send(dsn string, payload map[string]any) error
}

// httpSender is the default ErrorSender. It POSTs via its OWN *http.Client so it
// never inherits the main transport's Basic-auth credentials and never recurses.
type httpSender struct {
	client *http.Client
}

func newHTTPSender() *httpSender {
	return &httpSender{client: &http.Client{Timeout: reporterTimeout}}
}

func (s *httpSender) Send(dsn string, payload map[string]any) error {
	buf, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), reporterTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, dsn, bytes.NewReader(buf))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := s.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusAccepted {
		return errors.New("callisto: ingest returned non-202")
	}
	return nil
}

// ErrorReporter is an opt-in, Sentry-style background error reporter. It posts
// captured errors to the Callisto ingest DSN. Delivery is background and
// best-effort: the caller's error path is never delayed and every failure of the
// reporter itself is swallowed. When the DSN is missing/invalid the reporter is
// a cheap no-op.
type ErrorReporter struct {
	dsn         string
	enabled     bool
	environment string
	sender      ErrorSender

	user map[string]any

	queue  chan map[string]any
	done   chan struct{}
	closed bool
}

// newErrorReporter constructs a reporter. When dsn is empty or not a well-formed
// http(s) URL the reporter is disabled and every method is a no-op.
func newErrorReporter(dsn, environment string, sender ErrorSender) *ErrorReporter {
	r := &ErrorReporter{
		dsn:         dsn,
		enabled:     isValidDSN(dsn),
		environment: environment,
		sender:      sender,
	}
	if r.sender == nil {
		r.sender = newHTTPSender()
	}
	if r.enabled {
		r.queue = make(chan map[string]any, 100)
		r.done = make(chan struct{})
		go r.run()
	}
	return r
}

// Enabled reports whether the reporter will actually send events.
func (r *ErrorReporter) Enabled() bool { return r.enabled }

// SetUser sets (or, with nil, clears) the user context attached to subsequent
// events.
func (r *ErrorReporter) SetUser(user map[string]any) {
	if len(user) == 0 {
		r.user = nil
		return
	}
	cp := make(map[string]any, len(user))
	for k, v := range user {
		cp[k] = v
	}
	r.user = cp
}

// CaptureException builds a payload from err and enqueues a background send.
// method and path are non-empty only for transport-originated errors. It never
// blocks meaningfully and never panics.
func (r *ErrorReporter) CaptureException(err error, level string, extra map[string]any, method, path string) {
	if !r.enabled || err == nil {
		return
	}
	defer func() { _ = recover() }()
	payload := r.buildExceptionPayload(err, level, extra, method, path)
	r.enqueue(payload)
}

// CaptureMessage captures a plain message at the given level.
func (r *ErrorReporter) CaptureMessage(message, level string, extra map[string]any) {
	if !r.enabled {
		return
	}
	defer func() { _ = recover() }()
	payload := map[string]any{
		"message": message,
		"type":    "Message",
		"level":   constrainLevel(level),
		"context": r.baseContext(extra),
	}
	if r.user != nil {
		payload["user"] = r.user
	}
	r.enqueue(payload)
}

// Flush waits, bounded, for the queue to drain.
func (r *ErrorReporter) Flush() {
	if !r.enabled || r.closed {
		return
	}
	deadline := time.After(2 * time.Second)
	for {
		if len(r.queue) == 0 {
			return
		}
		select {
		case <-deadline:
			return
		case <-time.After(5 * time.Millisecond):
		}
	}
}

// Close flushes pending events and stops the worker goroutine.
func (r *ErrorReporter) Close() {
	if !r.enabled || r.closed {
		return
	}
	r.Flush()
	r.closed = true
	close(r.queue)
	select {
	case <-r.done:
	case <-time.After(2 * time.Second):
	}
}

func (r *ErrorReporter) enqueue(payload map[string]any) {
	if r.closed {
		return
	}
	select {
	case r.queue <- payload:
	default:
		// Queue full: drop the event rather than block the caller.
	}
}

func (r *ErrorReporter) run() {
	defer close(r.done)
	for payload := range r.queue {
		r.send(payload)
	}
}

func (r *ErrorReporter) send(payload map[string]any) {
	defer func() { _ = recover() }()
	// Swallow ALL failures; never re-capture the reporter's own errors.
	_ = r.sender.Send(r.dsn, payload)
}

func (r *ErrorReporter) baseContext(extra map[string]any) map[string]any {
	context := map[string]any{
		"sdk": map[string]any{
			"name":     sdkName,
			"version":  sdkVersion,
			"language": sdkLanguage,
		},
	}
	if r.environment != "" {
		context["environment"] = r.environment
	}
	for k, v := range extra {
		if forbiddenKeys[strings.ToLower(strings.TrimSpace(k))] {
			continue
		}
		context[k] = v
	}
	return context
}

func (r *ErrorReporter) buildExceptionPayload(err error, level string, extra map[string]any, method, path string) map[string]any {
	message := err.Error()
	typeName := errorTypeName(err)
	if message == "" {
		message = typeName
	}

	context := r.baseContext(extra)

	// For *CallistoError-derived errors, attach status_code / body. errors.As
	// reaches the embedded *CallistoError via each concrete type's Unwrap.
	var base *CallistoError
	if errors.As(err, &base) {
		context["status_code"] = base.StatusCode
		if base.Body != nil {
			context["body"] = base.Body
		}
	}
	var rle *RateLimitError
	if errors.As(err, &rle) {
		context["retry_after"] = rle.RetryAfter
	}

	payload := map[string]any{
		"message": message,
		"type":    typeName,
		"level":   constrainLevel(level),
		"context": context,
	}

	// Source context only for genuine application exceptions: a transport call
	// site can embed the outgoing request body as literal arguments, which would
	// violate the hard no-request-body guarantee. Transport errors already carry
	// method/path as their culprit.
	withSource := method == "" || path == ""
	if stack := buildStacktrace(withSource); len(stack) > 0 {
		payload["stacktrace"] = stack
	}

	// Transport-originated errors carry method + path.
	if method != "" && path != "" {
		payload["culprit"] = method + " " + path
		payload["request"] = map[string]any{"method": method, "path": path}
	}

	if r.user != nil {
		payload["user"] = r.user
	}

	return payload
}

// errorTypeName returns the Go type name of err (without the package path),
// matching the concrete typed-error names (e.g. AuthenticationError).
func errorTypeName(err error) string {
	// Prefer the concrete SDK type name where possible.
	switch err.(type) {
	case *AuthenticationError:
		return "AuthenticationError"
	case *ValidationError:
		return "ValidationError"
	case *NotFoundError:
		return "NotFoundError"
	case *RateLimitError:
		return "RateLimitError"
	case *APIError:
		return "APIError"
	case *NetworkError:
		return "NetworkError"
	case *CallistoError:
		return "CallistoError"
	}
	t := reflectTypeName(err)
	if t != "" {
		return t
	}
	return "error"
}

// reflectTypeName returns the unqualified Go type name of err's dynamic type.
func reflectTypeName(err error) string {
	t := reflect.TypeOf(err)
	if t == nil {
		return ""
	}
	for t.Kind() == reflect.Ptr {
		t = t.Elem()
	}
	return t.Name()
}

func constrainLevel(level string) string {
	if validLevels[level] {
		return level
	}
	return "error"
}

// sourceContextLines is the number of source lines captured on each side of a
// frame's error line; maxSourceBytes caps the size of a file we will read.
const (
	sourceContextLines = 5
	maxSourceBytes     = 2_000_000
)

// buildStacktrace returns a best-effort innermost-first stack trace of the
// current goroutine. Reporter internals are skipped. When withSource is true,
// each frame whose source file is readable also carries a pre_context /
// context_line / post_context window around its error line.
func buildStacktrace(withSource bool) []map[string]any {
	var pcs [32]uintptr
	// Skip runtime.Callers, buildStacktrace, buildExceptionPayload.
	n := runtime.Callers(3, pcs[:])
	if n == 0 {
		return nil
	}
	frames := runtime.CallersFrames(pcs[:n])
	out := make([]map[string]any, 0, n)
	for {
		frame, more := frames.Next()
		if frame.Function != "" {
			f := map[string]any{
				"function": frame.Function,
				"file":     frame.File,
				"line":     frame.Line,
			}
			if withSource {
				for k, v := range sourceContext(frame.File, frame.Line) {
					f[k] = v
				}
			}
			out = append(out, f)
		}
		if !more {
			break
		}
	}
	return out
}

// sourceContext reads up to sourceContextLines lines on each side of line in
// file, returning pre_context / context_line / post_context so the dashboard can
// render the failing code with the error line in focus. Go stack frames carry
// the compile-time absolute path, so this works wherever the source is present
// at runtime (dev / same-host deploys). Best-effort: any unreadable / oversized
// / out-of-range file yields a nil map and the frame renders without a window.
func sourceContext(file string, line int) map[string]any {
	if file == "" || line < 1 {
		return nil
	}
	info, err := os.Stat(file)
	if err != nil || info.IsDir() || info.Size() > maxSourceBytes {
		return nil
	}
	data, err := os.ReadFile(file)
	if err != nil {
		return nil
	}
	lines := strings.Split(string(data), "\n")
	// Drop the trailing empty element left by a final newline so line counts match.
	if len(lines) > 0 && lines[len(lines)-1] == "" {
		lines = lines[:len(lines)-1]
	}
	for i := range lines {
		lines[i] = strings.TrimSuffix(lines[i], "\r") // tolerate CRLF
	}
	if line > len(lines) {
		return nil
	}
	idx := line - 1
	start := max(idx-sourceContextLines, 0)
	end := min(idx+1+sourceContextLines, len(lines))
	return map[string]any{
		"pre_context":  append([]string{}, lines[start:idx]...),
		"context_line": lines[idx],
		"post_context": append([]string{}, lines[idx+1:end]...),
	}
}

func isValidDSN(dsn string) bool {
	return strings.HasPrefix(dsn, "http://") || strings.HasPrefix(dsn, "https://")
}
