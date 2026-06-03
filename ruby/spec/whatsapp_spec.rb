# frozen_string_literal: true

RSpec.describe Callisto::WhatsAppResource do
  let(:client) { build_client }

  describe "#create_instance" do
    it "POSTs /whatsapp/instances dropping nil fields" do
      stub = stub_request(:post, "#{SpecHelpers::BASE_URL}/whatsapp/instances")
             .with(body: { "name" => "Main", "phone_number" => "+225" })
             .to_return(status: 200, body: { id: "i1", code: "inst_1", name: "Main",
                                             status: "pending" }.to_json)

      inst = client.whatsapp.create_instance(name: "Main", phone_number: "+225")
      expect(stub).to have_been_requested
      expect(inst).to be_a(Callisto::WhatsAppInstance)
      expect(inst.code).to eq("inst_1")
    end
  end

  describe "#list_instances" do
    it "GETs /whatsapp/instances with default page=1" do
      stub = stub_request(:get, "#{SpecHelpers::BASE_URL}/whatsapp/instances")
             .with(query: { "page" => "1" })
             .to_return(status: 200, body: {
               items: [{ id: "i1", code: "inst_1" }], total: 1, per_page: 20,
               current_page: 1, next: nil, previous: nil, total_pages: 1
             }.to_json)

      page = client.whatsapp.list_instances
      expect(stub).to have_been_requested
      expect(page.items.first).to be_a(Callisto::WhatsAppInstance)
    end
  end

  describe "#get_instance" do
    it "GETs /whatsapp/{code}" do
      stub = stub_request(:get, "#{SpecHelpers::BASE_URL}/whatsapp/inst_1")
             .to_return(status: 200, body: { id: "i1", code: "inst_1" }.to_json)
      inst = client.whatsapp.get_instance("inst_1")
      expect(stub).to have_been_requested
      expect(inst.code).to eq("inst_1")
    end
  end

  describe "#get_qr" do
    it "GETs /whatsapp/{code}/qr and returns the raw payload" do
      stub = stub_request(:get, "#{SpecHelpers::BASE_URL}/whatsapp/inst_1/qr")
             .to_return(status: 200, body: { qr_code: "data:image/png;base64,AAA" }.to_json)
      qr = client.whatsapp.get_qr("inst_1")
      expect(stub).to have_been_requested
      expect(qr).to eq({ "qr_code" => "data:image/png;base64,AAA" })
    end
  end

  describe "#get_status" do
    it "GETs /whatsapp/{code}/status and returns the raw payload" do
      stub = stub_request(:get, "#{SpecHelpers::BASE_URL}/whatsapp/inst_1/status")
             .to_return(status: 200, body: { status: "connected" }.to_json)
      status = client.whatsapp.get_status("inst_1")
      expect(stub).to have_been_requested
      expect(status).to eq({ "status" => "connected" })
    end
  end

  describe "#list_messages" do
    it "GETs /whatsapp/{code}/messages with query params" do
      stub = stub_request(:get, "#{SpecHelpers::BASE_URL}/whatsapp/inst_1/messages")
             .with(query: { "page" => "1" })
             .to_return(status: 200, body: {
               items: [{ id: "m1", status: "sent" }], total: 1, per_page: 20,
               current_page: 1, next: nil, previous: nil, total_pages: 1
             }.to_json)

      page = client.whatsapp.list_messages("inst_1", page: 1)
      expect(stub).to have_been_requested
      expect(page.items.first).to be_a(Callisto::WhatsAppMessage)
    end
  end

  describe "#get_message" do
    it "GETs /whatsapp/messages/{id}" do
      stub = stub_request(:get, "#{SpecHelpers::BASE_URL}/whatsapp/messages/m1")
             .to_return(status: 200, body: { id: "m1", status: "delivered", cost: 0.5 }.to_json)
      msg = client.whatsapp.get_message("m1")
      expect(stub).to have_been_requested
      expect(msg.id).to eq("m1")
      expect(msg.cost).to eq(0.5)
    end
  end

  describe "#send_text" do
    it "POSTs /whatsapp/{code}/send/text" do
      stub = stub_request(:post, "#{SpecHelpers::BASE_URL}/whatsapp/inst_1/send/text")
             .with(body: { "to" => "+225", "message" => "Hi!" })
             .to_return(status: 200, body: {
               id: "w1", instance_id: "i1", recipient: "+225",
               message_type: "text", status: "sent", scheduled: false
             }.to_json)

      result = client.whatsapp.send_text("inst_1", to: "+225", message: "Hi!")
      expect(stub).to have_been_requested
      expect(result).to be_a(Callisto::SendWaResult)
      expect(result.message_type).to eq("text")
    end
  end

  describe "#send_media" do
    it "POSTs /whatsapp/{code}/send/media with enum type as string" do
      stub = stub_request(:post, "#{SpecHelpers::BASE_URL}/whatsapp/inst_1/send/media")
             .with(body: {
               "to" => "+225", "type" => "image",
               "media_url" => "https://x/p.jpg", "caption" => "Promo"
             })
             .to_return(status: 200, body: {
               id: "w2", instance_id: "i1", recipient: "+225",
               message_type: "media", status: "sent", scheduled: false,
               media_url: "https://x/p.jpg"
             }.to_json)

      result = client.whatsapp.send_media(
        "inst_1", to: "+225", type: Callisto::WhatsAppMediaType::IMAGE,
        media_url: "https://x/p.jpg", caption: "Promo"
      )
      expect(stub).to have_been_requested
      expect(result.media_url).to eq("https://x/p.jpg")
    end
  end

  describe "#send_buttons" do
    it "POSTs /whatsapp/{code}/send/buttons" do
      stub = stub_request(:post, "#{SpecHelpers::BASE_URL}/whatsapp/inst_1/send/buttons")
             .with(body: {
               "to" => "+225", "body" => "Confirm?",
               "buttons" => [{ "id" => "yes", "title" => "Yes" }]
             })
             .to_return(status: 200, body: {
               id: "w3", instance_id: "i1", recipient: "+225",
               message_type: "buttons", status: "sent", scheduled: false
             }.to_json)

      client.whatsapp.send_buttons(
        "inst_1", to: "+225", body: "Confirm?",
        buttons: [{ "id" => "yes", "title" => "Yes" }]
      )
      expect(stub).to have_been_requested
    end
  end

  describe "#send_location" do
    it "POSTs /whatsapp/{code}/send/location" do
      stub = stub_request(:post, "#{SpecHelpers::BASE_URL}/whatsapp/inst_1/send/location")
             .with(body: {
               "to" => "+225", "latitude" => 5.36, "longitude" => -4.0,
               "name" => "HQ"
             })
             .to_return(status: 200, body: {
               id: "w4", instance_id: "i1", recipient: "+225",
               message_type: "location", status: "sent", scheduled: false
             }.to_json)

      client.whatsapp.send_location(
        "inst_1", to: "+225", latitude: 5.36, longitude: -4.0, name: "HQ"
      )
      expect(stub).to have_been_requested
    end
  end

  describe "#send_list" do
    it "POSTs /whatsapp/{code}/send/list" do
      stub = stub_request(:post, "#{SpecHelpers::BASE_URL}/whatsapp/inst_1/send/list")
             .with(body: {
               "to" => "+225", "body" => "Pick", "button_text" => "View",
               "sections" => [{ "title" => "Plans", "rows" => [{ "id" => "p", "title" => "Pro" }] }]
             })
             .to_return(status: 200, body: {
               id: "w5", instance_id: "i1", recipient: "+225",
               message_type: "list", status: "sent", scheduled: false
             }.to_json)

      client.whatsapp.send_list(
        "inst_1", to: "+225", body: "Pick", button_text: "View",
        sections: [{ "title" => "Plans", "rows" => [{ "id" => "p", "title" => "Pro" }] }]
      )
      expect(stub).to have_been_requested
    end
  end
end
