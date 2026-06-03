# frozen_string_literal: true

require "callisto"
require "webmock/rspec"
require "json"

WebMock.disable_net_connect!

RSpec.configure do |config|
  config.expect_with :rspec do |c|
    c.syntax = :expect
  end

  config.mock_with :rspec do |c|
    c.verify_partial_doubles = true
  end

  config.order = :random
  Kernel.srand config.seed
end

module SpecHelpers
  CLIENT_ID = "test-client"
  API_KEY = "test-key"
  BASE_URL = "https://api.callistosignal.com/v1"

  def build_client
    Callisto::Client.new(client_id: CLIENT_ID, api_key: API_KEY, base_url: BASE_URL)
  end

  # The Authorization header value the SDK is expected to send.
  def basic_auth_header
    "Basic " + ["#{CLIENT_ID}:#{API_KEY}"].pack("m0")
  end
end

RSpec.configure do |config|
  config.include SpecHelpers
end
