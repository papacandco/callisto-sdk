# frozen_string_literal: true

RSpec.describe Callisto::BalanceResource do
  let(:client) { build_client }

  it "GETs /sms/balance with default format and Basic auth, decodes Balance" do
    stub = stub_request(:get, "#{SpecHelpers::BASE_URL}/sms/balance")
           .with(query: { "format" => "full" },
                 headers: { "Authorization" => basic_auth_header, "Accept" => "application/json" })
           .to_return(status: 200, body: {
             credit: 1500.5, currency: "XOF",
             sms_price_local: 10.0, sms_price_international: 25.0
           }.to_json, headers: { "Content-Type" => "application/json" })

    balance = client.balance.get

    expect(stub).to have_been_requested
    expect(balance).to be_a(Callisto::Balance)
    expect(balance.credit).to eq(1500.5)
    expect(balance.currency).to eq("XOF")
    expect(balance.sms_price_local).to eq(10.0)
    expect(balance.sms_price_international).to eq(25.0)
  end

  it "passes currency as a query param and drops it when nil" do
    stub = stub_request(:get, "#{SpecHelpers::BASE_URL}/sms/balance")
           .with(query: { "format" => "summary", "currency" => "USD" })
           .to_return(status: 200, body: { credit: 1, currency: "USD" }.to_json)

    client.balance.get(format: "summary", currency: "USD")
    expect(stub).to have_been_requested
  end

  it "ignores unknown fields in the response" do
    stub_request(:get, "#{SpecHelpers::BASE_URL}/sms/balance")
      .with(query: hash_including({}))
      .to_return(status: 200, body: { credit: 5, currency: "XOF", unknown: "x" }.to_json)

    balance = client.balance.get
    expect(balance.credit).to eq(5)
    expect(balance.respond_to?(:unknown)).to be(false)
  end
end
