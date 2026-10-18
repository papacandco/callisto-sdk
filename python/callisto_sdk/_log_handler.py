"""``logging.Handler`` adapter that forwards standard-library log records to a
:class:`~callisto_sdk.CallistoLogger`."""
from __future__ import annotations

import logging
import threading
import traceback
from typing import Any, Dict

from ._logs import CallistoLogger

# Attributes every LogRecord carries; anything else on a record came from ``extra``.
_STANDARD_ATTRS = frozenset(
    logging.LogRecord("", logging.INFO, "", 0, "", (), None).__dict__.keys()
) | {"message", "asctime", "taskName"}


def _level_name(levelno: int) -> str:
    if levelno < logging.INFO:
        return "debug"
    if levelno < logging.WARNING:
        return "info"
    if levelno < logging.ERROR:
        return "warn"
    if levelno < logging.CRITICAL:
        return "error"
    return "fatal"


def _qualified_name(exc_type: type) -> str:
    module = getattr(exc_type, "__module__", None)
    name = getattr(exc_type, "__qualname__", None) or getattr(exc_type, "__name__", "Exception")
    if module and module != "builtins":
        return f"{module}.{name}"
    return name


class CallistoLogHandler(logging.Handler):
    """Forward ``logging`` records to callisto-logs.

    - ``levelno`` -> severity (below INFO debug, INFO info, WARNING warn,
      ERROR error, CRITICAL fatal);
    - ``record.getMessage()`` -> body; ``record.name`` -> attribute ``logger.name``;
    - ``exc_info`` -> ``exception.type`` / ``exception.message`` / ``exception.stacktrace``;
    - keys passed through ``extra`` -> attributes.

    Records emitted while the logger is sending (for example by ``httpx``) or
    re-entrantly from inside ``emit`` are dropped, never re-shipped.
    """

    def __init__(self, logger: CallistoLogger, level: int = logging.NOTSET) -> None:
        super().__init__(level)
        self.callisto_logger = logger
        self._local = threading.local()

    def emit(self, record: logging.LogRecord) -> None:
        logger = self.callisto_logger
        if getattr(self._local, "active", False) or logger.in_delivery_thread():
            return
        self._local.active = True
        try:
            try:
                message = record.getMessage()
            except Exception:
                message = str(record.msg)

            attributes: Dict[str, Any] = {"logger.name": record.name}
            for key, value in record.__dict__.items():
                if key not in _STANDARD_ATTRS:
                    attributes[key] = value

            exc_info = record.exc_info
            if exc_info and exc_info[0] is not None:
                exc_type, exc_value, exc_tb = exc_info
                attributes["exception.type"] = _qualified_name(exc_type)
                attributes["exception.message"] = str(exc_value) if exc_value is not None else ""
                attributes["exception.stacktrace"] = "".join(
                    traceback.format_exception(exc_type, exc_value, exc_tb)
                )

            logger.log(_level_name(record.levelno), message, attributes)
        except Exception:
            # Never raise from logging, and never route our own failure through logging.
            pass
        finally:
            self._local.active = False
