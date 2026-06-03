package callisto

import (
	"context"
	"errors"
	"net/http"
	"testing"
)

func TestNotifySend(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/notify/send" {
			t.Errorf("%s %s", r.Method, r.URL.Path)
		}
		body := reqBody(t, r)
		if body["topic"] != "orders" {
			t.Errorf("topic = %v", body["topic"])
		}
		email, ok := body["email"].([]any)
		if !ok || len(email) != 1 {
			t.Errorf("email = %v", body["email"])
		}
		// Absent blocks must not be sent.
		if _, ok := body["sms"]; ok {
			t.Errorf("sms block should be absent")
		}
		writeJSON(w, 200, `{"status":"queued","topic":"orders","queued_events":1,"topic_messages":[]}`)
	})

	res, err := c.Notify.Send(context.Background(), NotifyParams{
		Topic: "orders",
		Email: []any{map[string]any{"to": "a@b.com", "subject": "Hi"}},
	})
	if err != nil {
		t.Fatalf("Send: %v", err)
	}
	if res.Status != "queued" {
		t.Errorf("status = %s", res.Status)
	}
}

func TestNotifySendRequiresAtLeastOneBlock(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		t.Fatalf("no request should be made; got %s %s", r.Method, r.URL.Path)
	})

	_, err := c.Notify.Send(context.Background(), NotifyParams{Topic: "orders"})
	var verr *ValidationError
	if !errors.As(err, &verr) {
		t.Fatalf("err = %v, want *ValidationError", err)
	}
}
