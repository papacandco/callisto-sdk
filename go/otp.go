package callisto

import (
	"context"
	"encoding/json"
)

// OTPService accesses the OTP resource.
type OTPService struct {
	t *transport
}

// SendOTPParams are the parameters for OTP.Send.
type SendOTPParams struct {
	// To is the recipient. Required.
	To string
	// Message is the message template/body. Required.
	Message string
	// Sender is the optional sender name.
	Sender string
	// ExpiredIn is the optional code lifetime in seconds.
	ExpiredIn *int
	// Type is the optional OTP character set.
	Type OtpType
	// DigitSize is the optional code length.
	DigitSize *int
	// Provider is the optional delivery channel (sms or whatsapp).
	Provider OtpProvider
	// InstanceCode is the WhatsApp instance code. Required when Provider is
	// whatsapp (sent as the JSON key "instanceCode").
	InstanceCode string
}

// Send generates and sends an OTP. POST /otp/send.
//
// Client-side validation: when Provider is whatsapp, InstanceCode is required;
// otherwise a *ValidationError is returned before any request is made.
func (s *OTPService) Send(ctx context.Context, params SendOTPParams) (*SendOTPResult, error) {
	if params.Provider == OtpProviderWhatsApp && params.InstanceCode == "" {
		verr := &ValidationError{CallistoError: &CallistoError{
			Message: "instance_code is required when provider is whatsapp",
		}}
		s.t.reportLocal(verr)
		return nil, verr
	}

	body := map[string]any{"to": params.To, "message": params.Message}
	if params.Sender != "" {
		body["sender"] = params.Sender
	}
	if params.ExpiredIn != nil {
		body["expired_in"] = *params.ExpiredIn
	}
	if params.Type != "" {
		body["type"] = string(params.Type)
	}
	if params.DigitSize != nil {
		body["digit_size"] = *params.DigitSize
	}
	if params.Provider != "" {
		body["provider"] = string(params.Provider)
	}
	if params.InstanceCode != "" {
		body["instanceCode"] = params.InstanceCode
	}

	raw, err := s.t.request(ctx, "POST", "/otp/send", body, nil)
	if err != nil {
		return nil, err
	}
	var out SendOTPResult
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// Verify checks a submitted OTP code. POST /otp/verify.
func (s *OTPService) Verify(ctx context.Context, otpID, code string) (*VerifyOTPResult, error) {
	body := map[string]any{"otp_id": otpID, "code": code}
	raw, err := s.t.request(ctx, "POST", "/otp/verify", body, nil)
	if err != nil {
		return nil, err
	}
	var out VerifyOTPResult
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// GetStatus fetches a single OTP by ID. GET /otps/{id}.
func (s *OTPService) GetStatus(ctx context.Context, otpID string) (*OTP, error) {
	raw, err := s.t.request(ctx, "GET", "/otps/"+otpID, nil, nil)
	if err != nil {
		return nil, err
	}
	var out OTP
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// ListOTPParams are the parameters for OTP.List.
type ListOTPParams struct {
	StartedAt string
	EndedAt   string
	Page      *int
	Limit     *int
}

// List lists OTP records. GET /otps.
func (s *OTPService) List(ctx context.Context, params ListOTPParams) (*Paginated[OTP], error) {
	query := map[string]any{
		"started_at": emptyToNil(params.StartedAt),
		"ended_at":   emptyToNil(params.EndedAt),
		"page":       params.Page,
		"limit":      params.Limit,
	}
	raw, err := s.t.request(ctx, "GET", "/otps", nil, query)
	if err != nil {
		return nil, err
	}
	var out Paginated[OTP]
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}
