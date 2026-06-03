from __future__ import annotations

from dataclasses import dataclass, fields
from enum import Enum
from typing import Any, Callable, Generic, Optional, TypeVar

T = TypeVar("T")


class MessageStatus(str, Enum):
    PENDING = "pending"
    SENT = "sent"
    DELIVERED = "delivered"
    FAILED = "failed"


class OtpStatus(str, Enum):
    PENDING = "pending"
    VERIFIED = "verified"
    EXPIRED = "expired"
    FAILED = "failed"


class OtpType(str, Enum):
    DIGIT = "digit"
    ALPHA = "alpha"
    ALPHANUMERIC = "alphanumeric"


class OtpProvider(str, Enum):
    SMS = "sms"
    WHATSAPP = "whatsapp"


class WhatsAppMediaType(str, Enum):
    IMAGE = "image"
    VIDEO = "video"
    DOCUMENT = "document"
    AUDIO = "audio"


def _from_dict(cls, data: dict[str, Any]):
    known = {f.name for f in fields(cls)}
    return cls(**{k: v for k, v in data.items() if k in known})


@dataclass
class Balance:
    credit: float
    currency: str
    sms_price_local: Optional[float] = None
    sms_price_international: Optional[float] = None

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Balance":
        return _from_dict(cls, data)


@dataclass
class SendSmsResult:
    total_amount: float
    available_credit: float
    status: str
    recipient_count: int
    scheduled: bool
    messages: list

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "SendSmsResult":
        return _from_dict(cls, data)


@dataclass
class SendOtpResult:
    id: str
    provider: str
    recipient: dict
    expires_at: str
    expires_in: int

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "SendOtpResult":
        return _from_dict(cls, data)


@dataclass
class VerifyOtpResult:
    id: str
    status: str
    verified: bool
    verified_at: Optional[str] = None

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "VerifyOtpResult":
        return _from_dict(cls, data)


@dataclass
class SendWaResult:
    id: str
    instance_id: str
    recipient: Any
    message_type: str
    status: str
    scheduled: bool
    media_url: Optional[str] = None

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "SendWaResult":
        return _from_dict(cls, data)


@dataclass
class NotifyResult:
    status: str
    topic: Any
    queued_events: Any
    topic_messages: Any

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "NotifyResult":
        return _from_dict(cls, data)


@dataclass
class Paginated(Generic[T]):
    items: list
    total: int
    per_page: int
    current_page: int
    next: Optional[int]
    previous: Optional[int]
    total_pages: int

    @classmethod
    def from_dict(cls, data: dict[str, Any], item_factory: Callable[[dict], T]) -> "Paginated[T]":
        return cls(
            items=[item_factory(i) for i in data.get("items", [])],
            total=data.get("total", 0),
            per_page=data.get("per_page", 0),
            current_page=data.get("current_page", 0),
            next=data.get("next"),
            previous=data.get("previous"),
            total_pages=data.get("total_pages", 0),
        )


@dataclass
class SmsMessage:
    id: str
    sender_name: Optional[str] = None
    recipient: Optional[str] = None
    content: Optional[str] = None
    status: Optional[str] = None
    created_at: Optional[str] = None
    updated_at: Optional[str] = None

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "SmsMessage":
        return _from_dict(cls, data)


@dataclass
class Otp:
    otp_id: Optional[str] = None
    id: Optional[str] = None
    status: Optional[str] = None
    recipient: Optional[str] = None
    expires_at: Optional[str] = None
    verified_at: Optional[str] = None
    attempts: Optional[int] = None
    created_at: Optional[str] = None

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Otp":
        return _from_dict(cls, data)


@dataclass
class WhatsAppInstance:
    id: str
    code: Optional[str] = None
    client_id: Optional[str] = None
    name: Optional[str] = None
    phone_number: Optional[str] = None
    phone_name: Optional[str] = None
    status: Optional[str] = None
    billing_status: Optional[str] = None
    trial_days_remaining: Optional[int] = None
    monthly_fee: Optional[float] = None
    messages_sent_today: Optional[int] = None
    messages_sent_month: Optional[int] = None
    daily_limit: Optional[int] = None
    last_message_at: Optional[str] = None
    webhook_url: Optional[str] = None
    is_active: Optional[bool] = None
    created_at: Optional[str] = None
    updated_at: Optional[str] = None

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "WhatsAppInstance":
        return _from_dict(cls, data)


@dataclass
class WhatsAppMessage:
    id: str
    instance_id: Optional[str] = None
    client_id: Optional[str] = None
    api_client_id: Optional[str] = None
    recipient: Optional[str] = None
    recipient_name: Optional[str] = None
    message_type: Optional[str] = None
    content: Optional[str] = None
    media_url: Optional[str] = None
    media_mimetype: Optional[str] = None
    media_filename: Optional[str] = None
    extra_data: Optional[dict] = None
    direction: Optional[str] = None
    status: Optional[str] = None
    whatsapp_message_id: Optional[str] = None
    error_code: Optional[int] = None
    error_message: Optional[str] = None
    retry_count: Optional[int] = None
    is_billable: Optional[bool] = None
    cost: Optional[float] = None
    sent_at: Optional[str] = None
    delivered_at: Optional[str] = None
    read_at: Optional[str] = None
    scheduled_at: Optional[str] = None
    created_at: Optional[str] = None
    updated_at: Optional[str] = None
    processor_identifier: Optional[str] = None

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "WhatsAppMessage":
        return _from_dict(cls, data)
