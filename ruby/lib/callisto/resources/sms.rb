# frozen_string_literal: true

module Callisto
  # SMS resource.
  class SmsResource
    # @param transport [Transport]
    def initialize(transport)
      @t = transport
    end

    # Sends an SMS to one or more recipients.
    #
    # @param sender [String] approved sender name
    # @param to [String, Array<String>] a single recipient or a list of recipients
    # @param message [String] message body
    # @param notify_url [String, nil] webhook URL for delivery status callbacks
    # @param scheduled_at [String, nil] schedule delivery
    # @return [SendSmsResult]
    def send(sender:, to:, message:, notify_url: nil, scheduled_at: nil)
      body = { "sender" => sender, "to" => to, "message" => message }
      body["notify_url"] = notify_url unless notify_url.nil?
      body["scheduled_at"] = scheduled_at unless scheduled_at.nil?
      SendSmsResult.from_hash(@t.request("POST", "/sms/send", body: body))
    end

    # Lists sent SMS messages.
    #
    # @param started_at [String, nil]
    # @param ended_at [String, nil]
    # @param page [Integer, nil]
    # @param per_page [Integer, nil]
    # @return [Paginated] of SmsMessage
    def list(started_at: nil, ended_at: nil, page: nil, per_page: nil)
      data = @t.request("GET", "/sms/messages", query: {
        "started_at" => started_at, "ended_at" => ended_at,
        "page" => page, "per_page" => per_page
      })
      Paginated.from_hash(data, SmsMessage.method(:from_hash))
    end

    # Fetches a single SMS by ID.
    #
    # @param message_id [String]
    # @return [SmsMessage]
    def get_status(message_id)
      SmsMessage.from_hash(@t.request("GET", "/sms/#{message_id}"))
    end
  end
end
