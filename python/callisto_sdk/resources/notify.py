from __future__ import annotations

from typing import Optional

from .._http import Transport
from ..errors import ValidationError
from ..models import NotifyResult

_EVENT_KEYS = ("email", "sms", "mobile_push", "web_push", "webhook", "messaging", "real_time")


class NotifyResource:
    def __init__(self, transport: Transport):
        self._t = transport

    def send(
        self,
        topic: str,
        email: Optional[list] = None,
        sms: Optional[list] = None,
        mobile_push: Optional[list] = None,
        web_push: Optional[list] = None,
        webhook: Optional[list] = None,
        messaging: Optional[list] = None,
        real_time: Optional[list] = None,
    ) -> NotifyResult:
        blocks = {
            "email": email, "sms": sms, "mobile_push": mobile_push,
            "web_push": web_push, "webhook": webhook,
            "messaging": messaging, "real_time": real_time,
        }
        present = {k: v for k, v in blocks.items() if v}
        if not present:
            raise ValidationError(
                "At least one event block (email, sms, mobile_push, web_push, "
                "webhook, messaging, real_time) must be provided."
            )
        body = {"topic": topic, **present}
        return NotifyResult.from_dict(self._t.request("POST", "/notify/send", body=body))
