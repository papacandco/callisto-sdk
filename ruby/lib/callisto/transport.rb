# frozen_string_literal: true

require "net/http"
require "uri"
require "json"

module Callisto
  # HTTP transport over the standard library Net::HTTP. Applies Basic auth, drops nil
  # query params and body fields, serializes JSON, and maps non-2xx responses to typed errors.
  class Transport
    # @param config [Config]
    def initialize(config)
      @config = config
    end

    # Performs an HTTP request and returns the decoded response body.
    #
    # @param method [String] HTTP verb ("GET", "POST", ...)
    # @param path [String] path appended to the base URL (e.g. "/sms/send")
    # @param body [Object, nil] request body, serialized as JSON when present
    # @param query [Hash, nil] query parameters; nil values are dropped
    # @return [Object, nil] decoded JSON (Hash/Array), raw String, or nil
    # @raise [CallistoError] on a non-2xx response or transport failure
    def request(method, path, body: nil, query: nil)
      uri = URI.parse(@config.base_url + path)
      params = (query || {}).reject { |_, v| v.nil? }
      uri.query = URI.encode_www_form(params) unless params.empty?

      req = build_request(method, uri, body)

      response = perform(uri, req)

      data = decode_body(response)

      if error_response?(response)
        status = response.code.to_i
        message =
          if data.is_a?(Hash) && (data.key?("message") || data.key?(:message))
            data["message"] || data[:message]
          else
            "HTTP #{status}"
          end
        retry_after = nil
        if status == 429
          raw = response["Retry-After"]
          retry_after = Integer(raw, 10) rescue nil unless raw.nil?
        end
        raise Callisto.error_from_status(status, message.to_s, data, retry_after)
      end

      data
    end

    # Net::HTTP holds no persistent connection in this design; provided for API symmetry.
    def close
      nil
    end

    private

    def build_request(method, uri, body)
      klass = case method.to_s.upcase
              when "GET" then Net::HTTP::Get
              when "POST" then Net::HTTP::Post
              when "PUT" then Net::HTTP::Put
              when "PATCH" then Net::HTTP::Patch
              when "DELETE" then Net::HTTP::Delete
              else raise ArgumentError, "Unsupported HTTP method: #{method}"
              end

      req = klass.new(uri)
      req["Accept"] = "application/json"
      credentials = "#{@config.client_id}:#{@config.api_key}"
      req["Authorization"] = "Basic " + [credentials].pack("m0")

      unless body.nil?
        req["Content-Type"] = "application/json"
        req.body = JSON.generate(body)
      end

      req
    end

    def perform(uri, req)
      http = Net::HTTP.new(uri.host, uri.port)
      http.use_ssl = uri.scheme == "https"
      http.open_timeout = @config.timeout
      http.read_timeout = @config.timeout
      http.write_timeout = @config.timeout if http.respond_to?(:write_timeout=)
      http.request(req)
    rescue StandardError => e
      raise NetworkError.new("Request to #{uri} failed: #{e.message}")
    end

    def decode_body(response)
      raw = response.body
      return nil if raw.nil? || raw.empty?

      begin
        JSON.parse(raw)
      rescue JSON::ParserError
        raw
      end
    end

    def error_response?(response)
      response.code.to_i >= 400
    end
  end
end
