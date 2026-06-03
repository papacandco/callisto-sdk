# frozen_string_literal: true

module Callisto
  # Resolves and holds client configuration (credentials, base URL, timeout).
  class Config
    DEFAULT_BASE_URL = "https://api.callistosignal.com/v1"
    DEFAULT_TIMEOUT = 30.0

    attr_reader :client_id, :api_key, :base_url, :timeout

    # Resolves configuration from explicit arguments, falling back to environment variables.
    #
    # @param client_id [String, nil] falls back to CALLISTO_CLIENT_ID
    # @param api_key [String, nil] falls back to CALLISTO_API_KEY
    # @param base_url [String, nil] falls back to CALLISTO_BASE_URL, then the default
    # @param timeout [Float] request timeout in seconds
    # @raise [ValidationError] if client_id or api_key cannot be resolved
    def initialize(client_id: nil, api_key: nil, base_url: nil, timeout: DEFAULT_TIMEOUT)
      client_id ||= ENV["CALLISTO_CLIENT_ID"]
      api_key ||= ENV["CALLISTO_API_KEY"]

      if client_id.nil? || client_id.empty? || api_key.nil? || api_key.empty?
        raise ValidationError.new(
          "Callisto: client_id and api_key are required " \
          "(pass arguments or set CALLISTO_CLIENT_ID / CALLISTO_API_KEY)."
        )
      end

      base_url = (base_url || ENV["CALLISTO_BASE_URL"] || DEFAULT_BASE_URL).sub(%r{/+\z}, "")

      @client_id = client_id
      @api_key = api_key
      @base_url = base_url
      @timeout = timeout
    end
  end
end
