package callisto

import (
	"context"
	"net/http"
	"testing"
)

func TestWhatsAppCreateInstance(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/whatsapp/instances" {
			t.Errorf("%s %s", r.Method, r.URL.Path)
		}
		body := reqBody(t, r)
		if body["name"] != "Sales" || body["webhook_url"] != "https://h" {
			t.Errorf("body = %v", body)
		}
		writeJSON(w, 200, `{"id":"i1","code":"abc","name":"Sales"}`)
	})

	inst, err := c.WhatsApp.CreateInstance(context.Background(), CreateInstanceParams{
		Name: "Sales", WebhookURL: "https://h",
	})
	if err != nil {
		t.Fatalf("CreateInstance: %v", err)
	}
	if inst.Code != "abc" {
		t.Errorf("code = %s", inst.Code)
	}
}

func TestWhatsAppListInstancesDefaultsPageToOne(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if got := r.URL.Query().Get("page"); got != "1" {
			t.Errorf("page = %q, want 1", got)
		}
		writeJSON(w, 200, `{"items":[],"total":0,"per_page":20,"current_page":1,"next":null,"previous":null,"total_pages":0}`)
	})

	if _, err := c.WhatsApp.ListInstances(context.Background(), 0); err != nil {
		t.Fatalf("ListInstances: %v", err)
	}
}

func TestWhatsAppGetQRReturnsRaw(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/whatsapp/abc/qr" {
			t.Errorf("path = %s", r.URL.Path)
		}
		writeJSON(w, 200, `{"qr":"data:image/png;base64,xxx"}`)
	})

	raw, err := c.WhatsApp.GetQR(context.Background(), "abc")
	if err != nil {
		t.Fatalf("GetQR: %v", err)
	}
	if string(raw) != `{"qr":"data:image/png;base64,xxx"}` {
		t.Errorf("raw = %s", raw)
	}
}

func TestWhatsAppSendText(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/whatsapp/abc/send/text" {
			t.Errorf("path = %s", r.URL.Path)
		}
		body := reqBody(t, r)
		if body["to"] != "+225" || body["message"] != "hi" {
			t.Errorf("body = %v", body)
		}
		writeJSON(w, 200, `{"id":"w1","instance_id":"i","recipient":"+225","message_type":"text","status":"queued","scheduled":false}`)
	})

	res, err := c.WhatsApp.SendText(context.Background(), "abc", SendTextParams{To: "+225", Message: "hi"})
	if err != nil {
		t.Fatalf("SendText: %v", err)
	}
	if res.MessageType != "text" {
		t.Errorf("type = %s", res.MessageType)
	}
}

func TestWhatsAppSendMedia(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/whatsapp/abc/send/media" {
			t.Errorf("path = %s", r.URL.Path)
		}
		body := reqBody(t, r)
		if body["type"] != "image" || body["media_url"] != "https://m.png" {
			t.Errorf("body = %v", body)
		}
		writeJSON(w, 200, `{"id":"w","instance_id":"i","recipient":"x","message_type":"image","status":"q","scheduled":false}`)
	})

	if _, err := c.WhatsApp.SendMedia(context.Background(), "abc", SendMediaParams{
		To: "+225", Type: WhatsAppMediaTypeImage, MediaURL: "https://m.png",
	}); err != nil {
		t.Fatalf("SendMedia: %v", err)
	}
}

func TestWhatsAppSendLocationAlwaysSendsCoordinates(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/whatsapp/abc/send/location" {
			t.Errorf("path = %s", r.URL.Path)
		}
		body := reqBody(t, r)
		// Zero is a valid coordinate and must NOT be omitted.
		if _, ok := body["latitude"]; !ok {
			t.Errorf("latitude missing from body %v", body)
		}
		if body["longitude"] != float64(0) {
			t.Errorf("longitude = %v, want 0", body["longitude"])
		}
		writeJSON(w, 200, `{"id":"w","instance_id":"i","recipient":"x","message_type":"location","status":"q","scheduled":false}`)
	})

	if _, err := c.WhatsApp.SendLocation(context.Background(), "abc", SendLocationParams{
		To: "+225", Latitude: 5.34, Longitude: 0,
	}); err != nil {
		t.Fatalf("SendLocation: %v", err)
	}
}

func TestWhatsAppGetMessage(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/whatsapp/messages/msg-9" {
			t.Errorf("path = %s", r.URL.Path)
		}
		writeJSON(w, 200, `{"id":"msg-9","status":"read"}`)
	})

	msg, err := c.WhatsApp.GetMessage(context.Background(), "msg-9")
	if err != nil {
		t.Fatalf("GetMessage: %v", err)
	}
	if msg.Status != "read" {
		t.Errorf("status = %s", msg.Status)
	}
}
