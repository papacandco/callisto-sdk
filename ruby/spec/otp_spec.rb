# frozen_string_literal: true

RSpec.describe Callisto::OtpResource do
  let(:client) { build_client }

  describe "#send" do
    it "POSTs /otp/send with enum type/provider passed as strings" do
      stub = stub_request(:post, "#{SpecHelpers::BASE_URL}/otp/send")
             .with(body: {
               "to" => "+2250700000000", "message" => "Code {code}",
               "type" => "digit", "digit_size" => 6, "expired_in" => 300,
               "provider" => "sms"
             })
             .to_return(status: 200, body: {
               id: "otp_1", provider: "sms", recipient: { to: "+225" },
               expires_at: "2026-06-03T00:05:00Z", expires_in: 300
             }.to_json)

      result = client.otp.send(
        to: "+2250700000000", message: "Code {code}",
        type: Callisto::OtpType::DIGIT, digit_size: 6, expired_in: 300,
        provider: Callisto::OtpProvider::SMS
      )

      expect(stub).to have_been_requested
      expect(result).to be_a(Callisto::SendOtpResult)
      expect(result.id).to eq("otp_1")
      expect(result.expires_in).to eq(300)
    end

    it "sends instance_code as instanceCode when provider is whatsapp" do
      stub = stub_request(:post, "#{SpecHelpers::BASE_URL}/otp/send")
             .with(body: {
               "to" => "+225", "message" => "Code {code}",
               "provider" => "whatsapp", "instanceCode" => "inst_1"
             })
             .to_return(status: 200, body: {
               id: "otp_2", provider: "whatsapp", recipient: {},
               expires_at: "x", expires_in: 300
             }.to_json)

      client.otp.send(
        to: "+225", message: "Code {code}",
        provider: Callisto::OtpProvider::WHATSAPP, instance_code: "inst_1"
      )
      expect(stub).to have_been_requested
    end

    it "raises ValidationError client-side when provider is whatsapp without instance_code" do
      expect do
        client.otp.send(to: "+225", message: "x", provider: "whatsapp")
      end.to raise_error(Callisto::ValidationError, /instance_code is required/)
      expect(a_request(:post, "#{SpecHelpers::BASE_URL}/otp/send")).not_to have_been_made
    end
  end

  describe "#verify" do
    it "POSTs /otp/verify and decodes VerifyOtpResult" do
      stub = stub_request(:post, "#{SpecHelpers::BASE_URL}/otp/verify")
             .with(body: { "otp_id" => "otp_1", "code" => "123456" })
             .to_return(status: 200, body: {
               id: "otp_1", status: "verified", verified: true,
               verified_at: "2026-06-03T00:01:00Z"
             }.to_json)

      result = client.otp.verify(otp_id: "otp_1", code: "123456")
      expect(stub).to have_been_requested
      expect(result.verified).to be(true)
      expect(result.status).to eq("verified")
    end
  end

  describe "#get_status" do
    it "GETs /otps/{id} and decodes Otp" do
      stub = stub_request(:get, "#{SpecHelpers::BASE_URL}/otps/otp_1")
             .to_return(status: 200, body: { otp_id: "otp_1", status: "pending" }.to_json)

      otp = client.otp.get_status("otp_1")
      expect(stub).to have_been_requested
      expect(otp.otp_id).to eq("otp_1")
      expect(otp.status).to eq("pending")
    end
  end

  describe "#list" do
    it "GETs /otps with limit query key (not per_page)" do
      stub = stub_request(:get, "#{SpecHelpers::BASE_URL}/otps")
             .with(query: { "page" => "1", "limit" => "20" })
             .to_return(status: 200, body: {
               items: [{ id: "otp_1", status: "verified" }],
               total: 1, per_page: 20, current_page: 1,
               next: nil, previous: nil, total_pages: 1
             }.to_json)

      page = client.otp.list(page: 1, limit: 20)
      expect(stub).to have_been_requested
      expect(page.items.first).to be_a(Callisto::Otp)
      expect(page.items.first.id).to eq("otp_1")
    end
  end
end
