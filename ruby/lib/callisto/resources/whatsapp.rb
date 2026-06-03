# frozen_string_literal: true

module Callisto
  # WhatsApp resource.
  class WhatsAppResource
    # @param transport [Transport]
    def initialize(transport)
      @t = transport
    end

    # Creates a WhatsApp instance.
    #
    # @param name [String] instance display name
    # @param phone_number [String, nil]
    # @param webhook_url [String, nil]
    # @param idempotency_key [String, nil]
    # @return [WhatsAppInstance]
    def create_instance(name:, phone_number: nil, webhook_url: nil, idempotency_key: nil)
      body = drop_nil({
        "name" => name, "phone_number" => phone_number,
        "webhook_url" => webhook_url, "idempotency_key" => idempotency_key
      })
      WhatsAppInstance.from_hash(@t.request("POST", "/whatsapp/instances", body: body))
    end

    # Lists WhatsApp instances.
    #
    # @param page [Integer]
    # @return [Paginated] of WhatsAppInstance
    def list_instances(page: 1)
      data = @t.request("GET", "/whatsapp/instances", query: { "page" => page })
      Paginated.from_hash(data, WhatsAppInstance.method(:from_hash))
    end

    # Fetches a single instance.
    #
    # @param code [String]
    # @return [WhatsAppInstance]
    def get_instance(code)
      WhatsAppInstance.from_hash(@t.request("GET", "/whatsapp/#{code}"))
    end

    # Fetches the QR code used to link the instance. Returns the raw decoded payload.
    #
    # @param code [String]
    # @return [Object] raw payload
    def get_qr(code)
      @t.request("GET", "/whatsapp/#{code}/qr")
    end

    # Fetches the connection status of an instance. Returns the raw decoded payload.
    #
    # @param code [String]
    # @return [Object] raw payload
    def get_status(code)
      @t.request("GET", "/whatsapp/#{code}/status")
    end

    # Lists messages for an instance.
    #
    # @param code [String]
    # @param started_at [String, nil]
    # @param ended_at [String, nil]
    # @param page [Integer, nil]
    # @param per_page [Integer, nil]
    # @return [Paginated] of WhatsAppMessage
    def list_messages(code, started_at: nil, ended_at: nil, page: nil, per_page: nil)
      data = @t.request("GET", "/whatsapp/#{code}/messages", query: {
        "started_at" => started_at, "ended_at" => ended_at,
        "page" => page, "per_page" => per_page
      })
      Paginated.from_hash(data, WhatsAppMessage.method(:from_hash))
    end

    # Fetches a single WhatsApp message.
    #
    # @param message_id [String]
    # @return [WhatsAppMessage]
    def get_message(message_id)
      WhatsAppMessage.from_hash(@t.request("GET", "/whatsapp/messages/#{message_id}"))
    end

    # Sends a text message.
    #
    # @return [SendWaResult]
    def send_text(code, to:, message:, scheduled_at: nil)
      body = drop_nil({ "to" => to, "message" => message, "scheduled_at" => scheduled_at })
      SendWaResult.from_hash(@t.request("POST", "/whatsapp/#{code}/send/text", body: body))
    end

    # Sends a media message.
    #
    # @param type [String] WhatsAppMediaType value or raw string
    # @return [SendWaResult]
    def send_media(code, to:, type:, media_url:, caption: nil, filename: nil, scheduled_at: nil)
      body = drop_nil({
        "to" => to, "type" => type, "media_url" => media_url,
        "caption" => caption, "filename" => filename, "scheduled_at" => scheduled_at
      })
      SendWaResult.from_hash(@t.request("POST", "/whatsapp/#{code}/send/media", body: body))
    end

    # Sends an interactive buttons message.
    #
    # @return [SendWaResult]
    def send_buttons(code, to:, body:, buttons:, header: nil, footer: nil, scheduled_at: nil)
      payload = drop_nil({
        "to" => to, "body" => body, "buttons" => buttons,
        "header" => header, "footer" => footer, "scheduled_at" => scheduled_at
      })
      SendWaResult.from_hash(@t.request("POST", "/whatsapp/#{code}/send/buttons", body: payload))
    end

    # Sends a location message.
    #
    # @return [SendWaResult]
    def send_location(code, to:, latitude:, longitude:, name: nil, address: nil, scheduled_at: nil)
      payload = drop_nil({
        "to" => to, "latitude" => latitude, "longitude" => longitude,
        "name" => name, "address" => address, "scheduled_at" => scheduled_at
      })
      SendWaResult.from_hash(@t.request("POST", "/whatsapp/#{code}/send/location", body: payload))
    end

    # Sends an interactive list message.
    #
    # @return [SendWaResult]
    def send_list(code, to:, body:, button_text:, sections:, header: nil, footer: nil, scheduled_at: nil)
      payload = drop_nil({
        "to" => to, "body" => body, "button_text" => button_text, "sections" => sections,
        "header" => header, "footer" => footer, "scheduled_at" => scheduled_at
      })
      SendWaResult.from_hash(@t.request("POST", "/whatsapp/#{code}/send/list", body: payload))
    end

    private

    def drop_nil(hash)
      hash.reject { |_, v| v.nil? }
    end
  end
end
