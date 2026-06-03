from __future__ import annotations

from typing import Optional

import httpx

from ._config import resolve_config
from ._http import Transport
from .resources.balance import BalanceResource
from .resources.sms import SmsResource
from .resources.otp import OtpResource
from .resources.whatsapp import WhatsAppResource
from .resources.notify import NotifyResource


class Client:
    def __init__(
        self,
        client_id: Optional[str] = None,
        api_key: Optional[str] = None,
        base_url: Optional[str] = None,
        timeout: float = 30.0,
        http_client: Optional[httpx.Client] = None,
    ):
        cfg = resolve_config(client_id, api_key, base_url, timeout)
        self._transport = Transport(cfg, http_client)
        self.balance = BalanceResource(self._transport)
        self.sms = SmsResource(self._transport)
        self.otp = OtpResource(self._transport)
        self.whatsapp = WhatsAppResource(self._transport)
        self.notify = NotifyResource(self._transport)

    def close(self) -> None:
        self._transport.close()

    def __enter__(self) -> "Client":
        return self

    def __exit__(self, *exc) -> None:
        self.close()
