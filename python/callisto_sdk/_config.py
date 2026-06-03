from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Optional

DEFAULT_BASE_URL = "https://api.callistosignal.com/v1"


@dataclass(frozen=True)
class Config:
    client_id: str
    api_key: str
    base_url: str
    timeout: float


def resolve_config(
    client_id: Optional[str] = None,
    api_key: Optional[str] = None,
    base_url: Optional[str] = None,
    timeout: float = 30.0,
) -> Config:
    client_id = client_id or os.environ.get("CALLISTO_CLIENT_ID")
    api_key = api_key or os.environ.get("CALLISTO_API_KEY")
    if not client_id or not api_key:
        raise ValueError(
            "Callisto: client_id and api_key are required "
            "(pass arguments or set CALLISTO_CLIENT_ID / CALLISTO_API_KEY)."
        )
    base_url = (base_url or os.environ.get("CALLISTO_BASE_URL") or DEFAULT_BASE_URL).rstrip("/")
    return Config(client_id=client_id, api_key=api_key, base_url=base_url, timeout=timeout)
