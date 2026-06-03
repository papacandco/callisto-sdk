from __future__ import annotations

from typing import Any, Optional

import httpx

from ._config import Config
from .errors import error_from_status, NetworkError


class Transport:
    def __init__(self, config: Config, http_client: Optional[httpx.Client] = None):
        self._config = config
        self._client = http_client or httpx.Client(
            auth=(config.client_id, config.api_key),
            timeout=config.timeout,
            headers={"accept": "application/json"},
        )

    def request(
        self,
        method: str,
        path: str,
        body: Optional[Any] = None,
        query: Optional[dict] = None,
    ) -> Any:
        url = self._config.base_url + path
        params = {k: v for k, v in (query or {}).items() if v is not None}
        try:
            resp = self._client.request(
                method, url, json=body if body is not None else None, params=params or None
            )
        except httpx.HTTPError as exc:
            raise NetworkError(f"Request to {url} failed: {exc}") from exc

        data: Any = None
        if resp.content:
            try:
                data = resp.json()
            except ValueError:
                data = resp.text

        if resp.is_error:
            message = (
                data.get("message")
                if isinstance(data, dict) and "message" in data
                else f"HTTP {resp.status_code}"
            )
            retry_after: Optional[int] = None
            if resp.status_code == 429:
                raw = resp.headers.get("Retry-After")
                if raw is not None:
                    try:
                        retry_after = int(raw)
                    except ValueError:
                        retry_after = None
            raise error_from_status(resp.status_code, str(message), data, retry_after)
        return data

    def close(self) -> None:
        self._client.close()
