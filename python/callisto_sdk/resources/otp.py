from __future__ import annotations

from typing import Any, Optional, Union

from .._http import Transport
from ..errors import ValidationError
from ..models import Otp, OtpProvider, OtpType, Paginated, SendOtpResult, VerifyOtpResult


class OtpResource:
    def __init__(self, transport: Transport):
        self._t = transport

    def send(
        self,
        to: str,
        message: str,
        sender: Optional[str] = None,
        expired_in: Optional[int] = None,
        type: Optional[Union[OtpType, str]] = None,
        digit_size: Optional[int] = None,
        provider: Optional[Union[OtpProvider, str]] = None,
        instance_code: Optional[str] = None,
    ) -> SendOtpResult:
        provider_value = provider.value if isinstance(provider, OtpProvider) else provider
        if provider_value == OtpProvider.WHATSAPP.value and not instance_code:
            raise ValidationError("instance_code is required when provider is whatsapp")
        body: dict[str, Any] = {"to": to, "message": message}
        if sender is not None:
            body["sender"] = sender
        if expired_in is not None:
            body["expired_in"] = expired_in
        if type is not None:
            body["type"] = type.value if isinstance(type, OtpType) else type
        if digit_size is not None:
            body["digit_size"] = digit_size
        if provider_value is not None:
            body["provider"] = provider_value
        if instance_code is not None:
            body["instanceCode"] = instance_code
        return SendOtpResult.from_dict(self._t.request("POST", "/otp/send", body=body))

    def verify(self, otp_id: str, code: str) -> VerifyOtpResult:
        return VerifyOtpResult.from_dict(
            self._t.request("POST", "/otp/verify", body={"otp_id": otp_id, "code": code})
        )

    def get_status(self, otp_id: str) -> Otp:
        return Otp.from_dict(self._t.request("GET", f"/otps/{otp_id}"))

    def list(
        self,
        started_at: Optional[str] = None,
        ended_at: Optional[str] = None,
        page: Optional[int] = None,
        limit: Optional[int] = None,
    ) -> Paginated[Otp]:
        return Paginated.from_dict(self._t.request("GET", "/otps", query={
            "started_at": started_at, "ended_at": ended_at, "page": page, "limit": limit,
        }), Otp.from_dict)
