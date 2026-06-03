# frozen_string_literal: true

RSpec.describe Callisto::ErrorReporter do
  DSN = "https://app.callistosignal.com/ingest/abc-123?key=deadbeef"

  # A fake sender that records every (url, payload) pair and returns a configurable status.
  class FakeSender
    attr_reader :calls

    def initialize(status: 202)
      @status = status
      @calls = []
    end

    def call(url, payload)
      @calls << { url: url, payload: payload }
      @status.is_a?(Proc) ? @status.call : @status
    end
  end

  SDK = { name: "callisto-sdk", version: Callisto::VERSION, language: "ruby" }.freeze

  def build_reporter(sender:, dsn: DSN, environment: nil)
    Callisto::ErrorReporter.new(dsn: dsn, sdk: SDK, environment: environment, sender: sender)
  end

  describe "captureException" do
    it "POSTs a CallistoError to the DSN with message/type/level" do
      sender = FakeSender.new
      reporter = build_reporter(sender: sender)

      err = Callisto::AuthenticationError.new("invalid credentials", 401, { "error" => "nope" })
      reporter.capture_exception(err)
      reporter.flush

      expect(sender.calls.size).to eq(1)
      call = sender.calls.first
      expect(call[:url]).to eq(DSN)
      payload = call[:payload]
      expect(payload[:message]).to eq("invalid credentials")
      expect(payload[:type]).to eq("Callisto::AuthenticationError")
      expect(payload[:level]).to eq("error")
    ensure
      reporter.close
    end

    it "includes context.sdk and (for transport errors) status_code + request.{method,path}" do
      sender = FakeSender.new
      reporter = build_reporter(sender: sender, environment: "production")

      err = Callisto::NotFoundError.new("not found", 404, { "error" => "missing" })
      err.instance_variable_set(:@callisto_method, "POST")
      err.instance_variable_set(:@callisto_path, "/sms/send")
      reporter.capture_exception(err)
      reporter.flush

      payload = sender.calls.first[:payload]
      expect(payload[:context][:sdk]).to eq(SDK)
      expect(payload[:context][:environment]).to eq("production")
      expect(payload[:context][:status_code]).to eq(404)
      expect(payload[:context][:body]).to eq({ "error" => "missing" })
      expect(payload[:culprit]).to eq("POST /sms/send")
      expect(payload[:request]).to eq({ method: "POST", path: "/sms/send" })
    ensure
      reporter.close
    end

    it "includes retry_after for rate-limit errors" do
      sender = FakeSender.new
      reporter = build_reporter(sender: sender)

      err = Callisto::RateLimitError.new("slow down", 429, nil, 30)
      reporter.capture_exception(err)
      reporter.flush

      expect(sender.calls.first[:payload][:context][:retry_after]).to eq(30)
    ensure
      reporter.close
    end

    it "extracts a stacktrace and a culprit from the backtrace when not a transport error" do
      sender = FakeSender.new
      reporter = build_reporter(sender: sender)

      begin
        raise Callisto::ValidationError.new("bad input")
      rescue Callisto::ValidationError => e
        reporter.capture_exception(e)
      end
      reporter.flush

      payload = sender.calls.first[:payload]
      expect(payload[:stacktrace]).to be_an(Array)
      expect(payload[:stacktrace].first).to include(:function, :file, :line)
      expect(payload[:culprit]).to be_a(String)
      expect(payload[:request]).to be_nil
    ensure
      reporter.close
    end

    it "constrains the level to fatal|error|warning|info" do
      sender = FakeSender.new
      reporter = build_reporter(sender: sender)

      reporter.capture_exception(StandardError.new("x"), level: "bogus")
      reporter.flush
      expect(sender.calls.first[:payload][:level]).to eq("error")
    ensure
      reporter.close
    end
  end

  describe "PII / secrets" do
    it "never transmits credentials, the auth header, or the outgoing request body" do
      sender = FakeSender.new
      reporter = build_reporter(sender: sender)

      err = Callisto::AuthenticationError.new("invalid credentials", 401, { "error" => "nope" })
      err.instance_variable_set(:@callisto_method, "POST")
      err.instance_variable_set(:@callisto_path, "/sms/send")
      reporter.capture_exception(err)
      reporter.flush

      blob = JSON.generate(sender.calls.first[:payload])
      expect(blob).not_to include("test-client")
      expect(blob).not_to include("test-key")
      expect(blob.downcase).not_to include("authorization")
      expect(blob).not_to include("client_id")
      expect(blob).not_to include("api_key")
      # No outgoing request body fields (phone numbers / message content).
      expect(blob).not_to include("+225")
      expect(blob).not_to include("instanceCode")
    ensure
      reporter.close
    end
  end

  describe "failure isolation" do
    it "swallows a sender that raises and never raises from capture_exception" do
      raising = Object.new
      def raising.call(*) = raise "boom"
      reporter = build_reporter(sender: raising)

      expect { reporter.capture_exception(StandardError.new("x")); reporter.flush }.not_to raise_error
    ensure
      reporter.close
    end

    it "swallows a non-202 status response" do
      sender = FakeSender.new(status: 401)
      reporter = build_reporter(sender: sender)

      expect { reporter.capture_exception(StandardError.new("x")); reporter.flush }.not_to raise_error
      expect(sender.calls.size).to eq(1)
    ensure
      reporter.close
    end
  end

  describe "no DSN" do
    it "is a no-op and sends nothing" do
      sender = FakeSender.new
      reporter = build_reporter(sender: sender, dsn: nil)

      expect(reporter.enabled?).to be(false)
      reporter.capture_exception(StandardError.new("x"))
      reporter.capture_message("hi")
      reporter.flush
      reporter.close
      expect(sender.calls).to be_empty
    end

    it "is a no-op for an invalid DSN" do
      sender = FakeSender.new
      reporter = build_reporter(sender: sender, dsn: "not a url")

      expect(reporter.enabled?).to be(false)
      reporter.capture_exception(StandardError.new("x"))
      reporter.flush
      expect(sender.calls).to be_empty
    end
  end

  describe "captureMessage and setUser" do
    it "captures a plain message at the supplied level" do
      sender = FakeSender.new
      reporter = build_reporter(sender: sender)

      reporter.capture_message("just so you know", level: "warning")
      reporter.flush

      payload = sender.calls.first[:payload]
      expect(payload[:message]).to eq("just so you know")
      expect(payload[:level]).to eq("warning")
      expect(payload[:type]).to eq("message")
    ensure
      reporter.close
    end

    it "attaches the user set via set_user to subsequent events" do
      sender = FakeSender.new
      reporter = build_reporter(sender: sender)

      reporter.set_user({ id: "u1", email: "u@example.com" })
      reporter.capture_message("hi")
      reporter.flush

      expect(sender.calls.first[:payload][:user]).to eq({ id: "u1", email: "u@example.com" })
    ensure
      reporter.close
    end
  end
end

RSpec.describe "Client error-reporting integration" do
  DSN2 = "https://app.callistosignal.com/ingest/abc-123?key=deadbeef"

  it "captures transport errors and still propagates the original error" do
    sender = Class.new do
      attr_reader :calls
      def initialize = @calls = []
      def call(url, payload) = (@calls << { url: url, payload: payload }) && 202
    end.new

    reporter = Callisto::ErrorReporter.new(
      dsn: DSN2,
      sdk: { name: "callisto-sdk", version: Callisto::VERSION, language: "ruby" },
      sender: sender
    )
    client = Callisto::Client.new(
      client_id: SpecHelpers::CLIENT_ID, api_key: SpecHelpers::API_KEY,
      base_url: SpecHelpers::BASE_URL, error_reporter: reporter
    )

    stub_request(:get, "#{SpecHelpers::BASE_URL}/sms/balance")
      .with(query: hash_including({}))
      .to_return(status: 401, body: { message: "invalid_key" }.to_json)

    expect { client.balance.get }.to raise_error(Callisto::AuthenticationError)
    reporter.flush

    expect(sender.calls.size).to eq(1)
    payload = sender.calls.first[:payload]
    expect(payload[:type]).to eq("Callisto::AuthenticationError")
    expect(payload[:context][:status_code]).to eq(401)
    expect(payload[:request]).to eq({ method: "GET", path: "/sms/balance" })
  ensure
    client&.close
  end

  it "captures a client-side validation error from notify.send before raising" do
    sender = Class.new do
      attr_reader :calls
      def initialize = @calls = []
      def call(_url, payload) = (@calls << payload) && 202
    end.new

    reporter = Callisto::ErrorReporter.new(
      dsn: DSN2,
      sdk: { name: "callisto-sdk", version: Callisto::VERSION, language: "ruby" },
      sender: sender
    )
    client = Callisto::Client.new(
      client_id: SpecHelpers::CLIENT_ID, api_key: SpecHelpers::API_KEY,
      base_url: SpecHelpers::BASE_URL, error_reporter: reporter
    )

    expect { client.notify.send(topic: "t") }.to raise_error(Callisto::ValidationError)
    reporter.flush

    expect(sender.calls.size).to eq(1)
    expect(sender.calls.first[:type]).to eq("Callisto::ValidationError")
  ensure
    client&.close
  end

  it "exposes capture_exception / capture_message / set_user on the client" do
    sender = Class.new do
      attr_reader :calls
      def initialize = @calls = []
      def call(_url, payload) = (@calls << payload) && 202
    end.new

    reporter = Callisto::ErrorReporter.new(
      dsn: DSN2,
      sdk: { name: "callisto-sdk", version: Callisto::VERSION, language: "ruby" },
      sender: sender
    )
    client = Callisto::Client.new(
      client_id: SpecHelpers::CLIENT_ID, api_key: SpecHelpers::API_KEY,
      base_url: SpecHelpers::BASE_URL, error_reporter: reporter
    )

    client.set_user({ id: "u9" })
    client.capture_message("hello")
    client.capture_exception(StandardError.new("boom"), level: "warning")
    reporter.flush

    expect(sender.calls.size).to eq(2)
    expect(sender.calls.map { |p| p[:user] }).to all(eq({ id: "u9" }))
  ensure
    client&.close
  end

  it "behaves exactly as before with no DSN and still propagates errors" do
    client = Callisto::Client.new(
      client_id: SpecHelpers::CLIENT_ID, api_key: SpecHelpers::API_KEY,
      base_url: SpecHelpers::BASE_URL
    )
    expect(client.error_reporter.enabled?).to be(false)

    stub_request(:get, "#{SpecHelpers::BASE_URL}/sms/balance")
      .with(query: hash_including({}))
      .to_return(status: 401, body: { message: "invalid_key" }.to_json)
    expect { client.balance.get }.to raise_error(Callisto::AuthenticationError)
  ensure
    client&.close
  end
end
