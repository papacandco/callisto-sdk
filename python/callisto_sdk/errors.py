from __future__ import annotations

from typing import Any, Optional


class CallistoError(Exception):
    def __init__(self, message: str, status_code: int = 0, body: Any = None):
        super().__init__(message)
        self.message = message
        self.status_code = status_code
        self.body = body


class AuthenticationError(CallistoError):
    pass


class ValidationError(CallistoError):
    pass


class NotFoundError(CallistoError):
    pass


class RateLimitError(CallistoError):
    def __init__(
        self,
        message: str,
        status_code: int = 0,
        body: Any = None,
        retry_after: Optional[int] = None,
    ):
        super().__init__(message, status_code, body)
        self.retry_after = retry_after


class ApiError(CallistoError):
    pass


class NetworkError(CallistoError):
    pass


def error_from_status(
    status: int,
    message: str,
    body: Optional[Any] = None,
    retry_after: Optional[int] = None,
) -> CallistoError:
    if status == 401:
        return AuthenticationError(message, status, body)
    if status in (400, 422):
        return ValidationError(message, status, body)
    if status == 404:
        return NotFoundError(message, status, body)
    if status == 429:
        return RateLimitError(message, status, body, retry_after)
    return ApiError(message, status, body)
