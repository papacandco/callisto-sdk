from __future__ import annotations

import base64
import collections
import json
import os
import logging
import threading
import warnings

import httpx
import pytest
import respx

import callisto_sdk
from callisto_sdk import CallistoLogger, CallistoLogHandler, Client
from callisto_sdk import _logs

PK = "pk_00112233445566778899aabbccddeeff"
SK = "sk_000102030405060708090a0b0c0d0e0f1011121314151617"
ENDPOINT = "https://logs.test/v1/logs"
FIXED_NS = 1760745600 * 10**9

_LOG_ENV = (
    "CALLISTO_LOGS_ACCESS_KEY",
    "CALLISTO_LOGS_SECRET",
    "CALLISTO_LOGS_ENDPOINT",
    "CALLISTO_SERVICE_NAME",
    "OTEL_SERVICE_NAME",
    "CALLISTO_ENVIRONMENT",
)


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch):
    for name in _LOG_ENV:
        monkeypatch.delenv(name, raising=False)


class FakeSender:
    """Records every request; answers from a scripted list (last answer repeats)."""

    def __init__(self, responses=None, block=False):
        self.calls = []
        self.responses = list(responses or [200])
        self.entered = threading.Event()
        self.release = threading.Event()
        if not block:
            self.release.set()
        self._lock = threading.Lock()

    def __call__(self, url, body, headers, timeout):
        with self._lock:
            self.calls.append({"url": url, "body": body, "headers": dict(headers), "timeout": timeout})
            index = len(self.calls) - 1
        self.entered.set()
        self.release.wait(5)
        answer = self.responses[min(index, len(self.responses) - 1)]
        if isinstance(answer, BaseException):
            raise answer
        return answer

    def payloads(self):
        return [json.loads(c["body"]) for c in self.calls]

    def records(self, index=0):
        return self.payloads()[index]["resourceLogs"][0]["scopeLogs"][0]["logRecords"]

    def batch_sizes(self):
        return [len(p["resourceLogs"][0]["scopeLogs"][0]["logRecords"]) for p in self.payloads()]


class FakeSleep:
    def __init__(self):
        self.delays = []

    def __call__(self, seconds):
        self.delays.append(seconds)


def make_logger(sender=None, **kw):
    kw.setdefault("clock", lambda: FIXED_NS)
    kw.setdefault("flush_interval", 30.0)
    return CallistoLogger(
        logs_access_key=PK,
        logs_secret=SK,
        logs_endpoint=ENDPOINT,
        sender=sender or FakeSender(),
        **kw,
    )


def only_record(sender):
    records = sender.records()
    assert len(records) == 1
    return records[0]


def attrs_of(record):
    return {kv["key"]: kv["value"] for kv in record.get("attributes", [])}


# -- 1. golden payload ---------------------------------------------------------------


def test_golden_payload():
    sender = FakeSender()
    logger = make_logger(sender, service_name="checkout", environment="production")
    logger.info(
        "order paid",
        {
            "order_id": 42,
            "paid": True,
            "trace_id": "5B8EFFF798038103D269B633813FC60C",
            "span_id": "EEE19B7EC3C1B174",
        },
    )
    logger.close()

    version = callisto_sdk.__version__
    assert version == "0.3.0"
    expected = {
        "resourceLogs": [{
            "resource": {"attributes": [
                {"key": "service.name", "value": {"stringValue": "checkout"}},
                {"key": "deployment.environment.name", "value": {"stringValue": "production"}},
                {"key": "telemetry.sdk.name", "value": {"stringValue": "callisto-sdk"}},
                {"key": "telemetry.sdk.language", "value": {"stringValue": "python"}},
                {"key": "telemetry.sdk.version", "value": {"stringValue": version}},
            ]},
            "scopeLogs": [{
                "scope": {"name": "callisto-sdk", "version": version},
                "logRecords": [{
                    "timeUnixNano": "1760745600000000000",
                    "observedTimeUnixNano": "1760745600000000000",
                    "severityNumber": 9,
                    "severityText": "INFO",
                    "body": {"stringValue": "order paid"},
                    "attributes": [
                        {"key": "order_id", "value": {"intValue": "42"}},
                        {"key": "paid", "value": {"boolValue": True}},
                    ],
                    "traceId": "5b8efff798038103d269b633813fc60c",
                    "spanId": "eee19b7ec3c1b174",
                }],
            }],
        }],
    }
    assert sender.payloads() == [expected]


def test_environment_absent_omits_resource_attribute_and_defaults_service_name():
    sender = FakeSender()
    logger = make_logger(sender)
    logger.info("hello")
    logger.close()
    resource = sender.payloads()[0]["resourceLogs"][0]["resource"]["attributes"]
    keys = [kv["key"] for kv in resource]
    assert "deployment.environment.name" not in keys
    assert resource[0] == {"key": "service.name", "value": {"stringValue": "unknown_service"}}
    record = only_record(sender)
    assert "attributes" not in record
    assert "traceId" not in record and "spanId" not in record


def test_service_name_env_resolution(monkeypatch):
    monkeypatch.setenv("OTEL_SERVICE_NAME", "from-otel")
    assert make_logger()._config.service_name == "from-otel"
    monkeypatch.setenv("CALLISTO_SERVICE_NAME", "from-callisto")
    assert make_logger()._config.service_name == "from-callisto"


# -- 2. auth header and endpoint ------------------------------------------------------


def test_authorization_header_endpoint_and_user_agent():
    sender = FakeSender()
    logger = make_logger(sender)
    logger.info("x")
    logger.close()
    call = sender.calls[0]
    assert call["url"] == ENDPOINT
    expected = base64.b64encode(f"{PK}:{SK}".encode()).decode()
    assert call["headers"]["Authorization"] == f"Basic {expected}"
    assert call["headers"]["Content-Type"] == "application/json"
    assert call["headers"]["User-Agent"] == "callisto-sdk-python/0.3.0"
    assert call["timeout"] == 10.0


def test_credentials_and_endpoint_from_env(monkeypatch):
    monkeypatch.setenv("CALLISTO_LOGS_ACCESS_KEY", PK)
    monkeypatch.setenv("CALLISTO_LOGS_SECRET", SK)
    monkeypatch.setenv("CALLISTO_LOGS_ENDPOINT", ENDPOINT)
    sender = FakeSender()
    logger = CallistoLogger(sender=sender, clock=lambda: FIXED_NS)
    assert logger.enabled
    logger.info("x")
    logger.close()
    assert sender.calls[0]["url"] == ENDPOINT


def test_default_endpoint():
    logger = CallistoLogger(logs_access_key=PK, logs_secret=SK, sender=FakeSender())
    assert logger.endpoint == "https://logs.callistosignal.com/v1/logs"
    logger.close()


# -- 3. severity and AnyValue ----------------------------------------------------------


def test_severity_for_all_levels():
    sender = FakeSender()
    logger = make_logger(sender)
    logger.debug("d")
    logger.info("i")
    logger.warn("w")
    logger.error("e")
    logger.fatal("f")
    logger.log("warn", "via log")
    logger.close()
    got = [(r["severityNumber"], r["severityText"]) for r in sender.records()]
    assert got == [
        (5, "DEBUG"), (9, "INFO"), (13, "WARN"), (17, "ERROR"), (21, "FATAL"), (13, "WARN"),
    ]


def test_any_value_mapping():
    sender = FakeSender()
    logger = make_logger(sender)
    logger.info(
        123,
        {
            "s": "text",
            "i": 7,
            "neg": -3,
            "f": 1.5,
            "nan": float("nan"),
            "inf": float("inf"),
            "ninf": float("-inf"),
            "none": None,
            "list": [1, "a", None, [True]],
            "map": {"k": 2, "drop": None, "inner": {"x": 0.25}},
            "obj": object.__new__(_Stringy),
        },
    )
    logger.close()
    record = only_record(sender)
    assert record["body"] == {"stringValue": "123"}
    attrs = attrs_of(record)
    assert "none" not in attrs
    assert attrs["s"] == {"stringValue": "text"}
    assert attrs["i"] == {"intValue": "7"}
    assert attrs["neg"] == {"intValue": "-3"}
    assert attrs["f"] == {"doubleValue": 1.5}
    assert attrs["nan"] == {"stringValue": "NaN"}
    assert attrs["inf"] == {"stringValue": "Infinity"}
    assert attrs["ninf"] == {"stringValue": "-Infinity"}
    assert attrs["list"] == {"arrayValue": {"values": [
        {"intValue": "1"},
        {"stringValue": "a"},
        {},
        {"arrayValue": {"values": [{"boolValue": True}]}},
    ]}}
    assert attrs["map"] == {"kvlistValue": {"values": [
        {"key": "k", "value": {"intValue": "2"}},
        {"key": "inner", "value": {"kvlistValue": {"values": [
            {"key": "x", "value": {"doubleValue": 0.25}},
        ]}}},
    ]}}
    assert attrs["obj"] == {"stringValue": "stringy"}


class _Stringy:
    def __str__(self):
        return "stringy"


def test_nesting_deeper_than_five_levels_is_stringified():
    deep = {"l2": {"l3": {"l4": {"l5": {"l6": {"x": 1}}}}}}
    value = _logs._any_value(deep)
    # Containers at depths 1..5 are kept; the one at depth 6 is stringified.
    for key in ("l2", "l3", "l4", "l5", "l6"):
        value = value["kvlistValue"]["values"][0]
        assert value["key"] == key
        value = value["value"]
    assert value == {"stringValue": str({"x": 1})}


def test_nested_lists_and_maps_are_capped_at_128_items():
    value = _logs._any_value(
        {"list": list(range(300)), "map": {f"k{i}": i for i in range(300)}, "small": [1, 2]}
    )
    by_key = {kv["key"]: kv["value"] for kv in value["kvlistValue"]["values"]}
    items = by_key["list"]["arrayValue"]["values"]
    assert len(items) == 128
    assert items[-1] == {"intValue": "127"}
    entries = by_key["map"]["kvlistValue"]["values"]
    assert len(entries) == 128
    assert entries[-1]["key"] == "k127"
    assert len(by_key["small"]["arrayValue"]["values"]) == 2
    top = _logs._any_value(list(range(500)))
    assert len(top["arrayValue"]["values"]) == 128


def _count_values(any_values):
    """Number of encoded AnyValues (scalars + containers) in a list of key/values."""
    total = 0
    stack = [kv["value"] for kv in any_values]
    while stack:
        value = stack.pop()
        total += 1
        if "arrayValue" in value:
            stack.extend(value["arrayValue"]["values"])
        elif "kvlistValue" in value:
            stack.extend(kv["value"] for kv in value["kvlistValue"]["values"])
    return total


@pytest.mark.parametrize("levels", [5, 12])
def test_shared_sub_lists_cannot_explode_the_record(levels):
    import time

    shared = list(range(128))
    for _ in range(levels - 1):
        shared = [shared] * 128  # the same list referenced 128 times, at every level
    sender = FakeSender()
    logger = make_logger(sender)
    started = time.perf_counter()
    logger.info("bomb", {"a": shared, "b": {"c": shared}, "later": "dropped"})
    elapsed = time.perf_counter() - started
    logger.close()
    attrs = only_record(sender).get("attributes", [])
    assert elapsed < 0.1
    assert 0 < _count_values(attrs) <= _logs.MAX_RECORD_VALUES
    assert "later" not in {kv["key"] for kv in attrs}


def test_value_budget_drops_later_attributes():
    attrs = {f"k{i:02d}": list(range(100)) for i in range(30)}
    key_values = _logs._key_values(attrs, 1, _logs.MAX_ATTRIBUTES, _logs._Budget())
    assert _count_values(key_values) == _logs.MAX_RECORD_VALUES
    # 20 full lists (20 x 101 = 2020 values), then the 21st is cut after 27 values.
    assert len(key_values) == 21
    assert len(key_values[-1]["value"]["arrayValue"]["values"]) == 27


def test_too_deep_container_is_stringified_within_bounds():
    huge = list(range(10_000))
    nested = {"l2": {"l3": {"l4": {"l5": {"l6": huge}}}}}
    value = _logs._any_value(nested)
    for _ in range(5):
        value = value["kvlistValue"]["values"][0]["value"]
    text = value["stringValue"]
    assert len(text) == _logs.MAX_ATTR_VALUE_CHARS
    assert text.endswith("…")
    assert text.startswith("[0, 1, 2")


# -- 4. trace / span ids ------------------------------------------------------------------


def test_invalid_trace_and_span_ids_stay_attributes():
    sender = FakeSender()
    logger = make_logger(sender)
    logger.info("a", {"trace_id": "not-a-trace", "span_id": "0000000000000000"})
    logger.info("b", {"trace_id": "0" * 32, "span_id": "abc"})
    logger.close()
    first, second = sender.records()
    for record in (first, second):
        assert "traceId" not in record and "spanId" not in record
    assert attrs_of(first)["trace_id"] == {"stringValue": "not-a-trace"}
    assert attrs_of(first)["span_id"] == {"stringValue": "0000000000000000"}
    assert attrs_of(second)["trace_id"] == {"stringValue": "0" * 32}


# -- 5. batching ----------------------------------------------------------------------------


def test_hundred_records_trigger_a_send_without_flush():
    sender = FakeSender()
    logger = make_logger(sender)
    for i in range(100):
        logger.info(f"m{i}")
    assert sender.entered.wait(2)
    assert sender.batch_sizes() == [100]
    logger.info("rest")
    logger.flush()
    assert sender.batch_sizes() == [100, 1]
    logger.close()


def test_interval_sends_without_flush():
    sender = FakeSender()
    logger = make_logger(sender, flush_interval=0.05)
    logger.info("one")
    assert sender.entered.wait(2)
    assert sender.batch_sizes() == [1]
    logger.close()


def test_batches_are_cut_at_500_records():
    sender = FakeSender(block=True)
    logger = make_logger(sender)
    for i in range(100):
        logger.info(f"a{i}")
    assert sender.entered.wait(2)  # worker is now blocked inside the first send
    for i in range(1200):
        logger.info(f"b{i}")
    sender.release.set()
    logger.flush()
    assert sender.batch_sizes() == [100, 500, 500, 200]
    logger.close()


def test_batches_are_cut_at_about_one_megabyte():
    sender = FakeSender(block=True)
    logger = make_logger(sender)
    for i in range(100):
        logger.info("a")
    assert sender.entered.wait(2)
    big = "x" * 30_000  # ~30 KB per record -> ~33 records per MB
    for i in range(100):
        logger.info(big)
    sender.release.set()
    logger.flush()
    sizes = sender.batch_sizes()
    assert sizes[0] == 100 and sum(sizes[1:]) == 100 and len(sizes) > 2
    assert all(len(c["body"]) <= 1_100_000 for c in sender.calls)
    logger.close()


# -- 6. truncation ------------------------------------------------------------------------------


def test_body_and_attribute_values_are_truncated():
    sender = FakeSender()
    logger = make_logger(sender)
    attrs = {"long": "y" * 10_000, "nested": ["z" * 9_000]}
    attrs.update({f"k{i:03d}": i for i in range(200)})
    logger.info("x" * 40_000, attrs)
    logger.close()
    record = only_record(sender)
    body = record["body"]["stringValue"]
    assert len(body) == 32_768 and body.endswith("…") and body[:-1] == "x" * 32_767
    values = attrs_of(record)
    assert len(values["long"]["stringValue"]) == 8_192
    assert values["long"]["stringValue"].endswith("…")
    assert len(values["nested"]["arrayValue"]["values"][0]["stringValue"]) == 8_192
    assert len(record["attributes"]) == 128


# -- 7. responses and retries -------------------------------------------------------------------


def test_400_drops_without_retry():
    sender = FakeSender([400])
    sleep = FakeSleep()
    logger = make_logger(sender, sleep=sleep)
    logger.info("x")
    logger.flush()
    assert len(sender.calls) == 1
    assert sleep.delays == []
    assert logger.dropped_count == 1
    logger.info("y")
    logger.close()
    assert len(sender.calls) == 2  # not paused
    assert logger.dropped_count == 2


@pytest.mark.parametrize("status", [413, 415, 500, 302])
def test_other_non_retryable_statuses_drop_and_count(status):
    sender = FakeSender([status])
    logger = make_logger(sender, sleep=FakeSleep())
    logger.info("a")
    logger.info("b")
    logger.close()
    assert len(sender.calls) == 1
    assert logger.dropped_count == 2


def test_429_with_retry_after_retries_then_succeeds():
    sender = FakeSender([(429, "1"), 200])
    sleep = FakeSleep()
    logger = make_logger(sender, sleep=sleep)
    logger.info("x")
    logger.close()
    assert len(sender.calls) == 2
    assert sender.calls[0]["body"] == sender.calls[1]["body"]
    assert sleep.delays == [1.0]
    assert logger.dropped_count == 0


def test_retry_after_default_and_cap():
    assert _logs._parse_retry_after(None) == 5.0
    assert _logs._parse_retry_after("soon") == 5.0
    assert _logs._parse_retry_after("120") == 60.0
    assert _logs._parse_retry_after("2") == 2.0


def test_three_failed_attempts_drop_the_batch():
    sender = FakeSender([(503, None)])
    sleep = FakeSleep()
    logger = make_logger(sender, sleep=sleep)
    logger.info("x")
    logger.flush()
    assert len(sender.calls) == 3
    assert sleep.delays == [5.0, 5.0]
    assert logger.dropped_count == 1
    logger.close()
    assert len(sender.calls) == 3


def test_network_errors_retry_with_1s_then_5s_then_drop():
    sender = FakeSender([httpx.ConnectError("down")])
    sleep = FakeSleep()
    logger = make_logger(sender, sleep=sleep)
    logger.info("x")
    logger.close()
    assert len(sender.calls) == 3
    assert sleep.delays == [1.0, 5.0]
    assert logger.dropped_count == 1


def test_401_pauses_sending_for_60_seconds():
    now = [1000.0]
    sender = FakeSender([401, 200])
    logger = make_logger(sender, monotonic=lambda: now[0])
    logger.info("first")
    logger.flush()
    assert len(sender.calls) == 1
    assert logger.dropped_count == 1

    now[0] += 30
    logger.info("during pause")
    logger.flush()
    assert len(sender.calls) == 1
    assert logger.dropped_count == 2

    now[0] += 31
    logger.info("after pause")
    logger.flush()
    assert len(sender.calls) == 2
    assert only_record_at(sender, 1)["body"] == {"stringValue": "after pause"}
    assert logger.dropped_count == 2
    logger.close()


def test_401_drops_and_counts_records_queued_behind_the_rejected_batch():
    now = [0.0]
    sender = FakeSender([401], block=True)
    logger = make_logger(sender, monotonic=lambda: now[0])
    for i in range(100):
        logger.info("a")
    assert sender.entered.wait(2)  # batch of 100 in flight
    for i in range(30):
        logger.info("queued behind")
    sender.release.set()
    logger.flush()
    assert len(sender.calls) == 1
    assert logger.dropped_count == 130  # the rejected batch + the 30 cleared
    logger.info("during pause")
    assert logger.dropped_count == 131
    logger.close()
    assert len(sender.calls) == 1


def test_403_pauses_too():
    now = [0.0]
    sender = FakeSender([403])
    logger = make_logger(sender, monotonic=lambda: now[0])
    logger.info("a")
    logger.flush()
    logger.info("b")
    logger.close()
    assert len(sender.calls) == 1


def only_record_at(sender, index):
    records = sender.records(index)
    assert len(records) == 1
    return records[0]


# -- 8. full queue -------------------------------------------------------------------------------


def test_full_queue_drops_and_counts():
    sender = FakeSender(block=True)
    logger = make_logger(sender)
    for i in range(100):
        logger.info("a")
    assert sender.entered.wait(2)  # first batch taken; worker blocked
    for i in range(2005):
        logger.info("b")
    assert logger.dropped_count == 5
    sender.release.set()
    logger.close()
    assert sum(sender.batch_sizes()) == 2100
    assert logger.dropped_count == 5


# -- fork safety -------------------------------------------------------------------------------


def _assert_child_starts_fresh(logger, sender, parent_worker):
    assert logger._worker is None  # no thread until the child's first record
    assert logger.dropped_count == 0
    assert logger._state.buffer == collections.deque()
    assert logger._state.paused_until is None
    logger.info("from child")
    child_worker = logger._worker
    assert child_worker is not None and child_worker is not parent_worker
    assert child_worker.is_alive()
    logger.flush()
    bodies = [r["body"]["stringValue"] for p in sender.payloads()
              for r in p["resourceLogs"][0]["scopeLogs"][0]["logRecords"]]
    assert "from parent" not in bodies
    assert bodies[-1] == "from child"
    logger.close()


def test_at_fork_hook_resets_queue_pause_and_worker():
    now = [0.0]
    sender = FakeSender([200])
    logger = make_logger(sender, monotonic=lambda: now[0])
    logger.info("from parent")  # queued, not flushed (interval is 30 s)
    logger._count_dropped(3)
    logger._state.paused_until = 500.0
    parent_worker = logger._worker

    _logs._after_fork_in_child()  # what os.register_at_fork runs in the child

    assert sender.calls == []
    _assert_child_starts_fresh(logger, sender, parent_worker)


def test_pid_change_resets_without_the_at_fork_hook(monkeypatch):
    sender = FakeSender([200])
    logger = make_logger(sender)
    logger.info("from parent")
    parent_worker = logger._worker
    child_pid = _logs.os.getpid() + 1
    monkeypatch.setattr(_logs.os, "getpid", lambda: child_pid)
    logger.flush()  # any call in the child notices the new PID and resets
    assert sender.calls == []
    _assert_child_starts_fresh(logger, sender, parent_worker)


def test_parent_worker_in_flight_never_touches_the_child_queue():
    sender = FakeSender(block=True)
    logger = make_logger(sender)
    for i in range(100):
        logger.info("from parent")
    assert sender.entered.wait(2)  # parent worker blocked mid-send
    for i in range(5):
        logger.info("from parent")
    parent_worker = logger._worker

    _logs._after_fork_in_child()
    logger.info("from child")
    sender.release.set()
    parent_worker.join(2)
    assert not parent_worker.is_alive()  # superseded worker exited
    logger.flush()
    logger.close()
    sizes = sender.batch_sizes()
    assert sizes == [100, 1]
    assert sender.records(1)[0]["body"] == {"stringValue": "from child"}


def test_at_fork_hook_is_registered():
    if not hasattr(os, "register_at_fork"):
        pytest.skip("platform without os.register_at_fork")
    assert callable(_logs._after_fork_in_child)


# -- 9. disabled logger ---------------------------------------------------------------------------


def test_disabled_logger_is_a_no_op_without_a_thread():
    before = {t.name for t in threading.enumerate()}
    sender = FakeSender()
    logger = CallistoLogger(sender=sender)
    assert not logger.enabled
    assert logger._worker is None
    logger.info("nothing", {"a": 1})
    logger.flush()
    logger.close()
    assert sender.calls == []
    assert logger.dropped_count == 0
    after = {t.name for t in threading.enumerate()}
    assert "callisto-logs" not in (after - before)


def test_calls_after_close_are_no_ops():
    sender = FakeSender()
    logger = make_logger(sender)
    logger.close()
    logger.info("late")
    logger.flush()
    logger.close()
    assert sender.calls == []
    assert not logger._worker.is_alive()


def test_close_is_bounded_when_ingest_hangs():
    sender = FakeSender(block=True)
    logger = make_logger(sender, flush_timeout=0.2)
    logger.info("x")
    import time

    start = time.monotonic()
    logger.close()
    assert time.monotonic() - start < 1.0
    logger_records_after = logger.dropped_count
    sender.release.set()
    assert logger_records_after == 0  # the in-flight record is not lost yet


def test_records_left_when_close_gives_up_are_counted():
    sender = FakeSender(block=True)
    logger = make_logger(sender, flush_timeout=0.1)
    for i in range(100):
        logger.info("a")
    assert sender.entered.wait(2)
    for i in range(7):
        logger.info("never sent")
    logger.close()
    sender.release.set()
    logger._worker.join(2)
    assert not logger._worker.is_alive()
    assert logger.dropped_count == 7
    assert len(sender.calls) == 1


def test_log_call_never_raises_on_bad_input():
    class Boom:
        def __str__(self):
            raise RuntimeError("no")

        def __repr__(self):
            raise RuntimeError("no")

    sender = FakeSender()
    logger = make_logger(sender)
    logger.info(Boom(), {"x": Boom()})
    logger.info("ok", "not-a-mapping")  # type: ignore[arg-type]
    logger.log("nonsense", "unknown level")
    logger.close()
    records = sender.records()
    assert records[0]["body"] == {"stringValue": "<unprintable>"}
    assert records[1]["body"] == {"stringValue": "ok"}
    assert records[2]["severityText"] == "INFO"


# -- 10. configuration errors -----------------------------------------------------------------------


@pytest.mark.parametrize(
    "kwargs",
    [
        {"logs_access_key": PK},
        {"logs_secret": SK},
        {"logs_access_key": PK.upper().replace("PK_", "pk_"), "logs_secret": SK},
        {"logs_access_key": "pk_123", "logs_secret": SK},
        {"logs_access_key": PK, "logs_secret": "sk_" + "0" * 47},
        {"logs_access_key": SK, "logs_secret": PK},
        {"logs_access_key": PK, "logs_secret": SK, "logs_endpoint": "logs.test/v1/logs"},
        {"logs_access_key": PK, "logs_secret": SK, "logs_endpoint": "ftp://logs.test/v1/logs"},
    ],
)
def test_half_or_malformed_configuration_raises(kwargs):
    with pytest.raises(ValueError):
        CallistoLogger(sender=FakeSender(), **kwargs)


def test_half_credentials_from_env_raise(monkeypatch):
    monkeypatch.setenv("CALLISTO_LOGS_SECRET", SK)
    with pytest.raises(ValueError):
        CallistoLogger()


@pytest.mark.parametrize(
    "endpoint",
    [
        "http://logs.test/v1/logs",
        "http://logs.callistosignal.com/v1/logs",
        "http://localhost.evil.test/v1/logs",
        "http://127.0.0.2/v1/logs",
        "https:///v1/logs",
    ],
)
def test_standalone_rejects_a_non_https_endpoint(endpoint):
    with pytest.raises(ValueError, match="https"):
        CallistoLogger(logs_access_key=PK, logs_secret=SK, logs_endpoint=endpoint, sender=FakeSender())


@pytest.mark.parametrize(
    "endpoint",
    [
        "https://logs.test/v1/logs",
        "HTTPS://logs.test/v1/logs",
        "http://localhost/v1/logs",
        "http://localhost:4318/v1/logs",
        "http://127.0.0.1:4318/v1/logs",
        "http://[::1]:4318/v1/logs",
        "http://logs.localhost/v1/logs",
    ],
)
def test_https_and_loopback_http_endpoints_are_accepted(endpoint):
    sender = FakeSender()
    logger = CallistoLogger(
        logs_access_key=PK, logs_secret=SK, logs_endpoint=endpoint, sender=sender, flush_interval=30.0
    )
    assert logger.enabled
    logger.info("hi")
    logger.close()
    assert sender.calls[0]["url"] == endpoint


def test_standalone_rejects_a_non_https_endpoint_from_env(monkeypatch):
    monkeypatch.setenv("CALLISTO_LOGS_ACCESS_KEY", PK)
    monkeypatch.setenv("CALLISTO_LOGS_SECRET", SK)
    monkeypatch.setenv("CALLISTO_LOGS_ENDPOINT", "http://logs.test/v1/logs")
    with pytest.raises(ValueError):
        CallistoLogger()


@pytest.mark.parametrize(
    "env",
    [
        {"CALLISTO_LOGS_SECRET": SK},
        {"CALLISTO_LOGS_ACCESS_KEY": PK},
        {"CALLISTO_LOGS_ACCESS_KEY": PK, "CALLISTO_LOGS_SECRET": SK[:-1]},
        {"CALLISTO_LOGS_ACCESS_KEY": SK, "CALLISTO_LOGS_SECRET": PK},
        {
            "CALLISTO_LOGS_ACCESS_KEY": PK,
            "CALLISTO_LOGS_SECRET": SK,
            "CALLISTO_LOGS_ENDPOINT": "http://logs.test/v1/logs",
        },
    ],
)
def test_client_with_bad_logs_env_warns_once_and_disables_logs(monkeypatch, env):
    for name, value in env.items():
        monkeypatch.setenv(name, value)
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        client = Client(client_id="cid", api_key="key", base_url="https://api.test/v1")
    assert len(caught) == 1
    text = str(caught[0].message)
    assert "Logs are disabled" in text
    for secret in (PK, SK, SK[:-1]):
        assert secret not in text
    assert not client.logs.enabled
    client.logs.info("dropped silently")
    client.close()


def test_client_with_half_logs_arguments_does_not_raise():
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        client = Client(client_id="cid", api_key="key", logs_access_key=PK)
    assert len(caught) == 1
    assert PK not in str(caught[0].message)
    assert not client.logs.enabled
    client.close()


def test_client_with_valid_logs_settings_does_not_warn():
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        client = Client(
            client_id="cid",
            api_key="key",
            logs_access_key=PK,
            logs_secret=SK,
            logs_endpoint="http://localhost:4318/v1/logs",
        )
    assert caught == []
    assert client.logs.enabled
    client.logs.close()
    client.close()


def test_client_without_messaging_credentials_still_raises(monkeypatch):
    monkeypatch.delenv("CALLISTO_CLIENT_ID", raising=False)
    monkeypatch.delenv("CALLISTO_API_KEY", raising=False)
    with pytest.raises(ValueError):
        Client(logs_access_key=PK)


# -- 11. isolation from messaging credentials ------------------------------------------------------


def test_client_logs_and_messaging_never_share_credentials():
    client_id = "cid-messaging-7f3a"
    api_key = "apikey-messaging-91bc"
    with respx.mock(assert_all_called=True) as router:
        api = router.get("https://api.test/v1/sms/balance").mock(
            return_value=httpx.Response(200, json={"credit": 1.0, "currency": "XOF"})
        )
        logs = router.post(ENDPOINT).mock(return_value=httpx.Response(200, json={}))
        client = Client(
            client_id=client_id,
            api_key=api_key,
            base_url="https://api.test/v1",
            logs_access_key=PK,
            logs_secret=SK,
            logs_endpoint=ENDPOINT,
            service_name="svc",
        )
        assert client.logs.enabled
        client.balance.get()
        client.logs.info("hello", {"k": "v"})
        client.close()

    assert logs.call_count == 1
    log_request = logs.calls[0].request
    blob = json.dumps(dict(log_request.headers)) + log_request.content.decode()
    messaging_basic = base64.b64encode(f"{client_id}:{api_key}".encode()).decode()
    for secret in (client_id, api_key, messaging_basic):
        assert secret not in blob
    assert log_request.headers["authorization"] == (
        "Basic " + base64.b64encode(f"{PK}:{SK}".encode()).decode()
    )

    api_request = api.calls[0].request
    api_blob = json.dumps(dict(api_request.headers)) + api_request.content.decode() + str(api_request.url)
    logs_basic = base64.b64encode(f"{PK}:{SK}".encode()).decode()
    for secret in (PK, SK, logs_basic):
        assert secret not in api_blob


def test_client_logs_is_a_no_op_without_logs_key():
    client = Client(client_id="cid", api_key="key", base_url="https://api.test/v1")
    assert not client.logs.enabled
    client.logs.info("nothing")
    client.close()


def test_client_close_closes_injected_logger():
    sender = FakeSender()
    logger = make_logger(sender)
    client = Client(client_id="cid", api_key="key", base_url="https://api.test/v1", logs=logger)
    assert client.logs is logger
    logger.info("bye")
    client.close()
    assert len(sender.calls) == 1
    assert logger._closed


# -- 12. logging.Handler adapter ------------------------------------------------------------------


@pytest.fixture
def std_logger():
    log = logging.getLogger("tests.callisto.handler")
    log.setLevel(1)
    log.propagate = False
    yield log
    for handler in list(log.handlers):
        log.removeHandler(handler)


def test_handler_level_mapping(std_logger):
    sender = FakeSender()
    logger = make_logger(sender)
    std_logger.addHandler(CallistoLogHandler(logger))
    std_logger.log(5, "trace-ish")
    std_logger.debug("d")
    std_logger.info("i")
    std_logger.warning("w")
    std_logger.error("e")
    std_logger.critical("c")
    logger.close()
    assert [r["severityText"] for r in sender.records()] == [
        "DEBUG", "DEBUG", "INFO", "WARN", "ERROR", "FATAL",
    ]


def test_handler_message_logger_name_and_extra(std_logger):
    sender = FakeSender()
    logger = make_logger(sender)
    std_logger.addHandler(CallistoLogHandler(logger))
    std_logger.info(
        "paid %s for %d", "order", 42,
        extra={"order_id": 42, "trace_id": "5b8efff798038103d269b633813fc60c"},
    )
    logger.close()
    record = only_record(sender)
    assert record["body"] == {"stringValue": "paid order for 42"}
    assert record["traceId"] == "5b8efff798038103d269b633813fc60c"
    attrs = attrs_of(record)
    assert attrs == {
        "logger.name": {"stringValue": "tests.callisto.handler"},
        "order_id": {"intValue": "42"},
    }


def test_handler_exc_info(std_logger):
    sender = FakeSender()
    logger = make_logger(sender)
    std_logger.addHandler(CallistoLogHandler(logger))
    try:
        raise KeyError("missing")
    except KeyError:
        std_logger.exception("lookup failed")
    logger.close()
    record = only_record(sender)
    assert record["severityText"] == "ERROR"
    attrs = attrs_of(record)
    assert attrs["exception.type"] == {"stringValue": "KeyError"}
    assert attrs["exception.message"] == {"stringValue": "'missing'"}
    stack = attrs["exception.stacktrace"]["stringValue"]
    assert stack.startswith("Traceback (most recent call last)")
    assert "KeyError: 'missing'" in stack
    assert "test_handler_exc_info" in stack


def test_handler_drops_records_emitted_while_sending(std_logger):
    root = logging.getLogger()
    handler_holder = {}

    def chatty_sender(url, body, headers, timeout):
        # What httpx does on the worker thread: log through the stdlib.
        logging.getLogger("httpx").info("HTTP Request: POST %s", url)
        std_logger.warning("inside send")
        return 200

    calls = []

    def recording_sender(url, body, headers, timeout):
        calls.append(json.loads(body))
        return chatty_sender(url, body, headers, timeout)

    logger = make_logger(recording_sender)
    handler = CallistoLogHandler(logger)
    handler_holder["h"] = handler
    std_logger.addHandler(handler)
    root.addHandler(handler)
    previous_level = root.level
    root.setLevel(logging.DEBUG)
    try:
        std_logger.info("outside")
        logger.flush()
        logger.flush()
    finally:
        root.removeHandler(handler)
        root.setLevel(previous_level)
    logger.close()
    assert len(calls) == 1
    bodies = [r["body"]["stringValue"] for r in calls[0]["resourceLogs"][0]["scopeLogs"][0]["logRecords"]]
    assert bodies == ["outside"]


def test_handler_reentrant_emit_is_dropped(std_logger):
    sender = FakeSender()
    logger = make_logger(sender)
    std_logger.addHandler(CallistoLogHandler(logger))

    class Loud:
        def __str__(self):
            std_logger.info("from __str__")
            return "loud"

    std_logger.info("value %s", Loud())
    logger.close()
    assert [r["body"]["stringValue"] for r in sender.records()] == ["value loud"]


def test_handler_on_disabled_logger_is_a_no_op(std_logger):
    logger = CallistoLogger()
    std_logger.addHandler(CallistoLogHandler(logger))
    std_logger.error("nothing happens")
    logger.close()
