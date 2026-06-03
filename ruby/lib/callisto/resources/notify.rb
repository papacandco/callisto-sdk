# frozen_string_literal: true

module Callisto
  # Notify resource (multi-channel notifications).
  class NotifyResource
    # @param transport [Transport]
    def initialize(transport)
      @t = transport
    end

    # Sends a multi-channel notification to a topic. At least one event block must be present.
    #
    # @param topic [String] topic identifier
    # @param email [Array, nil]
    # @param sms [Array, nil]
    # @param mobile_push [Array, nil]
    # @param web_push [Array, nil]
    # @param webhook [Array, nil]
    # @param messaging [Array, nil]
    # @param real_time [Array, nil]
    # @return [NotifyResult]
    # @raise [ValidationError] when no event block is provided
    def send(topic:, email: nil, sms: nil, mobile_push: nil, web_push: nil,
             webhook: nil, messaging: nil, real_time: nil)
      blocks = {
        "email" => email, "sms" => sms, "mobile_push" => mobile_push,
        "web_push" => web_push, "webhook" => webhook,
        "messaging" => messaging, "real_time" => real_time
      }
      present = blocks.reject { |_, v| v.nil? || (v.respond_to?(:empty?) && v.empty?) }

      if present.empty?
        raise ValidationError.new(
          "At least one event block (email, sms, mobile_push, web_push, " \
          "webhook, messaging, real_time) must be provided."
        )
      end

      body = { "topic" => topic }.merge(present)
      NotifyResult.from_hash(@t.request("POST", "/notify/send", body: body))
    end
  end
end
