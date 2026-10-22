"""Log shipping to callisto-logs (OTLP/HTTP JSON), with background delivery.

The logger has its own HTTP path (its own ``httpx.Client``): it never uses the
messaging ``Transport``, never sends ``client_id`` / ``api_key``, and its own
failures are swallowed (never reported, never logged through itself).
"""
from __future__ import annotations

import atexit
import base64
import collections
import json
import math
import os
import re
import threading
import time
import weakref
from typing import Any, Callable, Deque, List, Mapping, Optional, Tuple, Union

import httpx

from ._config import LogsConfig, resolve_logs_config
from ._reporter import SDK_LANGUAGE, SDK_NAME, _sdk_version

# -- limits (spec 2026-10-18) -------------------------------------------------

MAX_QUEUE = 2000
BATCH_TRIGGER = 100
MAX_BATCH_RECORDS = 500
MAX_BATCH_BYTES = 1_000_000
MAX_BODY_CHARS = 32_768
MAX_ATTR_VALUE_CHARS = 8_192
MAX_ATTRIBUTES = 128
MAX_NESTED_ITEMS = 128  # per nested list / map; extra items are dropped
MAX_RECORD_VALUES = 2048  # encoded values (scalars + containers) per record, all levels
MAX_DEPTH = 5

FLUSH_INTERVAL = 5.0
FLUSH_TIMEOUT = 5.0
HTTP_TIMEOUT = 10.0
MAX_ATTEMPTS = 3
RETRY_AFTER_DEFAULT = 5.0
RETRY_AFTER_CAP = 60.0
NETWORK_RETRY_DELAYS = (1.0, 5.0)
AUTH_PAUSE = 60.0

_ELLIPSIS = "…"
_INT64_MIN = -(2**63)
_INT64_MAX = 2**63 - 1

_SEVERITY = {
    "debug": (5, "DEBUG"),
    "info": (9, "INFO"),
    "warn": (13, "WARN"),
    "error": (17, "ERROR"),
    "fatal": (21, "FATAL"),
}
_LEVEL_ALIASES = {"warning": "warn", "critical": "fatal"}

_TRACE_ID_RE = re.compile(r"^[0-9a-fA-F]{32}$")
_SPAN_ID_RE = re.compile(r"^[0-9a-fA-F]{16}$")

# A sender returns the HTTP status, or ``(status, retry_after_header)``.
SenderResult = Union[int, Tuple[int, Optional[str]]]
Sender = Callable[[str, bytes, Mapping[str, str], float], SenderResult]


# -- AnyValue mapping -----------------------------------------------------------


def _truncate(value: str, limit: int) -> str:
    if len(value) <= limit:
        return value
    return value[: limit - 1] + _ELLIPSIS


def _safe_str(value: Any) -> str:
    try:
        return str(value)
    except Exception:
        try:
            return repr(value)
        except Exception:
            return "<unprintable>"


class _Budget:
    """Encoded values left for one record. A reused sub-list / sub-dict counts each
    time it is visited, so shared references cannot multiply the work."""

    __slots__ = ("left",)

    def __init__(self, left: int = MAX_RECORD_VALUES) -> None:
        self.left = left

    def take(self) -> bool:
        if self.left <= 0:
            return False
        self.left -= 1
        return True


class _StringFull(Exception):
    pass


def _bounded_str(
    value: Any, limit: int = MAX_ATTR_VALUE_CHARS, budget: Optional[_Budget] = None
) -> str:
    """``str()`` of a container, built piece by piece. It stops once past ``limit``
    characters, or when the record's value budget is spent (every nested item takes
    one value), so a huge, shared or self-referencing structure costs a bounded amount
    of work. Matches ``str()`` for plain dicts, lists and tuples; a cut ends with ``…``."""
    parts: List[str] = []
    size = 0
    active: set = set()
    budget = budget or _Budget()

    def emit(text: str) -> None:
        nonlocal size
        if size > limit:
            raise _StringFull
        parts.append(text)
        size += len(text)

    def walk(item: Any, root: bool = False) -> None:
        if not root and not budget.take():
            raise _StringFull
        if isinstance(item, (Mapping, list, tuple)):
            if id(item) in active or len(active) >= 64:
                emit("{...}" if isinstance(item, Mapping) else "[...]")
                return
            active.add(id(item))
            try:
                if isinstance(item, Mapping):
                    emit("{")
                    for index, (key, val) in enumerate(item.items()):
                        if index:
                            emit(", ")
                        walk(key)
                        emit(": ")
                        walk(val)
                    emit("}")
                else:
                    opening, closing = ("[", "]") if isinstance(item, list) else ("(", ")")
                    emit(opening)
                    for index, val in enumerate(item):
                        if index:
                            emit(", ")
                        walk(val)
                    if isinstance(item, tuple) and len(item) == 1:
                        emit(",")
                    emit(closing)
            finally:
                active.discard(id(item))
            return
        try:
            emit(repr(item))
        except _StringFull:
            raise
        except Exception:
            emit("<unprintable>")

    try:
        walk(value, root=True)
    except _StringFull:
        text = "".join(parts)
        return _truncate(text, limit) if len(text) > limit else text + _ELLIPSIS
    return "".join(parts)


def _any_value(value: Any, depth: int = 1, budget: Optional[_Budget] = None) -> Optional[dict]:
    """Map a Python value to an OTLP ``AnyValue``. ``None`` for a null value, or when the
    record's value budget is spent (the value is dropped)."""
    if value is None:
        return None
    if budget is None:
        budget = _Budget()
    if not budget.take():
        return None
    if isinstance(value, bool):
        return {"boolValue": value}
    if isinstance(value, int):
        if _INT64_MIN <= value <= _INT64_MAX:
            return {"intValue": str(value)}
        return {"stringValue": _truncate(str(value), MAX_ATTR_VALUE_CHARS)}
    if isinstance(value, float):
        if math.isnan(value):
            return {"stringValue": "NaN"}
        if math.isinf(value):
            return {"stringValue": "Infinity" if value > 0 else "-Infinity"}
        return {"doubleValue": value}
    if isinstance(value, str):
        return {"stringValue": _truncate(value, MAX_ATTR_VALUE_CHARS)}
    if isinstance(value, (list, tuple, Mapping)):
        if depth > MAX_DEPTH:
            return {"stringValue": _truncate(_bounded_str(value, budget=budget), MAX_ATTR_VALUE_CHARS)}
        if isinstance(value, Mapping):
            return {
                "kvlistValue": {
                    "values": _key_values(value, depth + 1, MAX_NESTED_ITEMS, budget)
                }
            }
        values: List[dict] = []
        for item in value:
            if len(values) >= MAX_NESTED_ITEMS:
                break
            if item is None:
                if not budget.take():
                    break
                values.append({})
                continue
            mapped = _any_value(item, depth + 1, budget)
            if mapped is None:
                break  # budget spent
            values.append(mapped)
        return {"arrayValue": {"values": values}}
    return {"stringValue": _truncate(_safe_str(value), MAX_ATTR_VALUE_CHARS)}


def _key_values(
    mapping: Mapping, depth: int, limit: Optional[int] = None, budget: Optional[_Budget] = None
) -> List[dict]:
    if budget is None:
        budget = _Budget()
    out: List[dict] = []
    for key, raw in mapping.items():
        if raw is None:
            continue
        if limit is not None and len(out) >= limit:
            break
        mapped = _any_value(raw, depth, budget)
        if mapped is None:
            break  # budget spent: this and every later value are dropped
        out.append({"key": _safe_str(key), "value": mapped})
    return out


def _valid_id(value: Any, pattern: "re.Pattern[str]") -> Optional[str]:
    if isinstance(value, str) and pattern.match(value) and value.strip("0") != "":
        return value.lower()
    return None


def _normalize_level(level: Any) -> str:
    name = _safe_str(level).strip().lower()
    name = _LEVEL_ALIASES.get(name, name)
    return name if name in _SEVERITY else "info"


def _parse_retry_after(raw: Optional[str]) -> float:
    if raw is None:
        return RETRY_AFTER_DEFAULT
    try:
        seconds = float(str(raw).strip())
    except (TypeError, ValueError):
        return RETRY_AFTER_DEFAULT
    if math.isnan(seconds) or seconds < 0:
        return RETRY_AFTER_DEFAULT
    return min(seconds, RETRY_AFTER_CAP)


# -- default HTTP sender ---------------------------------------------------------


class _HttpxSender:
    """POSTs a batch with a private ``httpx.Client`` (never the messaging one)."""

    def __init__(self) -> None:
        self._client: Optional[httpx.Client] = None

    def __call__(
        self, url: str, body: bytes, headers: Mapping[str, str], timeout: float
    ) -> Tuple[int, Optional[str]]:
        if self._client is None:
            self._client = httpx.Client(timeout=timeout)
        resp = self._client.post(url, content=body, headers=dict(headers))
        return resp.status_code, resp.headers.get("Retry-After")

    def close(self) -> None:
        client, self._client = self._client, None
        if client is not None:
            try:
                client.close()
            except Exception:
                pass


# -- the logger -----------------------------------------------------------------------


class _DeliveryState:
    """Everything the worker thread touches. A fork replaces it wholesale, so a
    worker that belongs to an older state (or to the parent process) can never
    read or send the new one."""

    def __init__(self) -> None:
        self.cond = threading.Condition()
        self.buffer: Deque[Tuple[dict, int]] = collections.deque()
        self.sending = False
        self.flush_pending = 0  # number of flush() calls waiting
        self.stopping = False
        self.paused_until: Optional[float] = None
        self.stop_event = threading.Event()
        self.worker: Optional[threading.Thread] = None


# Live enabled loggers, reset in a forked child by one at-fork hook.
_LIVE_LOGGERS: "weakref.WeakSet[CallistoLogger]" = weakref.WeakSet()


def _after_fork_in_child() -> None:
    for logger in list(_LIVE_LOGGERS):
        try:
            logger._reset_after_fork()
        except Exception:
            pass


if hasattr(os, "register_at_fork"):
    os.register_at_fork(after_in_child=_after_fork_in_child)


class CallistoLogger:
    """Ships log records to callisto-logs in the background.

    Every argument is optional and falls back to the environment
    (``CALLISTO_LOGS_ACCESS_KEY``, ``CALLISTO_LOGS_SECRET``,
    ``CALLISTO_LOGS_ENDPOINT``, ``CALLISTO_SERVICE_NAME`` / ``OTEL_SERVICE_NAME``,
    ``CALLISTO_ENVIRONMENT``). With no key and no secret the logger is a cheap
    no-op that starts no thread. Half or malformed credentials, or an endpoint
    that is not ``https`` (``http`` only on a loopback host), raise
    ``ValueError`` at construction. (The messaging :class:`~callisto_sdk.Client`
    does not raise: it warns once and keeps a disabled logger.)

    A log call never raises and never waits for the network. ``sender``,
    ``clock``, ``sleep``, ``monotonic`` and ``flush_interval`` are hooks for tests.

    Fork-safe: in a forked child (gunicorn/uWSGI preload, multiprocessing) the
    parent's queued records are discarded, the state is reset, and a new worker
    thread starts on the child's first record.
    """

    def __init__(
        self,
        logs_access_key: Optional[str] = None,
        logs_secret: Optional[str] = None,
        logs_endpoint: Optional[str] = None,
        service_name: Optional[str] = None,
        environment: Optional[str] = None,
        flush_timeout: float = FLUSH_TIMEOUT,
        *,
        sender: Optional[Sender] = None,
        clock: Optional[Callable[[], int]] = None,
        sleep: Optional[Callable[[float], None]] = None,
        monotonic: Optional[Callable[[], float]] = None,
        flush_interval: float = FLUSH_INTERVAL,
        _config: Optional[LogsConfig] = None,
    ) -> None:
        self._config: LogsConfig = _config or resolve_logs_config(
            logs_access_key, logs_secret, logs_endpoint, service_name, environment
        )
        self._version = _sdk_version()
        self._flush_timeout = float(flush_timeout)
        self._flush_interval = float(flush_interval)
        self._clock = clock or time.time_ns
        self._monotonic = monotonic or time.monotonic
        self._custom_sleep = sleep
        self._own_sender = sender is None
        self._sender: Sender = sender or _HttpxSender()

        self._state = _DeliveryState()
        self._dropped = 0
        self._dropped_lock = threading.Lock()
        self._closed = False
        self._pid = os.getpid()

        self._headers = {}
        self._resource: dict = {}
        if not self._config.enabled:
            return

        token = base64.b64encode(
            f"{self._config.access_key}:{self._config.secret}".encode("utf-8")
        ).decode("ascii")
        self._headers = {
            "Authorization": f"Basic {token}",
            "Content-Type": "application/json",
            "User-Agent": f"{SDK_NAME}-{SDK_LANGUAGE}/{self._version}",
        }
        resource_attrs = [
            {"key": "service.name", "value": {"stringValue": self._config.service_name}}
        ]
        if self._config.environment:
            resource_attrs.append(
                {
                    "key": "deployment.environment.name",
                    "value": {"stringValue": self._config.environment},
                }
            )
        resource_attrs += [
            {"key": "telemetry.sdk.name", "value": {"stringValue": SDK_NAME}},
            {"key": "telemetry.sdk.language", "value": {"stringValue": SDK_LANGUAGE}},
            {"key": "telemetry.sdk.version", "value": {"stringValue": self._version}},
        ]
        self._resource = {"attributes": resource_attrs}

        _LIVE_LOGGERS.add(self)
        with self._state.cond:
            self._start_worker(self._state)
        atexit.register(self.close)

    # -- public API ------------------------------------------------------------

    @property
    def enabled(self) -> bool:
        return self._config.enabled

    @property
    def endpoint(self) -> str:
        return self._config.endpoint

    @property
    def dropped_count(self) -> int:
        """Records lost without delivery: queue full, auth pause, rejected
        batch, retries exhausted, or still queued when close() gave up."""
        return self._dropped

    @property
    def _worker(self) -> Optional[threading.Thread]:
        return self._state.worker

    def debug(self, message: Any, attributes: Optional[Mapping[str, Any]] = None) -> None:
        self.log("debug", message, attributes)

    def info(self, message: Any, attributes: Optional[Mapping[str, Any]] = None) -> None:
        self.log("info", message, attributes)

    def warn(self, message: Any, attributes: Optional[Mapping[str, Any]] = None) -> None:
        self.log("warn", message, attributes)

    def error(self, message: Any, attributes: Optional[Mapping[str, Any]] = None) -> None:
        self.log("error", message, attributes)

    def fatal(self, message: Any, attributes: Optional[Mapping[str, Any]] = None) -> None:
        self.log("fatal", message, attributes)

    def log(
        self, level: str, message: Any, attributes: Optional[Mapping[str, Any]] = None
    ) -> None:
        """Queue one record. Never raises, never blocks on the network."""
        if not self._config.enabled or self._closed or self.in_delivery_thread():
            return
        try:
            self._check_fork()
            state = self._state
            if self._is_paused(state):
                self._count_dropped(1)
                return
            try:
                record = self._build_record(level, message, attributes)
                size = len(
                    json.dumps(record, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
                )
            except Exception:
                self._count_dropped(1)
                return
            with state.cond:
                if self._closed:
                    return
                if len(state.buffer) >= MAX_QUEUE:
                    self._count_dropped(1)
                    return
                if state.worker is None:
                    self._start_worker(state)
                state.buffer.append((record, size))
                if len(state.buffer) >= BATCH_TRIGGER:
                    state.cond.notify_all()
        except Exception:
            return

    def flush(self, timeout: Optional[float] = None) -> None:
        """Send what is queued now and wait for it, bounded (5 s by default)."""
        if not self._config.enabled or self.in_delivery_thread():
            return
        try:
            self._check_fork()
            limit = self._flush_timeout if timeout is None else float(timeout)
            self._wait_drained(self._state, time.monotonic() + max(0.0, limit))
        except Exception:
            return

    def close(self, timeout: Optional[float] = None) -> None:
        """Flush, then stop the worker. Bounded; later calls are no-ops."""
        if self._closed:
            return
        try:
            self._check_fork()
            state = self._state
            limit = self._flush_timeout if timeout is None else float(timeout)
            deadline = time.monotonic() + max(0.0, limit)
            on_worker = self.in_delivery_thread()
            if not on_worker:
                self._wait_drained(state, deadline)
            with state.cond:
                self._closed = True
                state.stopping = True
                state.cond.notify_all()
            state.stop_event.set()
            worker = state.worker
            if worker is not None and not on_worker:
                worker.join(max(0.0, deadline - time.monotonic()))
                if not worker.is_alive() and self._own_sender:
                    self._sender.close()  # type: ignore[attr-defined]
            try:
                atexit.unregister(self.close)
            except Exception:
                pass
        except Exception:
            self._closed = True

    def in_delivery_thread(self) -> bool:
        """True on the logger's own worker thread (used by the recursion guard)."""
        worker = self._state.worker
        return worker is not None and threading.current_thread() is worker

    def __enter__(self) -> "CallistoLogger":
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()

    # -- fork safety ------------------------------------------------------------------

    def _check_fork(self) -> None:
        """PID fallback for platforms or paths the at-fork hook does not cover."""
        if self._pid != os.getpid():
            self._reset_after_fork()

    def _reset_after_fork(self) -> None:
        """In a child: forget the parent's queue, pause and worker; the next
        record starts a new worker. Never touch the parent's lock (a thread of
        the parent may have held it at fork time)."""
        self._pid = os.getpid()
        if not self._config.enabled or self._closed:
            return
        self._state = _DeliveryState()
        self._dropped = 0
        self._dropped_lock = threading.Lock()
        if self._own_sender:
            # The parent's connection pool is not ours to reuse or close.
            self._sender = _HttpxSender()

    # -- record building ----------------------------------------------------------

    def _build_record(
        self, level: str, message: Any, attributes: Optional[Mapping[str, Any]]
    ) -> dict:
        number, text = _SEVERITY[_normalize_level(level)]
        now = str(int(self._clock()))
        record: dict = {
            "timeUnixNano": now,
            "observedTimeUnixNano": now,
            "severityNumber": number,
            "severityText": text,
            "body": {"stringValue": _truncate(_safe_str(message), MAX_BODY_CHARS)},
        }
        attrs = dict(attributes) if isinstance(attributes, Mapping) else {}
        trace_id = _valid_id(attrs.get("trace_id"), _TRACE_ID_RE)
        if trace_id is not None:
            del attrs["trace_id"]
        span_id = _valid_id(attrs.get("span_id"), _SPAN_ID_RE)
        if span_id is not None:
            del attrs["span_id"]
        key_values = _key_values(attrs, 1, MAX_ATTRIBUTES, _Budget())
        if key_values:
            record["attributes"] = key_values
        if trace_id is not None:
            record["traceId"] = trace_id
        if span_id is not None:
            record["spanId"] = span_id
        return record

    def _envelope(self, records: List[dict]) -> dict:
        return {
            "resourceLogs": [
                {
                    "resource": self._resource,
                    "scopeLogs": [
                        {
                            "scope": {"name": SDK_NAME, "version": self._version},
                            "logRecords": records,
                        }
                    ],
                }
            ]
        }

    # -- worker ---------------------------------------------------------------------

    def _count_dropped(self, count: int) -> None:
        if count <= 0:
            return
        with self._dropped_lock:
            self._dropped += count

    def _start_worker(self, state: _DeliveryState) -> None:
        """Start the worker of ``state``; the caller holds ``state.cond``."""
        state.worker = threading.Thread(
            target=self._run, args=(state,), name="callisto-logs", daemon=True
        )
        state.worker.start()

    def _is_paused(self, state: _DeliveryState) -> bool:
        until = state.paused_until
        if until is None:
            return False
        if self._monotonic() < until:
            return True
        state.paused_until = None
        return False

    def _wait_drained(self, state: _DeliveryState, deadline: float) -> None:
        with state.cond:
            state.flush_pending += 1
            state.cond.notify_all()
            try:
                while state.buffer or state.sending:
                    remaining = deadline - time.monotonic()
                    worker = state.worker
                    if remaining <= 0 or worker is None or not worker.is_alive():
                        break
                    state.cond.wait(min(remaining, 0.05))
            finally:
                state.flush_pending -= 1

    @staticmethod
    def _take_batch(state: _DeliveryState) -> List[dict]:
        batch: List[dict] = []
        total = 0
        while state.buffer and len(batch) < MAX_BATCH_RECORDS:
            record, size = state.buffer[0]
            if batch and total + size > MAX_BATCH_BYTES:
                break
            state.buffer.popleft()
            batch.append(record)
            total += size
        return batch

    def _run(self, state: _DeliveryState) -> None:
        while True:
            if self._state is not state:
                return  # superseded (fork reset)
            try:
                with state.cond:
                    deadline = time.monotonic() + self._flush_interval
                    while (
                        not state.stopping
                        and not (state.flush_pending and state.buffer)
                        and len(state.buffer) < BATCH_TRIGGER
                    ):
                        remaining = deadline - time.monotonic()
                        if remaining <= 0:
                            break
                        state.cond.wait(remaining)
                    if self._state is not state:
                        return  # superseded (fork reset): never send the old queue
                    if state.stopping:
                        self._count_dropped(len(state.buffer))
                        state.buffer.clear()
                        state.cond.notify_all()
                        return
                    batch = self._take_batch(state)
                    state.sending = bool(batch)
                if batch:
                    try:
                        self._deliver(state, batch)
                    finally:
                        with state.cond:
                            state.sending = False
                            state.cond.notify_all()
            except Exception:
                # The logger's own failures are swallowed, never re-logged.
                with state.cond:
                    state.sending = False
                    state.cond.notify_all()

    def _wait(self, state: _DeliveryState, seconds: float) -> bool:
        """Sleep before a retry; False when the logger is stopping."""
        if self._custom_sleep is not None:
            self._custom_sleep(seconds)
            return not state.stop_event.is_set()
        return not state.stop_event.wait(seconds)

    def _deliver(self, state: _DeliveryState, batch: List[dict]) -> None:
        if self._is_paused(state):
            self._count_dropped(len(batch))
            return
        try:
            body = json.dumps(
                self._envelope(batch), separators=(",", ":"), ensure_ascii=False, allow_nan=False
            ).encode("utf-8")
        except Exception:
            self._count_dropped(len(batch))
            return

        for attempt in range(1, MAX_ATTEMPTS + 1):
            if self._state is not state:
                return  # superseded by a fork reset
            try:
                result = self._sender(self._config.endpoint, body, self._headers, HTTP_TIMEOUT)
                if isinstance(result, tuple):
                    status, retry_after = int(result[0]), result[1]
                else:
                    status, retry_after = int(result), None
            except Exception:
                delay = NETWORK_RETRY_DELAYS[min(attempt - 1, len(NETWORK_RETRY_DELAYS) - 1)]
                if attempt >= MAX_ATTEMPTS or not self._wait(state, delay):
                    self._count_dropped(len(batch))
                    return
                continue

            if 200 <= status < 300:
                return
            if status in (401, 403):
                state.paused_until = self._monotonic() + AUTH_PAUSE
                with state.cond:
                    cleared = len(state.buffer)
                    state.buffer.clear()
                self._count_dropped(len(batch) + cleared)
                return
            if status in (429, 503):
                if attempt >= MAX_ATTEMPTS or not self._wait(state, _parse_retry_after(retry_after)):
                    self._count_dropped(len(batch))
                    return
                continue
            # 400 / 413 / 415 and any other status: drop the batch.
            self._count_dropped(len(batch))
            return
