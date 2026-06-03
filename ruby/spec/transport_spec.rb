# frozen_string_literal: true

RSpec.describe Callisto::Transport do
  let(:client) { build_client }

  it "sends the Basic auth header and Accept: application/json" do
    stub = stub_request(:get, "#{SpecHelpers::BASE_URL}/sms/balance")
           .with(query: hash_including({}),
                 headers: { "Authorization" => basic_auth_header, "Accept" => "application/json" })
           .to_return(status: 200, body: { credit: 1, currency: "XOF" }.to_json)

    client.balance.get
    expect(stub).to have_been_requested
  end

  it "drops nil query params and omits the query string when all are nil" do
    stub = stub_request(:get, "#{SpecHelpers::BASE_URL}/otps")
           .to_return(status: 200, body: { items: [] }.to_json)

    client.otp.list
    expect(stub).to have_been_requested
    # No query string should have been appended.
    expect(a_request(:get, "#{SpecHelpers::BASE_URL}/otps").with(query: {})).to have_been_made
  end

  it "serializes the request body as JSON with Content-Type" do
    stub = stub_request(:post, "#{SpecHelpers::BASE_URL}/sms/send")
           .with(headers: { "Content-Type" => "application/json" })
           .to_return(status: 200, body: {
             total_amount: 1, available_credit: 1, status: "sent",
             recipient_count: 1, scheduled: false, messages: []
           }.to_json)

    client.sms.send(sender: "A", to: "+225", message: "Hi")
    expect(stub).to have_been_requested
  end

  it "returns nil for an empty 2xx body" do
    stub_request(:get, "#{SpecHelpers::BASE_URL}/whatsapp/inst_1/qr")
      .to_return(status: 200, body: "")
    expect(client.whatsapp.get_qr("inst_1")).to be_nil
  end

  it "raises NetworkError on transport failure with status_code 0" do
    stub_request(:get, "#{SpecHelpers::BASE_URL}/sms/balance")
      .with(query: hash_including({}))
      .to_raise(Errno::ECONNREFUSED)

    expect { client.balance.get }.to raise_error(Callisto::NetworkError) do |err|
      expect(err.status_code).to eq(0)
      expect(err.message).to match(/failed/)
    end
  end
end
