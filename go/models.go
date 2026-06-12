package callisto

import "encoding/json"

// Balance is the account balance.
type Balance struct {
	Credit                float64  `json:"credit"`
	Currency              string   `json:"currency"`
	SMSPriceLocal         *float64 `json:"sms_price_local,omitempty"`
	SMSPriceInternational *float64 `json:"sms_price_international,omitempty"`
}

// SendSMSResult is the result of an SMS send.
type SendSMSResult struct {
	TotalAmount     float64           `json:"total_amount"`
	AvailableCredit float64           `json:"available_credit"`
	Status          string            `json:"status"`
	RecipientCount  int               `json:"recipient_count"`
	Scheduled       bool              `json:"scheduled"`
	Messages        []json.RawMessage `json:"messages"`
}

// SendOTPResult is the result of an OTP send.
type SendOTPResult struct {
	ID        string          `json:"id"`
	Provider  string          `json:"provider"`
	Recipient json.RawMessage `json:"recipient"`
	ExpiresAt string          `json:"expires_at"`
	ExpiresIn int             `json:"expires_in"`
}

// VerifyOTPResult is the result of an OTP verification.
type VerifyOTPResult struct {
	ID         string `json:"id"`
	Status     string `json:"status"`
	Verified   bool   `json:"verified"`
	VerifiedAt string `json:"verified_at,omitempty"`
}

// SendWAResult is the result of a WhatsApp send.
type SendWAResult struct {
	ID          string          `json:"id"`
	InstanceID  string          `json:"instance_id"`
	Recipient   json.RawMessage `json:"recipient"`
	MessageType string          `json:"message_type"`
	Status      string          `json:"status"`
	Scheduled   bool            `json:"scheduled"`
	MediaURL    string          `json:"media_url,omitempty"`
}

// NotifyResult is the result of a multi-channel notification.
type NotifyResult struct {
	Status        string          `json:"status"`
	Topic         json.RawMessage `json:"topic"`
	QueuedEvents  json.RawMessage `json:"queued_events"`
	TopicMessages json.RawMessage `json:"topic_messages"`
}

// SMSMessage is a sent SMS record.
type SMSMessage struct {
	ID         string `json:"id"`
	SenderName string `json:"sender_name,omitempty"`
	Recipient  string `json:"recipient,omitempty"`
	Content    string `json:"content,omitempty"`
	Status     string `json:"status,omitempty"`
	CreatedAt  string `json:"created_at,omitempty"`
	UpdatedAt  string `json:"updated_at,omitempty"`
}

// OTP is an OTP record. It carries both OtpID (populated by GetStatus) and ID
// (populated by List rows); depending on the endpoint, one or the other may be
// set.
type OTP struct {
	OtpID      string `json:"otp_id,omitempty"`
	ID         string `json:"id,omitempty"`
	Status     string `json:"status,omitempty"`
	Recipient  string `json:"recipient,omitempty"`
	ExpiresAt  string `json:"expires_at,omitempty"`
	VerifiedAt string `json:"verified_at,omitempty"`
	Attempts   *int   `json:"attempts,omitempty"`
	CreatedAt  string `json:"created_at,omitempty"`
}

// WhatsAppInstance is a WhatsApp instance record.
type WhatsAppInstance struct {
	ID                 string   `json:"id"`
	Code               string   `json:"code,omitempty"`
	ClientID           string   `json:"client_id,omitempty"`
	Name               string   `json:"name,omitempty"`
	PhoneNumber        string   `json:"phone_number,omitempty"`
	PhoneName          string   `json:"phone_name,omitempty"`
	Status             string   `json:"status,omitempty"`
	BillingStatus      string   `json:"billing_status,omitempty"`
	TrialDaysRemaining *int     `json:"trial_days_remaining,omitempty"`
	MonthlyFee         *float64 `json:"monthly_fee,omitempty"`
	MessagesSentToday  *int     `json:"messages_sent_today,omitempty"`
	MessagesSentMonth  *int     `json:"messages_sent_month,omitempty"`
	DailyLimit         *int     `json:"daily_limit,omitempty"`
	LastMessageAt      string   `json:"last_message_at,omitempty"`
	WebhookURL         string   `json:"webhook_url,omitempty"`
	IsActive           *bool    `json:"is_active,omitempty"`
	CreatedAt          string   `json:"created_at,omitempty"`
	UpdatedAt          string   `json:"updated_at,omitempty"`
}

// WhatsAppMessage is a WhatsApp message record.
type WhatsAppMessage struct {
	ID                  string          `json:"id"`
	InstanceID          string          `json:"instance_id,omitempty"`
	ClientID            string          `json:"client_id,omitempty"`
	ClientAPIID         string          `json:"client_api_id,omitempty"`
	Recipient           string          `json:"recipient,omitempty"`
	RecipientName       string          `json:"recipient_name,omitempty"`
	MessageType         string          `json:"message_type,omitempty"`
	Content             string          `json:"content,omitempty"`
	MediaURL            string          `json:"media_url,omitempty"`
	MediaMimetype       string          `json:"media_mimetype,omitempty"`
	MediaFilename       string          `json:"media_filename,omitempty"`
	ExtraData           json.RawMessage `json:"extra_data,omitempty"`
	Direction           string          `json:"direction,omitempty"`
	Status              string          `json:"status,omitempty"`
	WhatsAppMessageID   string          `json:"whatsapp_message_id,omitempty"`
	ErrorCode           *int            `json:"error_code,omitempty"`
	ErrorMessage        string          `json:"error_message,omitempty"`
	RetryCount          *int            `json:"retry_count,omitempty"`
	IsBillable          *bool           `json:"is_billable,omitempty"`
	Cost                *float64        `json:"cost,omitempty"`
	SentAt              string          `json:"sent_at,omitempty"`
	DeliveredAt         string          `json:"delivered_at,omitempty"`
	ReadAt              string          `json:"read_at,omitempty"`
	ScheduledAt         string          `json:"scheduled_at,omitempty"`
	CreatedAt           string          `json:"created_at,omitempty"`
	UpdatedAt           string          `json:"updated_at,omitempty"`
	ProcessorIdentifier string          `json:"processor_identifier,omitempty"`
}

// Paginated is a generic page container returned by list methods. Items holds
// the typed rows on this page; the other fields describe the page window.
type Paginated[T any] struct {
	Items       []T  `json:"items"`
	Total       int  `json:"total"`
	PerPage     int  `json:"per_page"`
	CurrentPage int  `json:"current_page"`
	Next        *int `json:"next"`
	Previous    *int `json:"previous"`
	TotalPages  int  `json:"total_pages"`
}
