import type { Transport } from "../http.js";
import type { Balance } from "../models.js";

export interface GetBalanceParams {
  format?: "full" | "short";
  currency?: string;
}

export class BalanceResource {
  constructor(private readonly t: Transport) {}

  get(params: GetBalanceParams = {}): Promise<Balance> {
    return this.t.request<Balance>("GET", "/sms/balance", {
      query: { format: params.format ?? "full", currency: params.currency },
    });
  }
}
