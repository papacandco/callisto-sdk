from __future__ import annotations

from typing import Optional

from .._http import Transport
from ..models import Balance


class BalanceResource:
    def __init__(self, transport: Transport):
        self._t = transport

    def get(self, format: str = "full", currency: Optional[str] = None) -> Balance:
        data = self._t.request(
            "GET", "/sms/balance", query={"format": format, "currency": currency}
        )
        return Balance.from_dict(data)
