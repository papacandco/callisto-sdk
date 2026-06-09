# frozen_string_literal: true

module Callisto
  # Resolves and holds client configuration (credentials, base URL, timeout).
  class Config
    DEFAULT_BASE_URL = "https://api.callistosignal.com/v1"
    DEFAULT_TIMEOUT = 30.0

    attr_reader :client_id, :api_key, :base_url, :timeout,
                :error_dsn, :capture_unhandled, :environment

    # Resolves configuration from explicit arguments, falling back to environment variables.
    #
    # @param client_id [String, nil] falls back to CALLISTO_CLIENT_ID
    # @param api_key [String, nil] falls back to CALLISTO_API_KEY
    # @param base_url [String, nil] falls back to CALLISTO_BASE_URL, then the default
    # @param timeout [Float] request timeout in seconds
    # @param error_dsn [String, nil] error-reporting ingest DSN; falls back to
    #   CALLISTO_APP_ERROR_DSN. When absent, error reporting is fully disabled (no-op).
    # @param capture_unhandled [Boolean, nil] install the global unhandled-exception
    #   handler; falls back to CALLISTO_CAPTURE_UNHANDLED, then false.
    # @param environment [String, nil] optional tag in context.environment; falls back
    #   to CALLISTO_ENVIRONMENT.
    # @raise [ValidationError] if client_id or api_key cannot be resolved
    def initialize(client_id: nil, api_key: nil, base_url: nil, timeout: DEFAULT_TIMEOUT,
                   error_dsn: nil, capture_unhandled: nil, environment: nil)
      client_id ||= ENV["CALLISTO_CLIENT_ID"]
      api_key ||= ENV["CALLISTO_API_KEY"]

      if client_id.nil? || client_id.empty? || api_key.nil? || api_key.empty?
        raise ValidationError.new(
          "Callisto: client_id and api_key are required " \
          "(pass arguments or set CALLISTO_CLIENT_ID / CALLISTO_API_KEY)."
        )
      end

      base_url = (base_url || ENV["CALLISTO_BASE_URL"] || DEFAULT_BASE_URL).sub(%r{/+\z}, "")

      error_dsn = error_dsn || ENV["CALLISTO_APP_ERROR_DSN"]
      error_dsn = nil if error_dsn.is_a?(String) && error_dsn.empty?

      capture_unhandled = resolve_capture_unhandled(capture_unhandled)

      environment ||= ENV["CALLISTO_ENVIRONMENT"]
      environment = nil if environment.is_a?(String) && environment.empty?

      @client_id = client_id
      @api_key = api_key
      @base_url = base_url
      @timeout = timeout
      @error_dsn = error_dsn
      @capture_unhandled = capture_unhandled
      @environment = environment
    end

    private

    def resolve_capture_unhandled(value)
      return value == true unless value.nil?

      raw = ENV["CALLISTO_CAPTURE_UNHANDLED"]
      return false if raw.nil?

      %w[1 true yes on].include?(raw.strip.downcase)
    end
  end
end
