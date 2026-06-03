// ---- Enums ----
export enum MessageStatus {
  Pending = "pending",
  Sent = "sent",
  Delivered = "delivered",
  Failed = "failed",
}

export enum OtpStatus {
  Pending = "pending",
  Verified = "verified",
  Expired = "expired",
  Failed = "failed",
}

export enum OtpType {
  Digit = "digit",
  Alpha = "alpha",
  Alphanumeric = "alphanumeric",
}

export enum OtpProvider {
  Sms = "sms",
  Whatsapp = "whatsapp",
}

export enum WhatsAppMediaType {
  Image = "image",
  Video = "video",
  Document = "document",
  Audio = "audio",
}

// ---- Balance ----
export interface Balance {
  credit: number;
  currency: string;
  sms_price_local?: number;
  sms_price_international?: number;
}

// ---- SMS ----
export interface SendSmsParams {
  sender: string;
  to: string | string[];
  message: string;
  notify_url?: string;
  scheduled_at?: string;
}

export interface SendSmsResult {
  total_amount: number;
  available_credit: number;
  status: string;
  recipient_count: number;
  scheduled: boolean;
  messages: unknown[];
}

export interface ListSmsParams {
  started_at?: string;
  ended_at?: string;
  page?: number;
  per_page?: number;
}

// Paginated wrapper matching the API's PaginatedResultDTO shape.
export interface Paginated<T> {
  items: T[];
  total: number;
  per_page: number;
  current_page: number;
  next: number | null;
  previous: number | null;
  total_pages: number;
}

export interface SmsMessage {
  id: string;
  sender_name: string | null;
  recipient: string | null;
  content: string;
  status: string;
  created_at: string;
  updated_at: string;
}

// ---- OTP ----
export interface SendOtpParams {
  to: string;
  message: string;
  sender?: string;
  expired_in?: number;
  type?: OtpType;
  digit_size?: number;
  provider?: OtpProvider;
  instance_code?: string;
}

export interface SendOtpResult {
  id: string;
  provider: string;
  recipient: Record<string, unknown>;
  expires_at: string;
  expires_in: number;
}

export interface VerifyOtpParams {
  otp_id: string;
  code: string;
}

export interface VerifyOtpResult {
  id: string;
  status: string;
  verified: boolean;
  verified_at: string | null;
}

export interface ListOtpsParams {
  started_at?: string;
  ended_at?: string;
  page?: number;
  limit?: number;
}

export interface Otp {
  // getStatus returns `otp_id`; list rows return `id`.
  otp_id?: string;
  id?: string;
  status: string;
  recipient: string | null;
  expires_at: string | null;
  verified_at: string | null;
  attempts: number | null;
  created_at: string | null;
}

// ---- WhatsApp ----
export interface CreateInstanceParams {
  name: string;
  phone_number?: string;
  webhook_url?: string;
  idempotency_key?: string;
}

export interface WhatsAppInstance {
  id: string;
  code: string | null;
  client_id: string;
  name: string;
  phone_number: string | null;
  phone_name: string | null;
  status: string;
  billing_status: string;
  trial_days_remaining: number;
  monthly_fee: number;
  messages_sent_today: number;
  messages_sent_month: number;
  daily_limit: number;
  last_message_at: string | null;
  webhook_url: string | null;
  is_active: boolean;
  created_at: string;
  updated_at: string;
}

export interface WhatsAppMessage {
  id: string;
  instance_id: string;
  client_id: string;
  api_client_id: string | null;
  recipient: string;
  recipient_name: string | null;
  message_type: string;
  content: string | null;
  media_url: string | null;
  media_mimetype: string | null;
  media_filename: string | null;
  extra_data: Record<string, unknown> | null;
  direction: string;
  status: string;
  whatsapp_message_id: string | null;
  error_code: number | null;
  error_message: string | null;
  retry_count: number;
  is_billable: boolean;
  cost: number;
  sent_at: string | null;
  delivered_at: string | null;
  read_at: string | null;
  scheduled_at: string | null;
  created_at: string;
  updated_at: string;
  processor_identifier: string | null;
}

export interface SendWaTextParams {
  to: string;
  message: string;
  scheduled_at?: string;
}

export interface SendWaMediaParams {
  to: string;
  type: WhatsAppMediaType;
  media_url: string;
  caption?: string;
  filename?: string;
  scheduled_at?: string;
}

export interface WaButton {
  id: string;
  title: string;
}

export interface SendWaButtonsParams {
  to: string;
  body: string;
  buttons: WaButton[];
  header?: string;
  footer?: string;
  scheduled_at?: string;
}

export interface SendWaLocationParams {
  to: string;
  latitude: number;
  longitude: number;
  name?: string;
  address?: string;
  scheduled_at?: string;
}

export interface WaListRow {
  id: string;
  title: string;
  description?: string;
}

export interface WaListSection {
  title: string;
  rows: WaListRow[];
}

export interface SendWaListParams {
  to: string;
  body: string;
  button_text: string;
  sections: WaListSection[];
  header?: string;
  footer?: string;
  scheduled_at?: string;
}

export interface SendWaResult {
  id: string;
  instance_id: string;
  recipient: unknown;
  message_type: string;
  status: string;
  scheduled: boolean;
  media_url?: string;
}

export interface ListWaMessagesParams {
  started_at?: string;
  ended_at?: string;
  page?: number;
  per_page?: number;
}

// ---- Notify ----
export interface NotifyParams {
  topic: string;
  email?: Record<string, unknown>[];
  sms?: Record<string, unknown>[];
  mobile_push?: Record<string, unknown>[];
  web_push?: Record<string, unknown>[];
  webhook?: Record<string, unknown>[];
  messaging?: Record<string, unknown>[];
  real_time?: Record<string, unknown>[];
}

export interface NotifyResult {
  status: string;
  topic: unknown;
  queued_events: unknown;
  topic_messages: unknown;
}
