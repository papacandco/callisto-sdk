import { describe, it, expect } from "vitest";
import { CallistoClient } from "../src/client.js";
import { WhatsAppMediaType } from "../src/models.js";
import { mockFetch } from "./helpers.js";

const opts = { clientId: "cid", apiKey: "k", baseUrl: "https://api.test/v1" };
const sendResp = {
  id: "m1", instance_id: "i1", recipient: {}, message_type: "text",
  status: "pending", scheduled: false,
};

const instanceResp = {
  id: "i1", code: "inst_1", client_id: "c1", name: "Main",
  phone_number: "+2250700000000", phone_name: "Acme", status: "connected",
  billing_status: "active", trial_days_remaining: 0, monthly_fee: 5000,
  messages_sent_today: 3, messages_sent_month: 42, daily_limit: 1000,
  last_message_at: "2026-06-01 10:00:00", webhook_url: "https://hook.test",
  is_active: true, created_at: "2026-05-01 00:00:00", updated_at: "2026-06-01 10:00:00",
};

const messageResp = {
  id: "msg_9", instance_id: "i1", client_id: "c1", api_client_id: null,
  recipient: "+2250700000000", recipient_name: "Bob", message_type: "text",
  content: "Hi", media_url: null, media_mimetype: null, media_filename: null,
  extra_data: null, direction: "outbound", status: "delivered",
  whatsapp_message_id: "wamid.1", error_code: null, error_message: null,
  retry_count: 0, is_billable: true, cost: 10, sent_at: "2026-06-01 10:00:00",
  delivered_at: "2026-06-01 10:00:05", read_at: null, scheduled_at: null,
  created_at: "2026-06-01 09:59:00", updated_at: "2026-06-01 10:00:05",
  processor_identifier: "proc_1",
};

describe("whatsapp", () => {
  it("createInstance POSTs /whatsapp/instances", async () => {
    const { fetch, captured } = mockFetch(201, { code: "inst_1" });
    const client = new CallistoClient(opts, fetch);
    await client.whatsapp.createInstance({ name: "Main" });
    expect(captured[0].method).toBe("POST");
    expect(captured[0].url).toBe("https://api.test/v1/whatsapp/instances");
    expect(captured[0].body).toEqual({ name: "Main" });
  });

  it("listInstances GETs with page and returns typed paginated instances", async () => {
    const { fetch, captured } = mockFetch(200, {
      items: [instanceResp], total: 1, per_page: 15, current_page: 1,
      next: 1, previous: 1, total_pages: 1,
    });
    const client = new CallistoClient(opts, fetch);
    const res = await client.whatsapp.listInstances(3);
    expect(captured[0].url).toBe("https://api.test/v1/whatsapp/instances?page=3");
    expect(res.total).toBe(1);
    expect(res.items[0].code).toBe("inst_1");
    expect(res.items[0].is_active).toBe(true);
  });

  it("getInstance returns a typed instance; getQr/getStatus hit the right paths", async () => {
    const { fetch, captured } = mockFetch(200, instanceResp);
    const client = new CallistoClient(opts, fetch);
    const inst = await client.whatsapp.getInstance("inst_1");
    await client.whatsapp.getQr("inst_1");
    await client.whatsapp.getStatus("inst_1");
    expect(captured[0].url).toBe("https://api.test/v1/whatsapp/inst_1");
    expect(captured[1].url).toBe("https://api.test/v1/whatsapp/inst_1/qr");
    expect(captured[2].url).toBe("https://api.test/v1/whatsapp/inst_1/status");
    expect(inst.name).toBe("Main");
    expect(inst.status).toBe("connected");
  });

  it("listMessages returns typed paginated messages", async () => {
    const { fetch, captured } = mockFetch(200, {
      items: [messageResp], total: 1, per_page: 15, current_page: 1,
      next: 1, previous: 1, total_pages: 1,
    });
    const client = new CallistoClient(opts, fetch);
    const list = await client.whatsapp.listMessages("inst_1", { page: 2 });
    expect(captured[0].url).toBe("https://api.test/v1/whatsapp/inst_1/messages?page=2");
    expect(list.total).toBe(1);
    expect(list.items[0].id).toBe("msg_9");
    expect(list.items[0].status).toBe("delivered");
  });

  it("getMessage returns a typed message", async () => {
    const { fetch, captured } = mockFetch(200, messageResp);
    const client = new CallistoClient(opts, fetch);
    const msg = await client.whatsapp.getMessage("msg_9");
    expect(captured[0].url).toBe("https://api.test/v1/whatsapp/messages/msg_9");
    expect(msg.status).toBe("delivered");
    expect(msg.is_billable).toBe(true);
  });

  it("sendText/Media/Buttons/Location/List POST to their endpoints", async () => {
    const { fetch, captured } = mockFetch(200, sendResp);
    const client = new CallistoClient(opts, fetch);
    await client.whatsapp.sendText("inst_1", { to: "+225", message: "hi" });
    await client.whatsapp.sendMedia("inst_1", { to: "+225", type: WhatsAppMediaType.Image, media_url: "u" });
    await client.whatsapp.sendButtons("inst_1", { to: "+225", body: "b", buttons: [{ id: "1", title: "Yes" }] });
    await client.whatsapp.sendLocation("inst_1", { to: "+225", latitude: 1.2, longitude: 3.4 });
    await client.whatsapp.sendList("inst_1", { to: "+225", body: "b", button_text: "Open", sections: [] });
    expect(captured.map((c) => c.url)).toEqual([
      "https://api.test/v1/whatsapp/inst_1/send/text",
      "https://api.test/v1/whatsapp/inst_1/send/media",
      "https://api.test/v1/whatsapp/inst_1/send/buttons",
      "https://api.test/v1/whatsapp/inst_1/send/location",
      "https://api.test/v1/whatsapp/inst_1/send/list",
    ]);
    expect(captured[1].body).toMatchObject({ type: "image", media_url: "u" });
  });
});
