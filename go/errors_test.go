package callisto

import (
	"errors"
	"testing"
)

func TestErrorFromStatus(t *testing.T) {
	if err := errorFromStatus(401, "no", nil, 0); err.Error() != "no" {
		t.Errorf("Error() = %q", err.Error())
	}

	var auth *AuthenticationError
	if !errors.As(errorFromStatus(401, "x", nil, 0), &auth) {
		t.Error("401 not AuthenticationError")
	}
	var nf *NotFoundError
	if !errors.As(errorFromStatus(404, "x", nil, 0), &nf) {
		t.Error("404 not NotFoundError")
	}
	var api *APIError
	if !errors.As(errorFromStatus(503, "x", nil, 0), &api) {
		t.Error("503 not APIError")
	}

	rl := errorFromStatus(429, "x", nil, 7)
	var rle *RateLimitError
	if !errors.As(rl, &rle) || rle.RetryAfter != 7 {
		t.Errorf("429 mapping wrong: %v", rl)
	}
}

func TestCallistoErrorFieldsPromoted(t *testing.T) {
	err := errorFromStatus(404, "missing", map[string]any{"k": "v"}, 0)
	var nf *NotFoundError
	if !errors.As(err, &nf) {
		t.Fatal("not NotFoundError")
	}
	// Fields are promoted from the embedded *CallistoError.
	if nf.StatusCode != 404 || nf.Message != "missing" {
		t.Errorf("promoted fields wrong: %+v", nf)
	}
}
