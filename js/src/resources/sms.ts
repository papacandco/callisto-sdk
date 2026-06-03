import type { Transport } from "../http.js";
import type {
  SendSmsParams, SendSmsResult, ListSmsParams, Paginated, SmsMessage,
} from "../models.js";

export class SmsResource {
  constructor(private readonly t: Transport) {}

  send(params: SendSmsParams): Promise<SendSmsResult> {
    return this.t.request<SendSmsResult>("POST", "/sms/send", { body: params });
  }

  list(params: ListSmsParams = {}): Promise<Paginated<SmsMessage>> {
    return this.t.request<Paginated<SmsMessage>>("GET", "/sms/messages", {
      query: {
        started_at: params.started_at,
        ended_at: params.ended_at,
        page: params.page,
        per_page: params.per_page,
      },
    });
  }

  getStatus(id: string): Promise<SmsMessage> {
    return this.t.request<SmsMessage>("GET", `/sms/${encodeURIComponent(id)}`);
  }
}
