import { resolveConfig, type CallistoOptions } from "./config.js";
import { Transport } from "./http.js";
import { BalanceResource } from "./resources/balance.js";
import { SmsResource } from "./resources/sms.js";
import { OtpResource } from "./resources/otp.js";
import { WhatsAppResource } from "./resources/whatsapp.js";
import { NotifyResource } from "./resources/notify.js";

export class CallistoClient {
  readonly balance: BalanceResource;
  readonly sms: SmsResource;
  readonly otp: OtpResource;
  readonly whatsapp: WhatsAppResource;
  readonly notify: NotifyResource;

  constructor(options: CallistoOptions = {}, fetchImpl?: typeof fetch) {
    const cfg = resolveConfig(options);
    const transport = new Transport(cfg, fetchImpl);
    this.balance = new BalanceResource(transport);
    this.sms = new SmsResource(transport);
    this.otp = new OtpResource(transport);
    this.whatsapp = new WhatsAppResource(transport);
    this.notify = new NotifyResource(transport);
  }
}
