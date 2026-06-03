from __future__ import annotations

from typing import Any, Optional, Union

from .._http import Transport
from ..models import (
    Paginated, SendWaResult, WhatsAppInstance, WhatsAppMediaType, WhatsAppMessage,
)


def _drop_none(d: dict) -> dict:
    return {k: v for k, v in d.items() if v is not None}


class WhatsAppResource:
    def __init__(self, transport: Transport):
        self._t = transport

    def create_instance(
        self,
        name: str,
        phone_number: Optional[str] = None,
        webhook_url: Optional[str] = None,
        idempotency_key: Optional[str] = None,
    ) -> WhatsAppInstance:
        body = _drop_none({
            "name": name, "phone_number": phone_number,
            "webhook_url": webhook_url, "idempotency_key": idempotency_key,
        })
        return WhatsAppInstance.from_dict(
            self._t.request("POST", "/whatsapp/instances", body=body)
        )

    def list_instances(self, page: int = 1) -> Paginated[WhatsAppInstance]:
        return Paginated.from_dict(
            self._t.request("GET", "/whatsapp/instances", query={"page": page}),
            WhatsAppInstance.from_dict,
        )

    def get_instance(self, code: str) -> WhatsAppInstance:
        return WhatsAppInstance.from_dict(self._t.request("GET", f"/whatsapp/{code}"))

    def get_qr(self, code: str) -> Any:
        return self._t.request("GET", f"/whatsapp/{code}/qr")

    def get_status(self, code: str) -> Any:
        return self._t.request("GET", f"/whatsapp/{code}/status")

    def list_messages(
        self,
        code: str,
        started_at: Optional[str] = None,
        ended_at: Optional[str] = None,
        page: Optional[int] = None,
        per_page: Optional[int] = None,
    ) -> Paginated[WhatsAppMessage]:
        return Paginated.from_dict(self._t.request("GET", f"/whatsapp/{code}/messages", query={
            "started_at": started_at, "ended_at": ended_at, "page": page, "per_page": per_page,
        }), WhatsAppMessage.from_dict)

    def get_message(self, message_id: str) -> WhatsAppMessage:
        return WhatsAppMessage.from_dict(
            self._t.request("GET", f"/whatsapp/messages/{message_id}")
        )

    def send_text(
        self, code: str, to: str, message: str, scheduled_at: Optional[str] = None
    ) -> SendWaResult:
        body = _drop_none({"to": to, "message": message, "scheduled_at": scheduled_at})
        return SendWaResult.from_dict(
            self._t.request("POST", f"/whatsapp/{code}/send/text", body=body)
        )

    def send_media(
        self,
        code: str,
        to: str,
        type: Union[WhatsAppMediaType, str],
        media_url: str,
        caption: Optional[str] = None,
        filename: Optional[str] = None,
        scheduled_at: Optional[str] = None,
    ) -> SendWaResult:
        body = _drop_none({
            "to": to,
            "type": type.value if isinstance(type, WhatsAppMediaType) else type,
            "media_url": media_url, "caption": caption,
            "filename": filename, "scheduled_at": scheduled_at,
        })
        return SendWaResult.from_dict(
            self._t.request("POST", f"/whatsapp/{code}/send/media", body=body)
        )

    def send_buttons(
        self,
        code: str,
        to: str,
        body: str,
        buttons: list,
        header: Optional[str] = None,
        footer: Optional[str] = None,
        scheduled_at: Optional[str] = None,
    ) -> SendWaResult:
        payload = _drop_none({
            "to": to, "body": body, "buttons": buttons,
            "header": header, "footer": footer, "scheduled_at": scheduled_at,
        })
        return SendWaResult.from_dict(
            self._t.request("POST", f"/whatsapp/{code}/send/buttons", body=payload)
        )

    def send_location(
        self,
        code: str,
        to: str,
        latitude: float,
        longitude: float,
        name: Optional[str] = None,
        address: Optional[str] = None,
        scheduled_at: Optional[str] = None,
    ) -> SendWaResult:
        payload = _drop_none({
            "to": to, "latitude": latitude, "longitude": longitude,
            "name": name, "address": address, "scheduled_at": scheduled_at,
        })
        return SendWaResult.from_dict(
            self._t.request("POST", f"/whatsapp/{code}/send/location", body=payload)
        )

    def send_list(
        self,
        code: str,
        to: str,
        body: str,
        button_text: str,
        sections: list,
        header: Optional[str] = None,
        footer: Optional[str] = None,
        scheduled_at: Optional[str] = None,
    ) -> SendWaResult:
        payload = _drop_none({
            "to": to, "body": body, "button_text": button_text, "sections": sections,
            "header": header, "footer": footer, "scheduled_at": scheduled_at,
        })
        return SendWaResult.from_dict(
            self._t.request("POST", f"/whatsapp/{code}/send/list", body=payload)
        )
