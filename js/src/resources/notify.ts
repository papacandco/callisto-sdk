import type { Transport } from "../http.js";
import type { NotifyParams, NotifyResult } from "../models.js";
import { ValidationError } from "../errors.js";

const EVENT_KEYS = [
  "email", "sms", "mobile_push", "web_push", "webhook", "messaging", "real_time",
] as const;

export class NotifyResource {
  constructor(private readonly t: Transport) {}

  async send(params: NotifyParams): Promise<NotifyResult> {
    const hasEvent = EVENT_KEYS.some((k) => {
      const v = params[k];
      return Array.isArray(v) && v.length > 0;
    });
    if (!hasEvent) {
      const err = new ValidationError(
        "At least one event block (email, sms, mobile_push, web_push, webhook, messaging, real_time) must be provided.",
        400,
        undefined,
      );
      this.t.report(err, "POST", "/notify/send");
      throw err;
    }
    return this.t.request<NotifyResult>("POST", "/notify/send", { body: params });
  }
}
