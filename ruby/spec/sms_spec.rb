# frozen_string_literal: true

RSpec.describe Callisto::SmsResource do
  let(:client) { build_client }

  describe "#send" do
    it "POSTs /sms/send with a single recipient and decodes SendSmsResult" do
      stub = stub_request(:post, "#{SpecHelpers::BASE_URL}/sms/send")
             .with(
               headers: { "Authorization" => basic_auth_header, "Content-Type" => "application/json" },
               body: { "sender" => "Acme", "to" => "+2250700000000", "message" => "Hi" }
             )
             .to_return(status: 200, body: {
               total_amount: 10.0, available_credit: 90.0, status: "sent",
               recipient_count: 1, scheduled: false, messages: [{ id: "m1" }]
             }.to_json)

      result = client.sms.send(sender: "Acme", to: "+2250700000000", message: "Hi")

      expect(stub).to have_been_requested
      expect(result).to be_a(Callisto::SendSmsResult)
      expect(result.status).to eq("sent")
      expect(result.recipient_count).to eq(1)
      expect(result.scheduled).to be(false)
      expect(result.messages).to eq([{ "id" => "m1" }])
    end

    it "accepts an array of recipients and optional notify_url / scheduled_at" do
      stub = stub_request(:post, "#{SpecHelpers::BASE_URL}/sms/send")
             .with(body: {
               "sender" => "Acme",
               "to" => ["+2250700000000", "+2250700000001"],
               "message" => "Sale!",
               "notify_url" => "https://example.com/hook",
               "scheduled_at" => "2026-06-02 10:00:00"
             })
             .to_return(status: 200, body: {
               total_amount: 20.0, available_credit: 80.0, status: "scheduled",
               recipient_count: 2, scheduled: true, messages: []
             }.to_json)

      client.sms.send(
        sender: "Acme",
        to: ["+2250700000000", "+2250700000001"],
        message: "Sale!",
        notify_url: "https://example.com/hook",
        scheduled_at: "2026-06-02 10:00:00"
      )
      expect(stub).to have_been_requested
    end

    it "omits notify_url and scheduled_at when nil" do
      stub = stub_request(:post, "#{SpecHelpers::BASE_URL}/sms/send")
             .with { |req| !JSON.parse(req.body).key?("notify_url") && !JSON.parse(req.body).key?("scheduled_at") }
             .to_return(status: 200, body: {
               total_amount: 1, available_credit: 1, status: "sent",
               recipient_count: 1, scheduled: false, messages: []
             }.to_json)

      client.sms.send(sender: "Acme", to: "+225", message: "Hi")
      expect(stub).to have_been_requested
    end
  end

  describe "#list" do
    it "GETs /sms/messages with query params and decodes Paginated<SmsMessage>" do
      stub = stub_request(:get, "#{SpecHelpers::BASE_URL}/sms/messages")
             .with(query: { "page" => "1", "per_page" => "50" })
             .to_return(status: 200, body: {
               items: [{ id: "m1", status: "delivered", recipient: "+225" }],
               total: 1, per_page: 50, current_page: 1,
               next: nil, previous: nil, total_pages: 1
             }.to_json)

      page = client.sms.list(page: 1, per_page: 50)

      expect(stub).to have_been_requested
      expect(page).to be_a(Callisto::Paginated)
      expect(page.items.first).to be_a(Callisto::SmsMessage)
      expect(page.items.first.id).to eq("m1")
      expect(page.total).to eq(1)
      expect(page.current_page).to eq(1)
      expect(page.next).to be_nil
    end

    it "drops nil query params" do
      stub = stub_request(:get, "#{SpecHelpers::BASE_URL}/sms/messages")
             .with(query: { "page" => "2" })
             .to_return(status: 200, body: { items: [], total: 0 }.to_json)

      client.sms.list(page: 2)
      expect(stub).to have_been_requested
    end
  end

  describe "#get_status" do
    it "GETs /sms/{id} and decodes SmsMessage" do
      stub = stub_request(:get, "#{SpecHelpers::BASE_URL}/sms/abc")
             .to_return(status: 200, body: { id: "abc", status: "sent" }.to_json)

      msg = client.sms.get_status("abc")
      expect(stub).to have_been_requested
      expect(msg.id).to eq("abc")
      expect(msg.status).to eq("sent")
    end
  end
end
