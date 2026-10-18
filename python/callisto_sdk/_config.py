from __future__ import annotations

import os
import re
from dataclasses import dataclass
from typing import Optional
from urllib.parse import urlsplit

DEFAULT_BASE_URL = "https://api.callistosignal.com/v1"


def _env_bool(value: Optional[str]) -> Optional[bool]:
    if value is None:
        return None
    return value.strip().lower() in ("1", "true", "yes", "on")


@dataclass(frozen=True)
class Config:
    client_id: str
    api_key: str
    base_url: str
    timeout: float
    error_dsn: Optional[str] = None
    capture_unhandled: bool = False
    environment: Optional[str] = None


def resolve_config(
    client_id: Optional[str] = None,
    api_key: Optional[str] = None,
    base_url: Optional[str] = None,
    timeout: float = 30.0,
    error_dsn: Optional[str] = None,
    capture_unhandled: Optional[bool] = None,
    environment: Optional[str] = None,
) -> Config:
    client_id = client_id or os.environ.get("CALLISTO_CLIENT_ID")
    api_key = api_key or os.environ.get("CALLISTO_API_KEY")
    if not client_id or not api_key:
        raise ValueError(
            "Callisto: client_id and api_key are required "
            "(pass arguments or set CALLISTO_CLIENT_ID / CALLISTO_API_KEY)."
        )
    base_url = (base_url or os.environ.get("CALLISTO_BASE_URL") or DEFAULT_BASE_URL).rstrip("/")
    error_dsn = error_dsn or os.environ.get("CALLISTO_APP_ERROR_DSN")
    if capture_unhandled is None:
        capture_unhandled = _env_bool(os.environ.get("CALLISTO_CAPTURE_UNHANDLED")) or False
    environment = environment or os.environ.get("CALLISTO_ENVIRONMENT")
    return Config(
        client_id=client_id,
        api_key=api_key,
        base_url=base_url,
        timeout=timeout,
        error_dsn=error_dsn,
        capture_unhandled=capture_unhandled,
        environment=environment,
    )


# -- logs (callisto-logs) ------------------------------------------------------

DEFAULT_LOGS_ENDPOINT = "https://logs.callistosignal.com/v1/logs"
DEFAULT_SERVICE_NAME = "unknown_service"

_LOGS_ACCESS_KEY_RE = re.compile(r"^pk_[0-9a-f]{32}$")
_LOGS_SECRET_RE = re.compile(r"^sk_[0-9a-f]{48}$")


@dataclass(frozen=True)
class LogsConfig:
    """Resolved settings of a :class:`~callisto_sdk.CallistoLogger`.

    ``access_key`` / ``secret`` are both ``None`` when logs are disabled.
    """

    access_key: Optional[str]
    secret: Optional[str]
    endpoint: str
    service_name: str
    environment: Optional[str] = None

    @property
    def enabled(self) -> bool:
        return self.access_key is not None and self.secret is not None


def _is_http_url(value: str) -> bool:
    try:
        parts = urlsplit(value)
    except ValueError:
        return False
    return parts.scheme in ("http", "https") and bool(parts.netloc)


def resolve_logs_config(
    logs_access_key: Optional[str] = None,
    logs_secret: Optional[str] = None,
    logs_endpoint: Optional[str] = None,
    service_name: Optional[str] = None,
    environment: Optional[str] = None,
) -> LogsConfig:
    """Resolve the logs settings: explicit argument first, then environment.

    Both key and secret absent -> disabled config (no error). Exactly one of them,
    a malformed value, or a non-http(s) endpoint -> ``ValueError`` (the SDK's
    configuration error), so a typo never silently loses every log.
    """
    access_key = logs_access_key or os.environ.get("CALLISTO_LOGS_ACCESS_KEY") or None
    secret = logs_secret or os.environ.get("CALLISTO_LOGS_SECRET") or None
    endpoint = logs_endpoint or os.environ.get("CALLISTO_LOGS_ENDPOINT") or DEFAULT_LOGS_ENDPOINT
    service_name = (
        service_name
        or os.environ.get("CALLISTO_SERVICE_NAME")
        or os.environ.get("OTEL_SERVICE_NAME")
        or DEFAULT_SERVICE_NAME
    )
    environment = environment or os.environ.get("CALLISTO_ENVIRONMENT") or None

    if access_key is None and secret is None:
        return LogsConfig(None, None, endpoint, service_name, environment)

    if access_key is None or secret is None:
        raise ValueError(
            "Callisto: logs_access_key and logs_secret must be set together "
            "(pass arguments or set CALLISTO_LOGS_ACCESS_KEY / CALLISTO_LOGS_SECRET)."
        )
    if not isinstance(access_key, str) or not _LOGS_ACCESS_KEY_RE.match(access_key):
        raise ValueError(
            "Callisto: logs_access_key is malformed (expected 'pk_' followed by 32 lowercase hex characters)."
        )
    if not isinstance(secret, str) or not _LOGS_SECRET_RE.match(secret):
        raise ValueError(
            "Callisto: logs_secret is malformed (expected 'sk_' followed by 48 lowercase hex characters)."
        )
    if not isinstance(endpoint, str) or not _is_http_url(endpoint):
        raise ValueError("Callisto: logs_endpoint must be an absolute http(s) URL.")

    return LogsConfig(access_key, secret, endpoint, service_name, environment)
