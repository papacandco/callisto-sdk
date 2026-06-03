from __future__ import annotations

import sys
import threading
from typing import Optional

import httpx

from ._config import resolve_config
from ._http import Transport
from ._reporter import ErrorReporter
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
        error_dsn: Optional[str] = None,
        capture_unhandled: Optional[bool] = None,
        environment: Optional[str] = None,
        reporter: Optional[ErrorReporter] = None,
    ):
        cfg = resolve_config(
            client_id,
            api_key,
            base_url,
            timeout,
            error_dsn=error_dsn,
            capture_unhandled=capture_unhandled,
            environment=environment,
        )
        self.error_reporter = reporter or ErrorReporter(
            cfg.error_dsn, environment=cfg.environment
        )
        self._transport = Transport(cfg, http_client, reporter=self.error_reporter)
        self.balance = BalanceResource(self._transport)
        self.sms = SmsResource(self._transport)
        self.otp = OtpResource(self._transport)
        self.whatsapp = WhatsAppResource(self._transport)
        self.notify = NotifyResource(self._transport)

        self._prev_excepthook = None
        self._prev_thread_excepthook = None
        if cfg.capture_unhandled and self.error_reporter.enabled:
            self._install_unhandled_hook()

    # -- error-reporting public API -----------------------------------------

    def capture_exception(self, error, level: str = "error", extra: Optional[dict] = None) -> None:
        self.error_reporter.capture_exception(error, level=level, extra=extra)

    def capture_message(self, message: str, level: str = "info", extra: Optional[dict] = None) -> None:
        self.error_reporter.capture_message(message, level=level, extra=extra)

    def set_user(self, mapping: Optional[dict]) -> None:
        self.error_reporter.set_user(mapping)

    # -- unhandled-exception handler (opt-in) -------------------------------

    def _install_unhandled_hook(self) -> None:
        self._prev_excepthook = sys.excepthook

        def _hook(exc_type, exc_value, exc_tb):
            try:
                if exc_value is not None:
                    if exc_value.__traceback__ is None:
                        exc_value.__traceback__ = exc_tb
                    self.error_reporter.capture_exception(exc_value, level="fatal")
                    self.error_reporter.flush()
            except Exception:
                pass
            # Preserve the platform's default behavior (chain the previous hook).
            if self._prev_excepthook is not None:
                self._prev_excepthook(exc_type, exc_value, exc_tb)

        sys.excepthook = _hook

        self._prev_thread_excepthook = threading.excepthook

        def _thread_hook(args):
            try:
                if args.exc_value is not None:
                    if args.exc_value.__traceback__ is None:
                        args.exc_value.__traceback__ = args.exc_traceback
                    self.error_reporter.capture_exception(args.exc_value, level="fatal")
                    self.error_reporter.flush()
            except Exception:
                pass
            if self._prev_thread_excepthook is not None:
                self._prev_thread_excepthook(args)

        threading.excepthook = _thread_hook

    # -- lifecycle ----------------------------------------------------------

    def close(self) -> None:
        try:
            self.error_reporter.flush()
            self.error_reporter.close()
        finally:
            self._transport.close()

    def __enter__(self) -> "Client":
        return self

    def __exit__(self, *exc) -> None:
        self.close()
