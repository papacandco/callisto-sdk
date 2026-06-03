package callisto

import (
	"context"
	"errors"
	"net/http"
	"testing"
)

func TestErrorStatusMapping(t *testing.T) {
	cases := []struct {
		status int
		check  func(error) bool
	}{
		{401, func(e error) bool { var x *AuthenticationError; return errors.As(e, &x) }},
		{400, func(e error) bool { var x *ValidationError; return errors.As(e, &x) }},
		{422, func(e error) bool { var x *ValidationError; return errors.As(e, &x) }},
		{404, func(e error) bool { var x *NotFoundError; return errors.As(e, &x) }},
		{500, func(e error) bool { var x *APIError; return errors.As(e, &x) }},
	}

	for _, tc := range cases {
		tc := tc
		t.Run(http.StatusText(tc.status), func(t *testing.T) {
			c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
				writeJSON(w, tc.status, `{"message":"boom"}`)
			})
			_, err := c.Balance.Get(context.Background(), BalanceParams{})
			if err == nil || !tc.check(err) {
				t.Fatalf("status %d → err %v (wrong type)", tc.status, err)
			}
			var ce *CallistoError
			if !errors.As(err, &ce) {
				t.Fatalf("err not a CallistoError: %v", err)
			}
			if ce.StatusCode != tc.status || ce.Message != "boom" {
				t.Errorf("base = %+v", ce)
			}
		})
	}
}

func TestRateLimitErrorRetryAfter(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Retry-After", "42")
		writeJSON(w, 429, `{"message":"slow down"}`)
	})

	_, err := c.Balance.Get(context.Background(), BalanceParams{})
	var rle *RateLimitError
	if !errors.As(err, &rle) {
		t.Fatalf("err = %v, want *RateLimitError", err)
	}
	if rle.RetryAfter != 42 {
		t.Errorf("RetryAfter = %d, want 42", rle.RetryAfter)
	}
}

func TestNetworkError(t *testing.T) {
	c, srv := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {})
	srv.Close() // force a transport-level failure

	_, err := c.Balance.Get(context.Background(), BalanceParams{})
	var nerr *NetworkError
	if !errors.As(err, &nerr) {
		t.Fatalf("err = %v, want *NetworkError", err)
	}
	if nerr.StatusCode != 0 {
		t.Errorf("StatusCode = %d, want 0", nerr.StatusCode)
	}
}

func TestMissingCredentialsFailFast(t *testing.T) {
	t.Setenv("CALLISTO_CLIENT_ID", "")
	t.Setenv("CALLISTO_API_KEY", "")
	if _, err := NewClient(Options{APIKey: "k"}); err == nil {
		t.Error("expected error when ClientID is unresolved")
	}
	if _, err := NewClient(Options{ClientID: "c"}); err == nil {
		t.Error("expected error when APIKey is unresolved")
	}
}

func TestBaseURLTrailingSlashTrimmed(t *testing.T) {
	c, err := NewClient(Options{ClientID: "c", APIKey: "k", BaseURL: "https://example.com/v1/"})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(c.Close)
	if c.transport.cfg.baseURL != "https://example.com/v1" {
		t.Errorf("baseURL = %q", c.transport.cfg.baseURL)
	}
}
