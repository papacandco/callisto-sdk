import { describe, it, expect } from "vitest";
import { CallistoClient } from "../src/client.js";
import { mockFetch } from "./helpers.js";

describe("config / credentials", () => {
  it("constructs with only an errorDsn (no clientId/apiKey)", () => {
    expect(
      () => new CallistoClient({ errorDsn: "https://ingest.test/apps/x?key=y" }),
    ).not.toThrow();
  });

  it("constructs with no options at all (error reporting disabled, still no throw)", () => {
    expect(() => new CallistoClient()).not.toThrow();
  });

  it("error reporting works on a credential-less client", () => {
    const client = new CallistoClient({
      errorDsn: "https://ingest.test/apps/x?key=y",
    });
    expect(() => client.captureException(new Error("boom"))).not.toThrow();
    expect(() => client.captureMessage("hi", "info")).not.toThrow();
  });

  it("rejects a messaging call when credentials are missing", async () => {
    const { fetch } = mockFetch(200, {});
    const client = new CallistoClient({ baseUrl: "https://api.test/v1" }, fetch);
    await expect(
      client.sms.send({ sender: "Acme", to: "+2250700000000", message: "Hi" }),
    ).rejects.toThrow(/clientId and apiKey are required for messaging/);
  });

  it("still sends messaging calls when credentials are present", async () => {
    const { fetch, captured } = mockFetch(200, {
      total_amount: 1,
      available_credit: 9,
      status: "sent",
      recipient_count: 1,
      scheduled: false,
      messages: [],
    });
    const client = new CallistoClient(
      { clientId: "cid", apiKey: "k", baseUrl: "https://api.test/v1" },
      fetch,
    );
    await client.sms.send({ sender: "Acme", to: "+2250700000000", message: "Hi" });
    expect(captured[0].headers.authorization).toMatch(/^Basic /);
  });
});
