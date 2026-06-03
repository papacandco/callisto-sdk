package callisto

import (
	"context"
	"encoding/json"
)

// WhatsAppService accesses the WhatsApp resource.
type WhatsAppService struct {
	t *transport
}

// CreateInstanceParams are the parameters for WhatsApp.CreateInstance.
type CreateInstanceParams struct {
	// Name is the instance name. Required.
	Name string
	// PhoneNumber is the optional phone number to pair.
	PhoneNumber string
	// WebhookURL is the optional webhook URL for inbound events.
	WebhookURL string
	// IdempotencyKey optionally deduplicates instance creation.
	IdempotencyKey string
}

// CreateInstance provisions a new WhatsApp instance. POST /whatsapp/instances.
func (s *WhatsAppService) CreateInstance(ctx context.Context, params CreateInstanceParams) (*WhatsAppInstance, error) {
	body := map[string]any{"name": params.Name}
	if params.PhoneNumber != "" {
		body["phone_number"] = params.PhoneNumber
	}
	if params.WebhookURL != "" {
		body["webhook_url"] = params.WebhookURL
	}
	if params.IdempotencyKey != "" {
		body["idempotency_key"] = params.IdempotencyKey
	}
	return decodeInstance(s.t.request(ctx, "POST", "/whatsapp/instances", body, nil))
}

// ListInstances lists WhatsApp instances. GET /whatsapp/instances. page
// defaults to 1 when zero or negative.
func (s *WhatsAppService) ListInstances(ctx context.Context, page int) (*Paginated[WhatsAppInstance], error) {
	if page <= 0 {
		page = 1
	}
	raw, err := s.t.request(ctx, "GET", "/whatsapp/instances", nil, map[string]any{"page": page})
	if err != nil {
		return nil, err
	}
	var out Paginated[WhatsAppInstance]
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// GetInstance fetches a single instance by code. GET /whatsapp/{code}.
func (s *WhatsAppService) GetInstance(ctx context.Context, code string) (*WhatsAppInstance, error) {
	return decodeInstance(s.t.request(ctx, "GET", "/whatsapp/"+code, nil, nil))
}

// GetQR returns the raw QR pairing payload. GET /whatsapp/{code}/qr.
func (s *WhatsAppService) GetQR(ctx context.Context, code string) (json.RawMessage, error) {
	return s.t.request(ctx, "GET", "/whatsapp/"+code+"/qr", nil, nil)
}

// GetStatus returns the raw instance status payload. GET /whatsapp/{code}/status.
func (s *WhatsAppService) GetStatus(ctx context.Context, code string) (json.RawMessage, error) {
	return s.t.request(ctx, "GET", "/whatsapp/"+code+"/status", nil, nil)
}

// ListMessagesParams are the parameters for WhatsApp.ListMessages.
type ListMessagesParams struct {
	StartedAt string
	EndedAt   string
	Page      *int
	PerPage   *int
}

// ListMessages lists messages for an instance. GET /whatsapp/{code}/messages.
func (s *WhatsAppService) ListMessages(ctx context.Context, code string, params ListMessagesParams) (*Paginated[WhatsAppMessage], error) {
	query := map[string]any{
		"started_at": emptyToNil(params.StartedAt),
		"ended_at":   emptyToNil(params.EndedAt),
		"page":       params.Page,
		"per_page":   params.PerPage,
	}
	raw, err := s.t.request(ctx, "GET", "/whatsapp/"+code+"/messages", nil, query)
	if err != nil {
		return nil, err
	}
	var out Paginated[WhatsAppMessage]
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// GetMessage fetches a single WhatsApp message by ID.
// GET /whatsapp/messages/{id}.
func (s *WhatsAppService) GetMessage(ctx context.Context, messageID string) (*WhatsAppMessage, error) {
	raw, err := s.t.request(ctx, "GET", "/whatsapp/messages/"+messageID, nil, nil)
	if err != nil {
		return nil, err
	}
	var out WhatsAppMessage
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// SendTextParams are the parameters for WhatsApp.SendText.
type SendTextParams struct {
	To          string
	Message     string
	ScheduledAt string
}

// SendText sends a text message. POST /whatsapp/{code}/send/text.
func (s *WhatsAppService) SendText(ctx context.Context, code string, params SendTextParams) (*SendWAResult, error) {
	body := map[string]any{"to": params.To, "message": params.Message}
	if params.ScheduledAt != "" {
		body["scheduled_at"] = params.ScheduledAt
	}
	return decodeWAResult(s.t.request(ctx, "POST", "/whatsapp/"+code+"/send/text", body, nil))
}

// SendMediaParams are the parameters for WhatsApp.SendMedia.
type SendMediaParams struct {
	To          string
	Type        WhatsAppMediaType
	MediaURL    string
	Caption     string
	Filename    string
	ScheduledAt string
}

// SendMedia sends a media message. POST /whatsapp/{code}/send/media.
func (s *WhatsAppService) SendMedia(ctx context.Context, code string, params SendMediaParams) (*SendWAResult, error) {
	body := map[string]any{
		"to":        params.To,
		"type":      string(params.Type),
		"media_url": params.MediaURL,
	}
	if params.Caption != "" {
		body["caption"] = params.Caption
	}
	if params.Filename != "" {
		body["filename"] = params.Filename
	}
	if params.ScheduledAt != "" {
		body["scheduled_at"] = params.ScheduledAt
	}
	return decodeWAResult(s.t.request(ctx, "POST", "/whatsapp/"+code+"/send/media", body, nil))
}

// SendButtonsParams are the parameters for WhatsApp.SendButtons.
type SendButtonsParams struct {
	To          string
	Body        string
	Buttons     []any
	Header      string
	Footer      string
	ScheduledAt string
}

// SendButtons sends an interactive buttons message.
// POST /whatsapp/{code}/send/buttons.
func (s *WhatsAppService) SendButtons(ctx context.Context, code string, params SendButtonsParams) (*SendWAResult, error) {
	body := map[string]any{
		"to":      params.To,
		"body":    params.Body,
		"buttons": params.Buttons,
	}
	if params.Header != "" {
		body["header"] = params.Header
	}
	if params.Footer != "" {
		body["footer"] = params.Footer
	}
	if params.ScheduledAt != "" {
		body["scheduled_at"] = params.ScheduledAt
	}
	return decodeWAResult(s.t.request(ctx, "POST", "/whatsapp/"+code+"/send/buttons", body, nil))
}

// SendLocationParams are the parameters for WhatsApp.SendLocation.
type SendLocationParams struct {
	To          string
	Latitude    float64
	Longitude   float64
	Name        string
	Address     string
	ScheduledAt string
}

// SendLocation sends a location message. POST /whatsapp/{code}/send/location.
func (s *WhatsAppService) SendLocation(ctx context.Context, code string, params SendLocationParams) (*SendWAResult, error) {
	body := map[string]any{
		"to":        params.To,
		"latitude":  params.Latitude,
		"longitude": params.Longitude,
	}
	if params.Name != "" {
		body["name"] = params.Name
	}
	if params.Address != "" {
		body["address"] = params.Address
	}
	if params.ScheduledAt != "" {
		body["scheduled_at"] = params.ScheduledAt
	}
	return decodeWAResult(s.t.request(ctx, "POST", "/whatsapp/"+code+"/send/location", body, nil))
}

// SendListParams are the parameters for WhatsApp.SendList.
type SendListParams struct {
	To          string
	Body        string
	ButtonText  string
	Sections    []any
	Header      string
	Footer      string
	ScheduledAt string
}

// SendList sends an interactive list message. POST /whatsapp/{code}/send/list.
func (s *WhatsAppService) SendList(ctx context.Context, code string, params SendListParams) (*SendWAResult, error) {
	body := map[string]any{
		"to":          params.To,
		"body":        params.Body,
		"button_text": params.ButtonText,
		"sections":    params.Sections,
	}
	if params.Header != "" {
		body["header"] = params.Header
	}
	if params.Footer != "" {
		body["footer"] = params.Footer
	}
	if params.ScheduledAt != "" {
		body["scheduled_at"] = params.ScheduledAt
	}
	return decodeWAResult(s.t.request(ctx, "POST", "/whatsapp/"+code+"/send/list", body, nil))
}

func decodeInstance(raw json.RawMessage, err error) (*WhatsAppInstance, error) {
	if err != nil {
		return nil, err
	}
	var out WhatsAppInstance
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func decodeWAResult(raw json.RawMessage, err error) (*SendWAResult, error) {
	if err != nil {
		return nil, err
	}
	var out SendWAResult
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}
