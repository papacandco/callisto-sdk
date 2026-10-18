# frozen_string_literal: true

module Callisto
  # Entry point for the Callisto SDK. Access resources via the readers
  # +sms+, +balance+, +otp+, +whatsapp+, and +notify+, and log shipping via +logs+.
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
    attr_reader :balance, :sms, :otp, :whatsapp, :notify, :error_reporter

    # @return [Logger] the callisto-logs logger (a no-op logger when no Logs key resolves)
    attr_reader :logs

    # @param client_id [String, nil] falls back to CALLISTO_CLIENT_ID
    # @param api_key [String, nil] falls back to CALLISTO_API_KEY
    # @param base_url [String, nil] falls back to CALLISTO_BASE_URL, then the default
    # @param timeout [Float] request timeout in seconds
    # @param error_dsn [String, nil] error-reporting DSN; falls back to CALLISTO_APP_ERROR_DSN
    # @param capture_unhandled [Boolean, nil] install the global unhandled-exception handler;
    #   falls back to CALLISTO_CAPTURE_UNHANDLED, then false
    # @param environment [String, nil] optional environment tag; falls back to CALLISTO_ENVIRONMENT.
    #   Also the logs resource attribute deployment.environment.name
    # @param logs_access_key [String, nil] pk_... of a Logs key; falls back to
    #   CALLISTO_LOGS_ACCESS_KEY. Never the messaging credentials
    # @param logs_secret [String, nil] sk_... of that key; falls back to CALLISTO_LOGS_SECRET
    # @param logs_endpoint [String, nil] falls back to CALLISTO_LOGS_ENDPOINT, then the default
    # @param service_name [String, nil] logs service.name; falls back to CALLISTO_SERVICE_NAME,
    #   then OTEL_SERVICE_NAME, then "unknown_service"
    # @param transport [Transport, nil] optional transport seam for testing
    # @param error_reporter [ErrorReporter, nil] optional reporter seam for testing
    # @param logs [Logger, nil] optional logger seam for testing
    # @raise [ValidationError] on missing messaging credentials, or half / malformed Logs
    #   credentials
    # @yieldparam client [Client] when a block is given, the client is yielded and then closed
    def initialize(client_id: nil, api_key: nil, base_url: nil,
                   timeout: Config::DEFAULT_TIMEOUT,
                   error_dsn: nil, capture_unhandled: nil, environment: nil,
                   logs_access_key: nil, logs_secret: nil, logs_endpoint: nil,
                   service_name: nil,
                   transport: nil, error_reporter: nil, logs: nil)
      config = Config.new(
        client_id: client_id, api_key: api_key, base_url: base_url, timeout: timeout,
        error_dsn: error_dsn, capture_unhandled: capture_unhandled, environment: environment
      )

      # Built first so a Logs configuration error leaves no reporter thread behind.
      @logs = logs || Logger.new(
        logs_access_key: logs_access_key, logs_secret: logs_secret,
        logs_endpoint: logs_endpoint, service_name: service_name,
        environment: config.environment
      )

      @error_reporter = error_reporter || ErrorReporter.new(
        dsn: config.error_dsn,
        sdk: { name: "callisto-sdk", version: Callisto::VERSION, language: "ruby" },
        environment: config.environment
      )

      @transport = transport || Transport.new(config, reporter: @error_reporter)

      @balance = BalanceResource.new(@transport)
      @sms = SmsResource.new(@transport)
      @otp = OtpResource.new(@transport)
      @whatsapp = WhatsAppResource.new(@transport)
      @notify = NotifyResource.new(@transport)

      install_unhandled_handler if config.capture_unhandled && !config.error_dsn.nil?

      return unless block_given?

      begin
        yield self
      ensure
        close
      end
      self
    end

    # Captures an exception via the error reporter (no-op when reporting is disabled).
    #
    # @param error [Exception]
    # @param level [String] one of fatal|error|warning|info
    # @param extra [Hash, nil] extra fields merged into context
    def capture_exception(error, level: "error", extra: nil)
      @error_reporter&.capture_exception(error, level: level, extra: extra)
    end

    # Captures a plain message via the error reporter (no-op when reporting is disabled).
    def capture_message(message, level: "info", extra: nil)
      @error_reporter&.capture_message(message, level: level, extra: extra)
    end

    # Sets (or clears, with nil) the user context attached to subsequent events.
    def set_user(user)
      @error_reporter&.set_user(user)
    end

    # Releases any resources held by the underlying transport, flushes the error reporter and
    # closes the logger (bounded flush of queued log records).
    def close
      @transport.close
      @error_reporter&.close
      @logs&.close
    end

    private

    # Installs an at_exit hook that inspects $! and reports an uncaught exception at level
    # fatal. Best-effort; never alters the process's default exit behavior.
    def install_unhandled_handler
      reporter = @error_reporter
      at_exit do
        err = $!
        next if err.nil? || err.is_a?(SystemExit)

        begin
          reporter&.capture_exception(err, level: "fatal")
          reporter&.flush
        rescue StandardError
          nil
        end
      end
    end
  end
end
