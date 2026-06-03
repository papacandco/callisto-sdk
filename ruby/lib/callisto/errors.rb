# frozen_string_literal: true

module Callisto
  # Base class for all SDK errors. Carries the human-readable +message+, the HTTP
  # +status_code+ (0 for transport-level failures), and the decoded response +body+.
  class CallistoError < StandardError
    attr_reader :status_code, :body

    def initialize(message, status_code = 0, body = nil)
      super(message)
      @status_code = status_code
      @body = body
    end

    # Alias matching the cross-language SDK surface; +message+ is provided by StandardError.
  end

  # HTTP 401 — invalid credentials.
  class AuthenticationError < CallistoError; end

  # HTTP 400 / 422 — invalid request. Also raised client-side before a request is made.
  class ValidationError < CallistoError; end

  # HTTP 404 — resource not found.
  class NotFoundError < CallistoError; end

  # HTTP 429 — rate limited. Carries +retry_after+ (seconds) parsed from the Retry-After header.
  class RateLimitError < CallistoError
    attr_reader :retry_after

    def initialize(message, status_code = 0, body = nil, retry_after = nil)
      super(message, status_code, body)
      @retry_after = retry_after
    end
  end

  # Any other non-2xx HTTP status.
  class ApiError < CallistoError; end

  # Transport-level failure (connection error, timeout, DNS, etc.). +status_code+ is 0.
  class NetworkError < CallistoError; end

  # Maps an HTTP status code to the appropriate typed error.
  #
  # @param status [Integer] HTTP status code
  # @param message [String] error message
  # @param body [Object, nil] decoded response body
  # @param retry_after [Integer, nil] seconds parsed from Retry-After (429 only)
  # @return [CallistoError]
  def self.error_from_status(status, message, body = nil, retry_after = nil)
    case status
    when 401
      AuthenticationError.new(message, status, body)
    when 400, 422
      ValidationError.new(message, status, body)
    when 404
      NotFoundError.new(message, status, body)
    when 429
      RateLimitError.new(message, status, body, retry_after)
    else
      ApiError.new(message, status, body)
    end
  end
end
