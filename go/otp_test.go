package callisto

import (
	"context"
	"errors"
	"net/http"
	"testing"
)

func TestOTPSend(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/otp/send" {
			t.Errorf("%s %s", r.Method, r.URL.Path)
		}
		body := reqBody(t, r)
		if body["to"] != "+225070" || body["message"] != "code {code}" {
			t.Errorf("body = %v", body)
		}
		if body["type"] != "digit" {
			t.Errorf("type = %v, want digit", body["type"])
		}
		writeJSON(w, 200, `{"id":"otp1","provider":"sms","recipient":"+225070","expires_at":"x","expires_in":300}`)
	})

	res, err := c.OTP.Send(context.Background(), SendOTPParams{
		To: "+225070", Message: "code {code}", Type: OtpTypeDigit,
	})
	if err != nil {
		t.Fatalf("Send: %v", err)
	}
	if res.ID != "otp1" || res.ExpiresIn != 300 {
		t.Errorf("result = %+v", res)
	}
}

func TestOTPSendWhatsAppRequiresInstanceCode(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		t.Fatalf("no request should be made; got %s %s", r.Method, r.URL.Path)
	})

	_, err := c.OTP.Send(context.Background(), SendOTPParams{
		To: "+225070", Message: "m", Provider: OtpProviderWhatsApp,
	})
	var verr *ValidationError
	if !errors.As(err, &verr) {
		t.Fatalf("err = %v, want *ValidationError", err)
	}
}

func TestOTPSendWhatsAppWithInstanceCode(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		body := reqBody(t, r)
		if body["instanceCode"] != "inst-1" {
			t.Errorf("instanceCode = %v, want inst-1", body["instanceCode"])
		}
		if body["provider"] != "whatsapp" {
			t.Errorf("provider = %v", body["provider"])
		}
		writeJSON(w, 200, `{"id":"o","provider":"whatsapp","recipient":"x","expires_at":"x","expires_in":60}`)
	})

	if _, err := c.OTP.Send(context.Background(), SendOTPParams{
		To: "+225070", Message: "m", Provider: OtpProviderWhatsApp, InstanceCode: "inst-1",
	}); err != nil {
		t.Fatalf("Send: %v", err)
	}
}

func TestOTPVerify(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/otp/verify" {
			t.Errorf("path = %s", r.URL.Path)
		}
		body := reqBody(t, r)
		if body["otp_id"] != "o1" || body["code"] != "1234" {
			t.Errorf("body = %v", body)
		}
		writeJSON(w, 200, `{"id":"o1","status":"verified","verified":true}`)
	})

	res, err := c.OTP.Verify(context.Background(), "o1", "1234")
	if err != nil {
		t.Fatalf("Verify: %v", err)
	}
	if !res.Verified {
		t.Errorf("verified = false")
	}
}

func TestOTPListUsesLimitQueryKey(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/otps" {
			t.Errorf("path = %s", r.URL.Path)
		}
		if got := r.URL.Query().Get("limit"); got != "50" {
			t.Errorf("limit = %q, want 50", got)
		}
		writeJSON(w, 200, `{"items":[],"total":0,"per_page":50,"current_page":1,"next":null,"previous":null,"total_pages":0}`)
	})

	limit := 50
	if _, err := c.OTP.List(context.Background(), ListOTPParams{Limit: &limit}); err != nil {
		t.Fatalf("List: %v", err)
	}
}
