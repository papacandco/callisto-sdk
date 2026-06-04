from __future__ import annotations

import linecache
import queue
import threading
import traceback
from typing import Any, Callable, Optional

import httpx

from .errors import CallistoError, NetworkError

SDK_NAME = "callisto-sdk"
SDK_LANGUAGE = "python"

_VALID_LEVELS = ("fatal", "error", "warning", "info")

# Sentinel pushed onto the queue to stop the worker thread.
_STOP = object()

# Keys that must never be transmitted (PII / secrets hard rule).
_FORBIDDEN_KEYS = ("client_id", "api_key", "authorization", "body_sent", "request_body")


def _sdk_version() -> str:
    try:
        from . import __version__  # local import to avoid a circular import at module load

        return __version__
    except Exception:
        return "0.0.0"


def _default_sender(dsn: str, payload: dict, timeout: float) -> None:
    """Real HTTP sender: POSTs the JSON payload directly to the DSN URL.

    Uses its OWN httpx client (never the main Transport) so it never inherits the
    Basic-auth credentials and never recurses. All failures and any non-202 are
    swallowed by the caller.
    """
    with httpx.Client(timeout=timeout) as client:
        resp = client.post(dsn, json=payload, headers={"content-type": "application/json"})
        if resp.status_code != 202:
            raise RuntimeError(f"ingest returned {resp.status_code}")


def _constrain_level(level: Optional[str]) -> str:
    if level in _VALID_LEVELS:
        return level  # type: ignore[return-value]
    return "error"


# Source lines captured on each side of a frame's error line.
_CONTEXT_LINES = 5


def _source_context(filename: str, lineno: int) -> Optional[dict]:
    """Best-effort source window around ``lineno``: up to ``_CONTEXT_LINES``
    lines before (``pre_context``), the line itself (``context_line``), and up to
    ``_CONTEXT_LINES`` after (``post_context``), so the dashboard can render the
    failing code with the error line in focus.

    Uses :mod:`linecache` (the same source the traceback module reads), so it
    works for importable modules without re-opening files. Returns ``None`` when
    the source is unavailable — missing file, ``<stdin>``/``<string>`` frames, or
    line out of range — and the frame then renders without a source window.
    """
    if not filename or not lineno or lineno < 1:
        return None

    context_line = linecache.getline(filename, lineno)
    if context_line == "":
        # Unreadable file or line out of range.
        return None

    pre = [
        linecache.getline(filename, n).rstrip("\n")
        for n in range(max(1, lineno - _CONTEXT_LINES), lineno)
    ]

    post: list[str] = []
    for n in range(lineno + 1, lineno + 1 + _CONTEXT_LINES):
        line = linecache.getline(filename, n)
        if line == "":
            break
        post.append(line.rstrip("\n"))

    return {
        "pre_context": pre,
        "context_line": context_line.rstrip("\n"),
        "post_context": post,
    }


def _build_stacktrace(error: BaseException, with_source: bool = False) -> list[dict]:
    frames: list[dict] = []
    tb = error.__traceback__
    try:
        for frame, lineno in traceback.walk_tb(tb):
            code = frame.f_code
            built: dict[str, Any] = {
                "function": code.co_name,
                "file": code.co_filename,
                "line": lineno,
            }
            if with_source:
                ctx = _source_context(code.co_filename, lineno)
                if ctx:
                    built.update(ctx)
            frames.append(built)
    except Exception:
        return []
    # innermost-first
    frames.reverse()
    return frames


class ErrorReporter:
    """Opt-in, Sentry-style background error reporter.

    Posts captured errors to the Callisto ingest DSN. Delivery is background and
    best-effort: the caller's error path is never delayed and every failure of the
    reporter itself is swallowed. When ``dsn`` is missing/invalid the reporter is a
    cheap no-op.
    """

    def __init__(
        self,
        dsn: Optional[str],
        *,
        sdk_name: str = SDK_NAME,
        sdk_version: Optional[str] = None,
        language: str = SDK_LANGUAGE,
        environment: Optional[str] = None,
        sender: Optional[Callable[[str, dict, float], None]] = None,
        timeout: float = 3.0,
    ) -> None:
        self._dsn = dsn if _is_valid_dsn(dsn) else None
        self._sdk = {
            "name": sdk_name,
            "version": sdk_version or _sdk_version(),
            "language": language,
        }
        self._environment = environment
        self._sender = sender or _default_sender
        self._timeout = timeout
        self._user: Optional[dict] = None

        self._queue: "queue.Queue[Any]" = queue.Queue()
        self._worker: Optional[threading.Thread] = None
        self._closed = False
        if self.enabled:
            self._worker = threading.Thread(
                target=self._run, name="callisto-error-reporter", daemon=True
            )
            self._worker.start()

    @property
    def enabled(self) -> bool:
        return self._dsn is not None

    # -- public capture API -------------------------------------------------

    def set_user(self, mapping: Optional[dict]) -> None:
        self._user = dict(mapping) if mapping else None

    def capture_exception(
        self,
        error: BaseException,
        level: str = "error",
        extra: Optional[dict] = None,
        *,
        method: Optional[str] = None,
        path: Optional[str] = None,
    ) -> None:
        if not self.enabled:
            return
        try:
            payload = self._build_exception_payload(
                error, level=level, extra=extra, method=method, path=path
            )
            self._enqueue(payload)
        except Exception:
            # Never let the reporter disturb the caller's error path.
            return

    def capture_message(
        self, message: str, level: str = "info", extra: Optional[dict] = None
    ) -> None:
        if not self.enabled:
            return
        try:
            payload: dict[str, Any] = {
                "message": str(message),
                "type": "Message",
                "level": _constrain_level(level),
                "context": self._base_context(extra),
            }
            if self._user:
                payload["user"] = self._user
            self._enqueue(payload)
        except Exception:
            return

    # -- lifecycle ----------------------------------------------------------

    def flush(self, timeout: float = 2.0) -> None:
        if not self.enabled or self._worker is None:
            return
        try:
            # Wait for the queue to drain, bounded.
            deadline = threading.Event()
            t = threading.Timer(timeout, deadline.set)
            t.daemon = True
            t.start()
            try:
                while not self._queue.empty() and not deadline.is_set():
                    deadline.wait(0.01)
            finally:
                t.cancel()
        except Exception:
            return

    def close(self, timeout: float = 2.0) -> None:
        if self._closed:
            return
        self._closed = True
        if self._worker is None:
            return
        try:
            self.flush(timeout)
            self._queue.put(_STOP)
            self._worker.join(timeout)
        except Exception:
            return

    # -- internals ----------------------------------------------------------

    def _enqueue(self, payload: dict) -> None:
        if self._closed:
            return
        self._queue.put(payload)

    def _run(self) -> None:
        while True:
            item = self._queue.get()
            try:
                if item is _STOP:
                    return
                self._send(item)
            except Exception:
                # Swallow ALL failures; never re-capture the reporter's own errors.
                pass
            finally:
                self._queue.task_done()

    def _send(self, payload: dict) -> None:
        if self._dsn is None:
            return
        try:
            self._sender(self._dsn, payload, self._timeout)
        except Exception:
            # Any sender exception or non-202 is swallowed.
            return

    def _base_context(self, extra: Optional[dict]) -> dict:
        context: dict[str, Any] = {"sdk": dict(self._sdk)}
        if self._environment:
            context["environment"] = self._environment
        if extra:
            context.update(
                {
                    k: v
                    for k, v in extra.items()
                    if str(k).strip().lower() not in _FORBIDDEN_KEYS
                }
            )
        return context

    def _build_exception_payload(
        self,
        error: BaseException,
        *,
        level: str,
        extra: Optional[dict],
        method: Optional[str],
        path: Optional[str],
    ) -> dict:
        message = str(error) or error.__class__.__name__
        context = self._base_context(extra)

        if isinstance(error, CallistoError):
            context["status_code"] = error.status_code
            retry_after = getattr(error, "retry_after", None)
            if retry_after is not None:
                context["retry_after"] = retry_after
            if error.body is not None:
                context["body"] = error.body

        payload: dict[str, Any] = {
            "message": message,
            "type": error.__class__.__name__,
            "level": _constrain_level(level),
            "context": context,
        }

        # Transport-originated errors carry method + path. Source context is
        # captured ONLY for genuine application exceptions: a transport call
        # site can embed the outgoing request body as literal arguments, and
        # capturing it would violate the hard no-request-body guarantee. Such
        # errors already carry method/path as their culprit.
        is_transport = method is not None and path is not None

        stacktrace = _build_stacktrace(error, with_source=not is_transport)
        if stacktrace:
            payload["stacktrace"] = stacktrace

        if is_transport:
            payload["culprit"] = f"{method} {path}"
            payload["request"] = {"method": method, "path": path}
        elif stacktrace:
            top = stacktrace[0]
            payload["culprit"] = f"{top['function']} ({top['file']}:{top['line']})"

        if self._user:
            payload["user"] = self._user

        return payload


def _is_valid_dsn(dsn: Optional[str]) -> bool:
    if not dsn or not isinstance(dsn, str):
        return False
    return dsn.startswith("http://") or dsn.startswith("https://")
