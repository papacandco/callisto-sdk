# frozen_string_literal: true

module Callisto
  # Entry point for the Callisto SDK. Access resources via the readers
  # +sms+, +balance+, +otp+, +whatsapp+, and +notify+.
  #
  # @example
  #   client = Callisto::Client.new(client_id: "id", api_key: "key")
  #   client.balance.get
  #   client.close
  #
  # @example block form (auto-closes)
  #   Callisto::Client.new(client_id: "id", api_key: "key") do |c|
  #     c.sms.send(sender: "Acme", to: "+225...", message: "Hi")
  #   end
  class Client
    attr_reader :balance, :sms, :otp, :whatsapp, :notify

    # @param client_id [String, nil] falls back to CALLISTO_CLIENT_ID
    # @param api_key [String, nil] falls back to CALLISTO_API_KEY
    # @param base_url [String, nil] falls back to CALLISTO_BASE_URL, then the default
    # @param timeout [Float] request timeout in seconds
    # @param transport [Transport, nil] optional transport seam for testing
    # @yieldparam client [Client] when a block is given, the client is yielded and then closed
    def initialize(client_id: nil, api_key: nil, base_url: nil,
                   timeout: Config::DEFAULT_TIMEOUT, transport: nil)
      config = Config.new(client_id: client_id, api_key: api_key, base_url: base_url, timeout: timeout)
      @transport = transport || Transport.new(config)

      @balance = BalanceResource.new(@transport)
      @sms = SmsResource.new(@transport)
      @otp = OtpResource.new(@transport)
      @whatsapp = WhatsAppResource.new(@transport)
      @notify = NotifyResource.new(@transport)

      return unless block_given?

      begin
        yield self
      ensure
        close
      end
      self
    end

    # Releases any resources held by the underlying transport.
    def close
      @transport.close
    end
  end
end
