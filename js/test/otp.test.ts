import { describe, it, expect } from "vitest";
import { CallistoClient } from "../src/client.js";
import { OtpProvider } from "../src/models.js";
import { mockFetch } from "./helpers.js";

const opts = { clientId: "cid", apiKey: "k", baseUrl: "https://api.test/v1" };

describe("otp", () => {
  it("send POSTs /otp/send", async () => {
    const { fetch, captured } = mockFetch(200, {
      id: "o1", provider: "sms", recipient: {}, expires_at: "x", expires_in: 300,
    });
    const client = new CallistoClient(opts, fetch);
    const res = await client.otp.send({ to: "2250700000000", message: "Code {code}" });
    expect(captured[0].url).toBe("https://api.test/v1/otp/send");
    expect(captured[0].body).toEqual({ to: "2250700000000", message: "Code {code}" });
    expect(res.id).toBe("o1");
  });

  it("rejects whatsapp provider without instance_code before hitting the network", async () => {
    const { fetch, captured } = mockFetch(200, {});
    const client = new CallistoClient(opts, fetch);
    await expect(
      client.otp.send({ to: "x", message: "m", provider: OtpProvider.Whatsapp }),
    ).rejects.toThrow(/instance_code/);
    expect(captured.length).toBe(0);
  });

  it("sends instance_code on the wire as instanceCode for whatsapp", async () => {
    const { fetch, captured } = mockFetch(200, {
      id: "o1", provider: "whatsapp", recipient: {}, expires_at: "x", expires_in: 300,
    });
    const client = new CallistoClient(opts, fetch);
    await client.otp.send({
      to: "2250700000000",
      message: "Code {code}",
      provider: OtpProvider.Whatsapp,
      instance_code: "inst_1",
    });
    const body = captured[0].body as Record<string, unknown>;
    expect(body.instanceCode).toBe("inst_1");
    expect(body.instance_code).toBeUndefined();
  });

  it("verify POSTs /otp/verify", async () => {
    const { fetch, captured } = mockFetch(200, {
      id: "o1", status: "verified", verified: true, verified_at: "now",
    });
    const client = new CallistoClient(opts, fetch);
    const res = await client.otp.verify({ otp_id: "o1", code: "12345" });
    expect(captured[0].url).toBe("https://api.test/v1/otp/verify");
    expect(res.verified).toBe(true);
  });

  it("getStatus GETs /otps/:id", async () => {
    const { fetch, captured } = mockFetch(200, {
      otp_id: "o1", status: "pending", recipient: "+2250700000000",
      expires_at: "2026-06-01 10:05:00", verified_at: null,
      attempts: 0, created_at: "2026-06-01 10:00:00",
    });
    const client = new CallistoClient(opts, fetch);
    const res = await client.otp.getStatus("o1");
    expect(captured[0].url).toBe("https://api.test/v1/otps/o1");
    expect(res.otp_id).toBe("o1");
    expect(res.status).toBe("pending");
  });

  it("list GETs /otps", async () => {
    const { fetch, captured } = mockFetch(200, {
      items: [{
        id: "o1", status: "verified", recipient: "+2250700000000",
        expires_at: "2026-06-01 10:05:00", verified_at: "2026-06-01 10:02:00",
        attempts: 1, created_at: "2026-06-01 10:00:00",
      }],
      total: 1, per_page: 20, current_page: 1, next: 1, previous: 1, total_pages: 1,
    });
    const client = new CallistoClient(opts, fetch);
    const res = await client.otp.list({ page: 1, limit: 10 });
    expect(captured[0].url).toBe("https://api.test/v1/otps?page=1&limit=10");
    expect(res.total).toBe(1);
    expect(res.items[0].id).toBe("o1");
    expect(res.items[0].status).toBe("verified");
  });
});
