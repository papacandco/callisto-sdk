package callisto

import (
	"context"
	"net/http"
	"reflect"
	"testing"
)

func TestSMSSend(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		assertBasicAuth(t, r)
		if r.Method != http.MethodPost || r.URL.Path != "/sms/send" {
			t.Errorf("%s %s, want POST /sms/send", r.Method, r.URL.Path)
		}
		body := reqBody(t, r)
		if body["sender"] != "Acme" || body["message"] != "Hi" {
			t.Errorf("body = %v", body)
		}
		to, ok := body["to"].([]any)
		if !ok || len(to) != 1 || to[0] != "+2250700000000" {
			t.Errorf("to = %v", body["to"])
		}
		writeJSON(w, 200, `{"total_amount":4,"available_credit":96,"status":"queued","recipient_count":1,"scheduled":false,"messages":[]}`)
	})

	res, err := c.SMS.Send(context.Background(), SendSMSParams{
		Sender: "Acme", To: []string{"+2250700000000"}, Message: "Hi",
	})
	if err != nil {
		t.Fatalf("Send: %v", err)
	}
	if res.RecipientCount != 1 || res.Status != "queued" {
		t.Errorf("result = %+v", res)
	}
}

func TestSMSList(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/sms/messages" {
			t.Errorf("path = %s", r.URL.Path)
		}
		q := r.URL.Query()
		if q.Get("page") != "2" {
			t.Errorf("page = %q, want 2", q.Get("page"))
		}
		// per_page is nil → must be dropped from the query.
		if _, ok := q["per_page"]; ok {
			t.Errorf("per_page should be absent, got %q", q.Get("per_page"))
		}
		writeJSON(w, 200, `{"items":[{"id":"m1","status":"sent"}],"total":1,"per_page":20,"current_page":2,"next":null,"previous":1,"total_pages":2}`)
	})

	page := 2
	out, err := c.SMS.List(context.Background(), ListSMSParams{Page: &page})
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(out.Items) != 1 || out.Items[0].ID != "m1" {
		t.Errorf("items = %+v", out.Items)
	}
	if out.CurrentPage != 2 || out.Next != nil {
		t.Errorf("page window = %+v", out)
	}
}

func TestSMSGetStatus(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/sms/abc123" {
			t.Errorf("path = %s, want /sms/abc123", r.URL.Path)
		}
		writeJSON(w, 200, `{"id":"abc123","status":"delivered"}`)
	})

	msg, err := c.SMS.GetStatus(context.Background(), "abc123")
	if err != nil {
		t.Fatalf("GetStatus: %v", err)
	}
	if msg.Status != "delivered" {
		t.Errorf("status = %s", msg.Status)
	}
}

func TestSMSSendMarshalsToAsArray(t *testing.T) {
	var got map[string]any
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		got = reqBody(t, r)
		writeJSON(w, 200, `{"total_amount":0,"available_credit":0,"status":"ok","recipient_count":2,"scheduled":false,"messages":[]}`)
	})
	if _, err := c.SMS.Send(context.Background(), SendSMSParams{
		Sender: "A", To: []string{"+1", "+2"}, Message: "m",
	}); err != nil {
		t.Fatalf("Send: %v", err)
	}
	want := []any{"+1", "+2"}
	if !reflect.DeepEqual(got["to"], want) {
		t.Errorf("to = %v, want %v", got["to"], want)
	}
}
