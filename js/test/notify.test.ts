import { describe, it, expect } from "vitest";
import { CallistoClient } from "../src/client.js";
import { mockFetch } from "./helpers.js";

const opts = { clientId: "cid", apiKey: "k", baseUrl: "https://api.test/v1" };

describe("notify.send", () => {
  it("POSTs /notify/send with the topic and event blocks", async () => {
    const { fetch, captured } = mockFetch(200, {
      status: "queued", topic: "t", queued_events: 1, topic_messages: [],
    });
    const client = new CallistoClient(opts, fetch);
    const res = await client.notify.send({ topic: "welcome", sms: [{ to: "+225" }] });
    expect(captured[0].url).toBe("https://api.test/v1/notify/send");
    expect(captured[0].body).toEqual({ topic: "welcome", sms: [{ to: "+225" }] });
    expect(res.status).toBe("queued");
  });

  it("throws before the network when no event block is given", async () => {
    const { fetch, captured } = mockFetch(200, {});
    const client = new CallistoClient(opts, fetch);
    await expect(client.notify.send({ topic: "welcome" })).rejects.toThrow(/event block/);
    expect(captured.length).toBe(0);
  });
});
