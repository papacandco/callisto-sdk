package callisto

// OtpType is the character set used to generate an OTP code.
type OtpType string

const (
	OtpTypeDigit        OtpType = "digit"
	OtpTypeAlpha        OtpType = "alpha"
	OtpTypeAlphanumeric OtpType = "alphanumeric"
)

// OtpProvider is the delivery channel for an OTP.
type OtpProvider string

const (
	OtpProviderSMS      OtpProvider = "sms"
	OtpProviderWhatsApp OtpProvider = "whatsapp"
)

// WhatsAppMediaType is the type of media sent in a WhatsApp media message.
type WhatsAppMediaType string

const (
	WhatsAppMediaTypeImage    WhatsAppMediaType = "image"
	WhatsAppMediaTypeVideo    WhatsAppMediaType = "video"
	WhatsAppMediaTypeDocument WhatsAppMediaType = "document"
	WhatsAppMediaTypeAudio    WhatsAppMediaType = "audio"
)

// MessageStatus is the delivery status of an SMS or WhatsApp message.
type MessageStatus string

const (
	MessageStatusPending   MessageStatus = "pending"
	MessageStatusSent      MessageStatus = "sent"
	MessageStatusDelivered MessageStatus = "delivered"
	MessageStatusFailed    MessageStatus = "failed"
)

// OtpStatus is the lifecycle status of an OTP.
type OtpStatus string

const (
	OtpStatusPending  OtpStatus = "pending"
	OtpStatusVerified OtpStatus = "verified"
	OtpStatusExpired  OtpStatus = "expired"
	OtpStatusFailed   OtpStatus = "failed"
)
