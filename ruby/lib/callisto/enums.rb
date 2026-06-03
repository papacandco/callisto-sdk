# frozen_string_literal: true

module Callisto
  # Status of an SMS/WhatsApp message.
  module MessageStatus
    PENDING = "pending"
    SENT = "sent"
    DELIVERED = "delivered"
    FAILED = "failed"
  end

  # Status of an OTP.
  module OtpStatus
    PENDING = "pending"
    VERIFIED = "verified"
    EXPIRED = "expired"
    FAILED = "failed"
  end

  # Character set used when generating an OTP code.
  module OtpType
    DIGIT = "digit"
    ALPHA = "alpha"
    ALPHANUMERIC = "alphanumeric"
  end

  # Delivery channel for an OTP.
  module OtpProvider
    SMS = "sms"
    WHATSAPP = "whatsapp"
  end

  # WhatsApp media message type.
  module WhatsAppMediaType
    IMAGE = "image"
    VIDEO = "video"
    DOCUMENT = "document"
    AUDIO = "audio"
  end
end
