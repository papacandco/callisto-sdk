# frozen_string_literal: true

RSpec.describe Callisto::Client do
  around do |example|
    saved = ENV.to_hash.slice("CALLISTO_CLIENT_ID", "CALLISTO_API_KEY", "CALLISTO_BASE_URL")
    %w[CALLISTO_CLIENT_ID CALLISTO_API_KEY CALLISTO_BASE_URL].each { |k| ENV.delete(k) }
    example.run
    %w[CALLISTO_CLIENT_ID CALLISTO_API_KEY CALLISTO_BASE_URL].each { |k| ENV.delete(k) }
    saved.each { |k, v| ENV[k] = v }
  end

  it "resolves credentials and base_url from environment variables" do
    ENV["CALLISTO_CLIENT_ID"] = "env-id"
    ENV["CALLISTO_API_KEY"] = "env-key"
    ENV["CALLISTO_BASE_URL"] = "https://env.example.com/v1/"

    stub = stub_request(:get, "https://env.example.com/v1/sms/balance")
           .with(query: hash_including({}),
                 headers: { "Authorization" => "Basic " + ["env-id:env-key"].pack("m0") })
           .to_return(status: 200, body: { credit: 1, currency: "XOF" }.to_json)

    client = Callisto::Client.new
    client.balance.get
    expect(stub).to have_been_requested
  end

  it "trims a trailing slash from base_url" do
    client = Callisto::Client.new(client_id: "i", api_key: "k", base_url: "https://x.test/v1/")
    stub = stub_request(:get, "https://x.test/v1/sms/balance")
           .with(query: hash_including({}))
           .to_return(status: 200, body: { credit: 1, currency: "XOF" }.to_json)
    client.balance.get
    expect(stub).to have_been_requested
  end

  it "exposes all five resources" do
    client = Callisto::Client.new(client_id: "i", api_key: "k", base_url: SpecHelpers::BASE_URL)
    expect(client.balance).to be_a(Callisto::BalanceResource)
    expect(client.sms).to be_a(Callisto::SmsResource)
    expect(client.otp).to be_a(Callisto::OtpResource)
    expect(client.whatsapp).to be_a(Callisto::WhatsAppResource)
    expect(client.notify).to be_a(Callisto::NotifyResource)
  end

  it "supports the block form and auto-closes the transport" do
    fake_transport = instance_double(Callisto::Transport)
    allow(fake_transport).to receive(:close)

    yielded = nil
    returned = Callisto::Client.new(
      client_id: "i", api_key: "k", base_url: SpecHelpers::BASE_URL, transport: fake_transport
    ) { |c| yielded = c }

    expect(yielded).to be_a(Callisto::Client)
    expect(returned).to be_a(Callisto::Client)
    expect(fake_transport).to have_received(:close)
  end

  it "#close delegates to the transport" do
    fake_transport = instance_double(Callisto::Transport)
    allow(fake_transport).to receive(:close)
    client = Callisto::Client.new(
      client_id: "i", api_key: "k", base_url: SpecHelpers::BASE_URL, transport: fake_transport
    )
    client.close
    expect(fake_transport).to have_received(:close)
  end
end
