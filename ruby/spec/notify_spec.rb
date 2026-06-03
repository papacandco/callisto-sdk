# frozen_string_literal: true

RSpec.describe Callisto::NotifyResource do
  let(:client) { build_client }

  it "POSTs /notify/send with snake_case keys and only present blocks" do
    stub = stub_request(:post, "#{SpecHelpers::BASE_URL}/notify/send")
           .with(body: {
             "topic" => "welcome",
             "sms" => [{ "to" => "+2250700000000" }],
             "email" => [{ "to" => "user@example.com" }]
           })
           .to_return(status: 200, body: {
             status: "queued", topic: "welcome",
             queued_events: 2, topic_messages: []
           }.to_json)

    result = client.notify.send(
      topic: "welcome",
      sms: [{ "to" => "+2250700000000" }],
      email: [{ "to" => "user@example.com" }]
    )

    expect(stub).to have_been_requested
    expect(result).to be_a(Callisto::NotifyResult)
    expect(result.status).to eq("queued")
    expect(result.queued_events).to eq(2)
  end

  it "includes real_time and mobile_push with snake_case keys" do
    stub = stub_request(:post, "#{SpecHelpers::BASE_URL}/notify/send")
           .with { |req|
             b = JSON.parse(req.body)
             b.key?("mobile_push") && b.key?("real_time") && !b.key?("email")
           }
           .to_return(status: 200, body: { status: "queued", topic: "t",
                                            queued_events: 0, topic_messages: [] }.to_json)

    client.notify.send(topic: "t", mobile_push: [{ "token" => "abc" }], real_time: [{ "ch" => "x" }])
    expect(stub).to have_been_requested
  end

  it "raises ValidationError client-side when no event block is provided" do
    expect do
      client.notify.send(topic: "welcome")
    end.to raise_error(Callisto::ValidationError, /At least one event block/)
    expect(a_request(:post, "#{SpecHelpers::BASE_URL}/notify/send")).not_to have_been_made
  end

  it "treats empty arrays as absent blocks" do
    expect do
      client.notify.send(topic: "welcome", sms: [])
    end.to raise_error(Callisto::ValidationError)
  end
end
