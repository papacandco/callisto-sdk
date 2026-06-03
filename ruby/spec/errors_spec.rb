# frozen_string_literal: true

RSpec.describe "error mapping" do
  let(:client) { build_client }

  def stub_balance_error(status:, body:, headers: {})
    stub_request(:get, "#{SpecHelpers::BASE_URL}/sms/balance")
      .with(query: hash_including({}))
      .to_return(status: status, body: body, headers: headers)
  end

  it "maps 401 to AuthenticationError with message from body" do
    stub_balance_error(status: 401, body: { message: "bad credentials" }.to_json)
    expect { client.balance.get }.to raise_error(Callisto::AuthenticationError) do |e|
      expect(e.status_code).to eq(401)
      expect(e.message).to eq("bad credentials")
      expect(e.body).to eq({ "message" => "bad credentials" })
    end
  end

  it "maps 400 to ValidationError" do
    stub_balance_error(status: 400, body: { message: "bad request" }.to_json)
    expect { client.balance.get }.to raise_error(Callisto::ValidationError) do |e|
      expect(e.status_code).to eq(400)
    end
  end

  it "maps 422 to ValidationError" do
    stub_balance_error(status: 422, body: { message: "unprocessable" }.to_json)
    expect { client.balance.get }.to raise_error(Callisto::ValidationError)
  end

  it "maps 404 to NotFoundError" do
    stub_balance_error(status: 404, body: { message: "not found" }.to_json)
    expect { client.balance.get }.to raise_error(Callisto::NotFoundError)
  end

  it "maps 429 to RateLimitError and parses Retry-After" do
    stub_balance_error(status: 429, body: { message: "slow down" }.to_json,
                       headers: { "Retry-After" => "30" })
    expect { client.balance.get }.to raise_error(Callisto::RateLimitError) do |e|
      expect(e.status_code).to eq(429)
      expect(e.retry_after).to eq(30)
    end
  end

  it "leaves retry_after nil when Retry-After is absent or non-numeric" do
    stub_balance_error(status: 429, body: { message: "slow down" }.to_json,
                       headers: { "Retry-After" => "soon" })
    expect { client.balance.get }.to raise_error(Callisto::RateLimitError) do |e|
      expect(e.retry_after).to be_nil
    end
  end

  it "maps other non-2xx to ApiError" do
    stub_balance_error(status: 500, body: { message: "boom" }.to_json)
    expect { client.balance.get }.to raise_error(Callisto::ApiError) do |e|
      expect(e.status_code).to eq(500)
    end
  end

  it "falls back to 'HTTP <status>' when the body has no message" do
    stub_balance_error(status: 503, body: "service unavailable")
    expect { client.balance.get }.to raise_error(Callisto::ApiError) do |e|
      expect(e.message).to eq("HTTP 503")
      expect(e.body).to eq("service unavailable")
    end
  end

  describe "error_from_status helper" do
    it "returns the right class per status" do
      expect(Callisto.error_from_status(401, "m")).to be_a(Callisto::AuthenticationError)
      expect(Callisto.error_from_status(400, "m")).to be_a(Callisto::ValidationError)
      expect(Callisto.error_from_status(422, "m")).to be_a(Callisto::ValidationError)
      expect(Callisto.error_from_status(404, "m")).to be_a(Callisto::NotFoundError)
      expect(Callisto.error_from_status(429, "m", nil, 12).retry_after).to eq(12)
      expect(Callisto.error_from_status(418, "m")).to be_a(Callisto::ApiError)
    end
  end

  describe "configuration validation" do
    it "raises ValidationError when credentials are missing" do
      saved = ENV.to_hash.slice("CALLISTO_CLIENT_ID", "CALLISTO_API_KEY")
      ENV.delete("CALLISTO_CLIENT_ID")
      ENV.delete("CALLISTO_API_KEY")
      begin
        expect { Callisto::Client.new(base_url: SpecHelpers::BASE_URL) }
          .to raise_error(Callisto::ValidationError, /client_id and api_key are required/)
      ensure
        saved.each { |k, v| ENV[k] = v }
      end
    end
  end
end
