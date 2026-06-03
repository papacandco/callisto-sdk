package callisto

import (
	"context"
	"encoding/json"
)

// SMSService accesses the SMS resource.
type SMSService struct {
	t *transport
}

// SendSMSParams are the parameters for SMS.Send.
type SendSMSParams struct {
	// Sender is the approved sender name. Required.
	Sender string `json:"sender"`
	// To is one or more recipient numbers. A single recipient is a one-element
	// slice. Required.
	To []string `json:"to"`
	// Message is the message body. Required.
	Message string `json:"message"`
	// NotifyURL is an optional webhook URL for delivery status callbacks.
	NotifyURL string `json:"notify_url,omitempty"`
	// ScheduledAt optionally schedules delivery.
	ScheduledAt string `json:"scheduled_at,omitempty"`
}

// Send sends an SMS to one or more recipients. POST /sms/send.
func (s *SMSService) Send(ctx context.Context, params SendSMSParams) (*SendSMSResult, error) {
	raw, err := s.t.request(ctx, "POST", "/sms/send", params, nil)
	if err != nil {
		return nil, err
	}
	var out SendSMSResult
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// ListSMSParams are the parameters for SMS.List.
type ListSMSParams struct {
	StartedAt string
	EndedAt   string
	Page      *int
	PerPage   *int
}

// List lists sent SMS messages. GET /sms/messages.
func (s *SMSService) List(ctx context.Context, params ListSMSParams) (*Paginated[SMSMessage], error) {
	query := map[string]any{
		"started_at": emptyToNil(params.StartedAt),
		"ended_at":   emptyToNil(params.EndedAt),
		"page":       params.Page,
		"per_page":   params.PerPage,
	}
	raw, err := s.t.request(ctx, "GET", "/sms/messages", nil, query)
	if err != nil {
		return nil, err
	}
	var out Paginated[SMSMessage]
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// GetStatus fetches a single SMS by ID. GET /sms/{id}.
func (s *SMSService) GetStatus(ctx context.Context, messageID string) (*SMSMessage, error) {
	raw, err := s.t.request(ctx, "GET", "/sms/"+messageID, nil, nil)
	if err != nil {
		return nil, err
	}
	var out SMSMessage
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// emptyToNil returns nil for an empty string so it is dropped from the query.
func emptyToNil(s string) any {
	if s == "" {
		return nil
	}
	return s
}
