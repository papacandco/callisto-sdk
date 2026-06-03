package callisto

// CallistoError is the base error type for all SDK errors. Concrete error types
// embed *CallistoError, which promotes the Message/StatusCode/Body fields and
// the Error() method. Callers branch with errors.As(err, &target), e.g.
//
//	var rle *callisto.RateLimitError
//	if errors.As(err, &rle) { time.Sleep(time.Duration(rle.RetryAfter) * time.Second) }
type CallistoError struct {
	// Message is the human-readable error message (from the API response
	// "message" field, or a synthesized description for transport failures).
	Message string
	// StatusCode is the HTTP status code, or 0 for transport-level failures.
	StatusCode int
	// Body is the decoded API response body, when available.
	Body any
}

// Error implements the error interface.
func (e *CallistoError) Error() string { return e.Message }

// AuthenticationError is returned for HTTP 401 (invalid credentials).
type AuthenticationError struct{ *CallistoError }

// Unwrap exposes the embedded *CallistoError, so errors.As(err, &target) reaches
// both the concrete type and the base CallistoError.
func (e *AuthenticationError) Unwrap() error { return e.CallistoError }

// ValidationError is returned for HTTP 400/422, and for client-side validation
// failures raised before any request is made.
type ValidationError struct{ *CallistoError }

// Unwrap exposes the embedded *CallistoError.
func (e *ValidationError) Unwrap() error { return e.CallistoError }

// NotFoundError is returned for HTTP 404 (resource not found).
type NotFoundError struct{ *CallistoError }

// Unwrap exposes the embedded *CallistoError.
func (e *NotFoundError) Unwrap() error { return e.CallistoError }

// RateLimitError is returned for HTTP 429 (rate limited). RetryAfter carries the
// parsed Retry-After header value in seconds, when present.
type RateLimitError struct {
	*CallistoError
	RetryAfter int
}

// Unwrap exposes the embedded *CallistoError.
func (e *RateLimitError) Unwrap() error { return e.CallistoError }

// APIError is returned for any other non-2xx HTTP status.
type APIError struct{ *CallistoError }

// Unwrap exposes the embedded *CallistoError.
func (e *APIError) Unwrap() error { return e.CallistoError }

// NetworkError is returned for transport-level failures (connection error,
// timeout, DNS, etc.). Its StatusCode is 0.
type NetworkError struct{ *CallistoError }

// Unwrap exposes the embedded *CallistoError.
func (e *NetworkError) Unwrap() error { return e.CallistoError }

// errorFromStatus maps an HTTP status code to the appropriate typed error.
// retryAfter is only meaningful for 429 responses.
func errorFromStatus(status int, message string, body any, retryAfter int) error {
	base := &CallistoError{Message: message, StatusCode: status, Body: body}
	switch {
	case status == 401:
		return &AuthenticationError{CallistoError: base}
	case status == 400 || status == 422:
		return &ValidationError{CallistoError: base}
	case status == 404:
		return &NotFoundError{CallistoError: base}
	case status == 429:
		return &RateLimitError{CallistoError: base, RetryAfter: retryAfter}
	default:
		return &APIError{CallistoError: base}
	}
}
