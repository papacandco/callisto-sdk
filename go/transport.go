package callisto

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"reflect"
	"strconv"
)

// transport performs HTTP requests against the Callisto API. It applies Basic
// auth, encodes JSON bodies, drops nil query params, maps non-2xx responses to
// typed errors, and reports every error to the reporter (fire-and-forget).
type transport struct {
	cfg      config
	client   *http.Client
	reporter *ErrorReporter
}

func newTransport(cfg config, httpClient *http.Client, reporter *ErrorReporter) *transport {
	c := httpClient
	if c == nil {
		c = &http.Client{Timeout: cfg.timeout}
	}
	return &transport{cfg: cfg, client: c, reporter: reporter}
}

// request executes an API call. body is JSON-encoded (omitted when nil); query
// values that are nil are dropped. It returns the raw JSON response on success,
// or a typed error (mapped from the status, or *NetworkError on transport
// failure). Every error is reported before being returned.
func (t *transport) request(ctx context.Context, method, path string, body any, query map[string]any) (json.RawMessage, error) {
	endpoint := t.cfg.baseURL + path

	if q := encodeQuery(query); q != "" {
		endpoint += "?" + q
	}

	var reader io.Reader
	if body != nil {
		buf, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		reader = bytes.NewReader(buf)
	}

	req, err := http.NewRequestWithContext(ctx, method, endpoint, reader)
	if err != nil {
		return nil, err
	}
	req.SetBasicAuth(t.cfg.clientID, t.cfg.apiKey)
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}

	resp, err := t.client.Do(req)
	if err != nil {
		nerr := &NetworkError{CallistoError: &CallistoError{
			Message:    fmt.Sprintf("Request to %s failed: %v", endpoint, err),
			StatusCode: 0,
		}}
		t.report(nerr, method, path)
		return nil, nerr
	}
	defer resp.Body.Close()

	raw, _ := io.ReadAll(resp.Body)

	var data any
	if len(raw) > 0 {
		if jerr := json.Unmarshal(raw, &data); jerr != nil {
			data = string(raw)
		}
	}

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		message := messageFromBody(data, resp.StatusCode)
		retryAfter := 0
		if resp.StatusCode == http.StatusTooManyRequests {
			if v := resp.Header.Get("Retry-After"); v != "" {
				if n, perr := strconv.Atoi(v); perr == nil {
					retryAfter = n
				}
			}
		}
		err := errorFromStatus(resp.StatusCode, message, data, retryAfter)
		t.report(err, method, path)
		return nil, err
	}

	if len(raw) == 0 {
		return nil, nil
	}
	return json.RawMessage(raw), nil
}

// report fires a fire-and-forget capture for a transport-originated error.
func (t *transport) report(err error, method, path string) {
	if t.reporter != nil {
		t.reporter.CaptureException(err, "error", nil, method, path)
	}
}

// encodeQuery builds a URL query string from a map, dropping nil values and
// formatting scalars. Keys are sorted by url.Values for deterministic output.
func encodeQuery(query map[string]any) string {
	if len(query) == 0 {
		return ""
	}
	values := url.Values{}
	for k, v := range query {
		if v == nil {
			continue
		}
		// Skip nil pointers.
		rv := reflect.ValueOf(v)
		if rv.Kind() == reflect.Ptr {
			if rv.IsNil() {
				continue
			}
			rv = rv.Elem()
			v = rv.Interface()
		}
		values.Set(k, fmt.Sprintf("%v", v))
	}
	return values.Encode()
}

// messageFromBody extracts the API "message" field, falling back to a generic
// HTTP status description.
func messageFromBody(data any, status int) string {
	if m, ok := data.(map[string]any); ok {
		if msg, ok := m["message"].(string); ok && msg != "" {
			return msg
		}
	}
	return fmt.Sprintf("HTTP %d", status)
}

func (t *transport) reportLocal(err error) {
	if t.reporter != nil {
		t.reporter.CaptureException(err, "error", nil, "", "")
	}
}
