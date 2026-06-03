import { describe, it, expect } from "vitest";
import { CallistoClient } from "../src/client.js";
import { mockFetch } from "./helpers.js";

const opts = { clientId: "cid", apiKey: "k", baseUrl: "https://api.test/v1" };

describe("sms", () => {
  it("send POSTs /sms/send with the body", async () => {
    const { fetch, captured } = mockFetch(200, {
      total_amount: 5, available_credit: 95, status: "sent",
      recipient_count: 1, scheduled: false, messages: [],
    });
    const client = new CallistoClient(opts, fetch);
    const res = await client.sms.send({ sender: "Acme", to: "+2250700000000", message: "Hi" });
    expect(captured[0].method).toBe("POST");
    expect(captured[0].url).toBe("https://api.test/v1/sms/send");
    expect(captured[0].body).toEqual({ sender: "Acme", to: "+2250700000000", message: "Hi" });
    expect(res.status).toBe("sent");
  });

  it("list GETs /sms/messages with pagination query", async () => {
    const message = {
      id: "abc", sender_name: "Acme", recipient: "+2250700000000",
      content: "Hi", status: "delivered",
      created_at: "2026-06-01 10:00:00", updated_at: "2026-06-01 10:01:00",
    };
    const { fetch, captured } = mockFetch(200, {
      items: [message], total: 1, per_page: 15, current_page: 1,
      next: 1, previous: 1, total_pages: 1,
    });
    const client = new CallistoClient(opts, fetch);
    const res = await client.sms.list({ page: 2, per_page: 50 });
    expect(captured[0].url).toBe("https://api.test/v1/sms/messages?page=2&per_page=50");
    expect(res.total).toBe(1);
    expect(res.items.length).toBe(1);
    expect(res.items[0].status).toBe("delivered");
    expect(res.items[0].sender_name).toBe("Acme");
  });

  it("getStatus GETs /sms/:id", async () => {
    const { fetch, captured } = mockFetch(200, {
      id: "abc", sender_name: "Acme", recipient: "+2250700000000",
      content: "Hi", status: "delivered",
      created_at: "2026-06-01 10:00:00", updated_at: "2026-06-01 10:01:00",
    });
    const client = new CallistoClient(opts, fetch);
    const res = await client.sms.getStatus("abc");
    expect(captured[0].url).toBe("https://api.test/v1/sms/abc");
    expect(res.status).toBe("delivered");
    expect(res.sender_name).toBe("Acme");
  });
});
