package callisto

import (
	"context"
	"encoding/json"
)

// NotifyService accesses the notify resource.
type NotifyService struct {
	t *transport
}

// NotifyParams are the parameters for Notify.Send. Topic is required, and at
// least one event block must be non-empty.
type NotifyParams struct {
	// Topic is the notification topic. Required.
	Topic string
	// The event blocks. At least one must be present.
	Email      []any
	SMS        []any
	MobilePush []any
	WebPush    []any
	Webhook    []any
	Messaging  []any
	RealTime   []any
}

// Send publishes a single notification across one or more channels.
// POST /notify/send.
//
// Client-side validation: at least one event block (Email, SMS, MobilePush,
// WebPush, Webhook, Messaging, RealTime) must be present; otherwise a
// *ValidationError is returned before any request is made.
func (s *NotifyService) Send(ctx context.Context, params NotifyParams) (*NotifyResult, error) {
	blocks := []struct {
		key   string
		value []any
	}{
		{"email", params.Email},
		{"sms", params.SMS},
		{"mobile_push", params.MobilePush},
		{"web_push", params.WebPush},
		{"webhook", params.Webhook},
		{"messaging", params.Messaging},
		{"real_time", params.RealTime},
	}

	body := map[string]any{"topic": params.Topic}
	present := false
	for _, b := range blocks {
		if len(b.value) > 0 {
			body[b.key] = b.value
			present = true
		}
	}

	if !present {
		verr := &ValidationError{CallistoError: &CallistoError{
			Message: "At least one event block (email, sms, mobile_push, web_push, " +
				"webhook, messaging, real_time) must be provided.",
		}}
		s.t.reportLocal(verr)
		return nil, verr
	}

	raw, err := s.t.request(ctx, "POST", "/notify/send", body, nil)
	if err != nil {
		return nil, err
	}
	var out NotifyResult
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}
