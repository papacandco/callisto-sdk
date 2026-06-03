package callisto

import (
	"context"
	"net/http"
	"testing"
)

func TestBalanceGet(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		assertBasicAuth(t, r)
		if r.Method != http.MethodGet {
			t.Errorf("method = %s, want GET", r.Method)
		}
		if r.URL.Path != "/sms/balance" {
			t.Errorf("path = %s, want /sms/balance", r.URL.Path)
		}
		if got := r.URL.Query().Get("format"); got != "full" {
			t.Errorf("format = %q, want full", got)
		}
		writeJSON(w, 200, `{"credit":12.5,"currency":"XOF","sms_price_local":4.0}`)
	})

	bal, err := c.Balance.Get(context.Background(), BalanceParams{})
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	if bal.Credit != 12.5 || bal.Currency != "XOF" {
		t.Errorf("balance = %+v", bal)
	}
	if bal.SMSPriceLocal == nil || *bal.SMSPriceLocal != 4.0 {
		t.Errorf("sms_price_local = %v", bal.SMSPriceLocal)
	}
}

func TestBalanceGetWithCurrency(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		if q.Get("format") != "summary" || q.Get("currency") != "USD" {
			t.Errorf("query = %v", q)
		}
		writeJSON(w, 200, `{"credit":1,"currency":"USD"}`)
	})

	if _, err := c.Balance.Get(context.Background(), BalanceParams{Format: "summary", Currency: "USD"}); err != nil {
		t.Fatalf("Get: %v", err)
	}
}
