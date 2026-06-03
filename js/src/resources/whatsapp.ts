import type { Transport } from "../http.js";
import type {
  CreateInstanceParams, WhatsAppInstance, WhatsAppMessage,
  SendWaTextParams, SendWaMediaParams, SendWaButtonsParams,
  SendWaLocationParams, SendWaListParams, SendWaResult,
  ListWaMessagesParams, Paginated,
} from "../models.js";

export class WhatsAppResource {
  constructor(private readonly t: Transport) {}

  private enc(code: string): string {
    return encodeURIComponent(code);
  }

  createInstance(params: CreateInstanceParams): Promise<WhatsAppInstance> {
    return this.t.request("POST", "/whatsapp/instances", { body: params });
  }

  listInstances(page = 1): Promise<Paginated<WhatsAppInstance>> {
    return this.t.request("GET", "/whatsapp/instances", { query: { page } });
  }

  getInstance(code: string): Promise<WhatsAppInstance> {
    return this.t.request("GET", `/whatsapp/${this.enc(code)}`);
  }

  getQr(code: string): Promise<Record<string, unknown>> {
    return this.t.request("GET", `/whatsapp/${this.enc(code)}/qr`);
  }

  getStatus(code: string): Promise<Record<string, unknown>> {
    return this.t.request("GET", `/whatsapp/${this.enc(code)}/status`);
  }

  listMessages(
    code: string,
    params: ListWaMessagesParams = {},
  ): Promise<Paginated<WhatsAppMessage>> {
    return this.t.request("GET", `/whatsapp/${this.enc(code)}/messages`, {
      query: {
        started_at: params.started_at,
        ended_at: params.ended_at,
        page: params.page,
        per_page: params.per_page,
      },
    });
  }

  getMessage(messageId: string): Promise<WhatsAppMessage> {
    return this.t.request("GET", `/whatsapp/messages/${this.enc(messageId)}`);
  }

  sendText(code: string, params: SendWaTextParams): Promise<SendWaResult> {
    return this.t.request("POST", `/whatsapp/${this.enc(code)}/send/text`, { body: params });
  }

  sendMedia(code: string, params: SendWaMediaParams): Promise<SendWaResult> {
    return this.t.request("POST", `/whatsapp/${this.enc(code)}/send/media`, { body: params });
  }

  sendButtons(code: string, params: SendWaButtonsParams): Promise<SendWaResult> {
    return this.t.request("POST", `/whatsapp/${this.enc(code)}/send/buttons`, { body: params });
  }

  sendLocation(code: string, params: SendWaLocationParams): Promise<SendWaResult> {
    return this.t.request("POST", `/whatsapp/${this.enc(code)}/send/location`, { body: params });
  }

  sendList(code: string, params: SendWaListParams): Promise<SendWaResult> {
    return this.t.request("POST", `/whatsapp/${this.enc(code)}/send/list`, { body: params });
  }
}
