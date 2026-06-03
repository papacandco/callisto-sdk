import type { Transport } from "../http.js";
import {
  OtpProvider,
  type SendOtpParams, type SendOtpResult,
  type VerifyOtpParams, type VerifyOtpResult,
  type ListOtpsParams, type Paginated, type Otp,
} from "../models.js";
import { ValidationError } from "../errors.js";

export class OtpResource {
  constructor(private readonly t: Transport) {}

  async send(params: SendOtpParams): Promise<SendOtpResult> {
    if (params.provider === OtpProvider.Whatsapp && !params.instance_code) {
      const err = new ValidationError(
        "instance_code is required when provider is whatsapp",
        400,
        undefined,
      );
      this.t.report(err, "POST", "/otp/send");
      throw err;
    }
    const body: Record<string, unknown> = {};
    if (params.to !== undefined) body.to = params.to;
    if (params.message !== undefined) body.message = params.message;
    if (params.sender !== undefined) body.sender = params.sender;
    if (params.expired_in !== undefined) body.expired_in = params.expired_in;
    if (params.type !== undefined) body.type = params.type;
    if (params.digit_size !== undefined) body.digit_size = params.digit_size;
    if (params.provider !== undefined) body.provider = params.provider;
    // Public param is `instance_code` (snake_case) for cross-language
    // consistency, but the server reads it as `instanceCode` on the wire.
    if (params.instance_code !== undefined) body.instanceCode = params.instance_code;

    return this.t.request<SendOtpResult>("POST", "/otp/send", { body });
  }

  verify(params: VerifyOtpParams): Promise<VerifyOtpResult> {
    return this.t.request<VerifyOtpResult>("POST", "/otp/verify", { body: params });
  }

  getStatus(id: string): Promise<Otp> {
    return this.t.request<Otp>("GET", `/otps/${encodeURIComponent(id)}`);
  }

  list(params: ListOtpsParams = {}): Promise<Paginated<Otp>> {
    return this.t.request<Paginated<Otp>>("GET", "/otps", {
      query: {
        started_at: params.started_at,
        ended_at: params.ended_at,
        page: params.page,
        limit: params.limit,
      },
    });
  }
}
