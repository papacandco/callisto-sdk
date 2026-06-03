from __future__ import annotations

from typing import Any, Optional, Union

from .._http import Transport
from ..models import Paginated, SendSmsResult, SmsMessage


class SmsResource:
    def __init__(self, transport: Transport):
        self._t = transport

    def send(
        self,
        sender: str,
        to: Union[str, list],
        message: str,
        notify_url: Optional[str] = None,
        scheduled_at: Optional[str] = None,
    ) -> SendSmsResult:
        body: dict[str, Any] = {"sender": sender, "to": to, "message": message}
        if notify_url is not None:
            body["notify_url"] = notify_url
        if scheduled_at is not None:
            body["scheduled_at"] = scheduled_at
        return SendSmsResult.from_dict(self._t.request("POST", "/sms/send", body=body))

    def list(
        self,
        started_at: Optional[str] = None,
        ended_at: Optional[str] = None,
        page: Optional[int] = None,
        per_page: Optional[int] = None,
    ) -> Paginated[SmsMessage]:
        return Paginated.from_dict(self._t.request("GET", "/sms/messages", query={
            "started_at": started_at, "ended_at": ended_at,
            "page": page, "per_page": per_page,
        }), SmsMessage.from_dict)

    def get_status(self, message_id: str) -> SmsMessage:
        return SmsMessage.from_dict(self._t.request("GET", f"/sms/{message_id}"))
