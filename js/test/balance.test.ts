import { describe, it, expect } from "vitest";
import { CallistoClient } from "../src/client.js";
import { mockFetch } from "./helpers.js";

const opts = { clientId: "cid", apiKey: "k", baseUrl: "https://api.test/v1" };

describe("balance.get", () => {
  it("GETs /sms/balance with format query and returns the balance", async () => {
    const { fetch, captured } = mockFetch(200, { credit: 100.5, currency: "XOF" });
    const client = new CallistoClient(opts, fetch);
    const bal = await client.balance.get();
    expect(bal.credit).toBe(100.5);
    expect(captured[0].url).toBe("https://api.test/v1/sms/balance?format=full");
  });

  it("passes currency when supplied", async () => {
    const { fetch, captured } = mockFetch(200, { credit: 1, currency: "USD" });
    const client = new CallistoClient(opts, fetch);
    await client.balance.get({ format: "short", currency: "USD" });
    expect(captured[0].url).toContain("format=short");
    expect(captured[0].url).toContain("currency=USD");
  });
});
