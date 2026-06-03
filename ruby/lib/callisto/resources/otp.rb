# frozen_string_literal: true

module Callisto
  # OTP resource.
  class OtpResource
    # @param transport [Transport]
    def initialize(transport)
      @t = transport
    end

    # Generates and sends a one-time password.
    #
    # @param to [String] recipient number
    # @param message [String] message template
    # @param sender [String, nil] sender name
    # @param expired_in [Integer, nil] code lifetime in seconds
    # @param type [String, nil] OtpType value or raw string
    # @param digit_size [Integer, nil] number of characters in the code
    # @param provider [String, nil] OtpProvider value or raw string
    # @param instance_code [String, nil] WhatsApp instance code (required when provider is whatsapp)
    # @return [SendOtpResult]
    # @raise [ValidationError] when provider is "whatsapp" and instance_code is missing
    def send(to:, message:, sender: nil, expired_in: nil, type: nil,
             digit_size: nil, provider: nil, instance_code: nil)
      if provider == OtpProvider::WHATSAPP && (instance_code.nil? || instance_code.empty?)
        err = ValidationError.new("instance_code is required when provider is whatsapp")
        @t.reporter&.capture_exception(err) if @t.respond_to?(:reporter)
        raise err
      end

      body = { "to" => to, "message" => message }
      body["sender"] = sender unless sender.nil?
      body["expired_in"] = expired_in unless expired_in.nil?
      body["type"] = type unless type.nil?
      body["digit_size"] = digit_size unless digit_size.nil?
      body["provider"] = provider unless provider.nil?
      body["instanceCode"] = instance_code unless instance_code.nil?

      SendOtpResult.from_hash(@t.request("POST", "/otp/send", body: body))
    end

    # Verifies a code against an OTP.
    #
    # @param otp_id [String]
    # @param code [String]
    # @return [VerifyOtpResult]
    def verify(otp_id:, code:)
      VerifyOtpResult.from_hash(
        @t.request("POST", "/otp/verify", body: { "otp_id" => otp_id, "code" => code })
      )
    end

    # Fetches a single OTP by ID.
    #
    # @param otp_id [String]
    # @return [Otp]
    def get_status(otp_id)
      Otp.from_hash(@t.request("GET", "/otps/#{otp_id}"))
    end

    # Lists OTPs.
    #
    # @param started_at [String, nil]
    # @param ended_at [String, nil]
    # @param page [Integer, nil]
    # @param limit [Integer, nil]
    # @return [Paginated] of Otp
    def list(started_at: nil, ended_at: nil, page: nil, limit: nil)
      data = @t.request("GET", "/otps", query: {
        "started_at" => started_at, "ended_at" => ended_at,
        "page" => page, "limit" => limit
      })
      Paginated.from_hash(data, Otp.method(:from_hash))
    end
  end
end
