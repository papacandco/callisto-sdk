package callisto

import (
	"errors"
	"testing"
)

// TestNewErrorReporterCapturesWithoutCredentials proves the standalone reporter
// works with only a DSN — no ClientID/APIKey — which is its whole purpose.
func TestNewErrorReporterCapturesWithoutCredentials(t *testing.T) {
	sender := &fakeSender{ch: make(chan struct{}, 4)}
	r := NewErrorReporter(ReporterOptions{DSN: testDSN, Environment: "test", Sender: sender})
	t.Cleanup(r.Close)

	if !r.Enabled() {
		t.Fatal("reporter should be enabled with a valid DSN")
	}

	r.CaptureException(errors.New("boom"),
		WithLevel("warning"),
		WithContext(map[string]any{"feature": "checkout"}),
	)
	waitForSends(t, sender, 1)

	dsn, payload := sender.last()
	if dsn != testDSN {
		t.Errorf("posted to %q, want %q", dsn, testDSN)
	}
	if payload["level"] != "warning" || payload["message"] != "boom" {
		t.Errorf("payload level/message = %v/%v", payload["level"], payload["message"])
	}
	ctxMap, _ := payload["context"].(map[string]any)
	if ctxMap["feature"] != "checkout" {
		t.Errorf("context.feature = %v, want checkout", ctxMap["feature"])
	}
	sdk, _ := ctxMap["sdk"].(map[string]any)
	if sdk["language"] != "go" || sdk["name"] != sdkName {
		t.Errorf("sdk = %v", sdk)
	}
}

// TestNewErrorReporterDisabledWithoutDSN confirms a missing DSN yields a cheap
// no-op reporter rather than an error.
func TestNewErrorReporterDisabledWithoutDSN(t *testing.T) {
	sender := &fakeSender{}
	r := NewErrorReporter(ReporterOptions{DSN: "", Sender: sender})

	if r.Enabled() {
		t.Fatal("reporter should be disabled without a DSN")
	}

	r.CaptureException(errors.New("x"))
	r.CaptureMessage("y", "info", nil)
	r.Flush()

	if sender.count() != 0 {
		t.Errorf("sender called %d times, want 0", sender.count())
	}
}

// TestErrorReporterRecoverReportsAndRepanics verifies Recover reports an
// in-flight panic at level "fatal" and then re-panics, preserving crash
// semantics. The deferred ordering matters: r.Recover must run before the outer
// catcher, so it is deferred last (defers run LIFO).
func TestErrorReporterRecoverReportsAndRepanics(t *testing.T) {
	sender := &fakeSender{ch: make(chan struct{}, 4)}
	r := NewErrorReporter(ReporterOptions{DSN: testDSN, Sender: sender})
	t.Cleanup(r.Close)

	rec := func() (caught any) {
		defer func() { caught = recover() }() // runs second: catches the re-panic
		defer r.Recover()                     // runs first: reports + re-panics
		panic("kaboom")
	}()

	if rec != "kaboom" {
		t.Fatalf("Recover should re-panic with the original value, got %v", rec)
	}

	waitForSends(t, sender, 1)
	_, payload := sender.last()
	if payload["level"] != "fatal" {
		t.Errorf("level = %v, want fatal", payload["level"])
	}
	if payload["message"] != "kaboom" {
		t.Errorf("message = %v, want kaboom", payload["message"])
	}
}
