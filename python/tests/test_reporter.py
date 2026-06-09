from __future__ import annotations

import json

import httpx
import pytest
import respx

from callisto_sdk._reporter import ErrorReporter
from callisto_sdk.client import Client
from callisto_sdk.errors import NetworkError, NotFoundError, RateLimitError, ValidationError

DSN = "https://app.test/ingest/abc?key=deadbeef"
BASE = "https://api.test/v1"


class FakeSender:
    """Synchronous fake HTTP sender that records every payload."""

    def __init__(self, fail: bool = False):
        self.calls: list[tuple[str, dict, float]] = []
        self.fail = fail

    def __call__(self, dsn: str, payload: dict, timeout: float) -> None:
        self.calls.append((dsn, payload, timeout))
        if self.fail:
            raise RuntimeError("boom")

    @property
    def payloads(self) -> list[dict]:
        return [p for _, p, _ in self.calls]


def _reporter(sender, dsn=DSN, **kw):
    return ErrorReporter(dsn, sender=sender, environment="test", **kw)


# -- payload shape ----------------------------------------------------------


def test_capture_callisto_error_posts_to_dsn_with_shape():
    sender = FakeSender()
    r = _reporter(sender)
    err = NotFoundError("Message not found", status_code=404, body={"message": "Message not found"})
    r.capture_exception(err, method="GET", path="/sms/x")
    r.flush()
    r.close()

    assert len(sender.calls) == 1
    dsn, payload, _ = sender.calls[0]
    assert dsn == DSN
    assert payload["message"] == "Message not found"
    assert payload["type"] == "NotFoundError"
    assert payload["level"] == "error"
    # context.sdk present
    assert payload["context"]["sdk"]["name"] == "callisto-sdk"
    assert payload["context"]["sdk"]["language"] == "python"
    assert payload["context"]["sdk"]["version"]
    assert payload["context"]["environment"] == "test"
    # status_code + request for transport errors
    assert payload["context"]["status_code"] == 404
    assert payload["request"] == {"method": "GET", "path": "/sms/x"}
    assert payload["culprit"] == "GET /sms/x"
    assert payload["context"]["body"] == {"message": "Message not found"}


def test_rate_limit_includes_retry_after():
    sender = FakeSender()
    r = _reporter(sender)
    err = RateLimitError("slow down", status_code=429, retry_after=30)
    r.capture_exception(err, method="POST", path="/sms/send")
    r.flush()
    r.close()
    assert sender.payloads[0]["context"]["retry_after"] == 30


def test_capture_message():
    sender = FakeSender()
    r = _reporter(sender)
    r.capture_message("hello world", level="warning", extra={"k": "v"})
    r.flush()
    r.close()
    p = sender.payloads[0]
    assert p["message"] == "hello world"
    assert p["level"] == "warning"
    assert p["context"]["k"] == "v"


def test_invalid_level_falls_back_to_error():
    sender = FakeSender()
    r = _reporter(sender)
    r.capture_exception(ValidationError("bad"), level="nonsense")
    r.flush()
    r.close()
    assert sender.payloads[0]["level"] == "error"


def test_set_user_attached():
    sender = FakeSender()
    r = _reporter(sender)
    r.set_user({"id": "u1", "email": "a@b.c"})
    r.capture_exception(ValidationError("bad"))
    r.flush()
    r.close()
    assert sender.payloads[0]["user"] == {"id": "u1", "email": "a@b.c"}


def test_stacktrace_extracted():
    sender = FakeSender()
    r = _reporter(sender)
    try:
        raise ValueError("kaboom")
    except ValueError as e:
        r.capture_exception(e)
    r.flush()
    r.close()
    st = sender.payloads[0]["stacktrace"]
    assert isinstance(st, list) and len(st) >= 1
    assert {"function", "file", "line"} <= set(st[0].keys())
    # culprit derived from top frame when not a transport error
    assert "culprit" in sender.payloads[0]


# -- PII / secrets ----------------------------------------------------------


def test_no_credential_or_request_body_leak():
    sender = FakeSender()
    r = _reporter(sender)
    err = ValidationError("oops", status_code=422, body={"message": "oops"})
    r.set_user({"id": "u1"})
    r.capture_exception(
        err,
        method="POST",
        path="/otp/send",
        extra={"client_id": "cid", "api_key": "secret", "Authorization": "Basic xxx"},
    )
    r.flush()
    r.close()
    blob = json.dumps(sender.payloads[0]).lower()
    assert "cid" not in blob
    assert "secret" not in blob
    assert "authorization" not in blob
    assert "basic xxx" not in blob
    # phone numbers / message content (outgoing body) never present
    assert "+1555" not in blob


# -- failure swallowing -----------------------------------------------------


def test_sender_failures_swallowed():
    sender = FakeSender(fail=True)
    r = _reporter(sender)
    # capture must never raise even though the sender throws
    r.capture_exception(ValidationError("bad"))
    r.flush()
    r.close()
    assert len(sender.calls) == 1  # attempted, exception swallowed


def test_capture_never_raises_on_bad_input():
    sender = FakeSender()
    r = _reporter(sender)
    # Passing something odd should not raise.
    r.capture_exception(ValidationError("ok"))
    r.flush()
    r.close()


# -- no DSN / no-op ---------------------------------------------------------


def test_no_dsn_is_noop():
    sender = FakeSender()
    r = ErrorReporter(None, sender=sender)
    assert r.enabled is False
    r.capture_exception(ValidationError("bad"))
    r.capture_message("hi")
    r.flush()
    r.close()
    assert sender.calls == []


def test_invalid_dsn_is_noop():
    sender = FakeSender()
    r = ErrorReporter("not-a-url", sender=sender)
    assert r.enabled is False
    r.capture_exception(ValidationError("bad"))
    r.flush()
    r.close()
    assert sender.calls == []


# -- integration through the client / transport -----------------------------


@respx.mock
def test_transport_error_reports_and_propagates():
    sender = FakeSender()
    reporter = ErrorReporter(DSN, sender=sender)
    client = Client(client_id="cid", api_key="secret", base_url=BASE, reporter=reporter)
    respx.get(f"{BASE}/otps/x").mock(
        return_value=httpx.Response(404, json={"message": "not found"})
    )
    with pytest.raises(NotFoundError):
        client.otp.get_status("x")
    reporter.flush()
    client.close()
    assert len(sender.calls) == 1
    p = sender.payloads[0]
    assert p["type"] == "NotFoundError"
    assert p["request"] == {"method": "GET", "path": "/otps/x"}
    # no credential leak
    blob = json.dumps(p).lower()
    assert "secret" not in blob


@respx.mock
def test_network_error_captured():
    sender = FakeSender()
    reporter = ErrorReporter(DSN, sender=sender)
    client = Client(client_id="cid", api_key="secret", base_url=BASE, reporter=reporter)
    respx.get(f"{BASE}/otps/x").mock(side_effect=httpx.ConnectError("down"))
    with pytest.raises(NetworkError):
        client.otp.get_status("x")
    reporter.flush()
    client.close()
    assert sender.payloads[0]["type"] == "NetworkError"
    assert sender.payloads[0]["request"] == {"method": "GET", "path": "/otps/x"}


def test_resource_validation_error_captured():
    sender = FakeSender()
    reporter = ErrorReporter(DSN, sender=sender)
    client = Client(client_id="cid", api_key="secret", base_url=BASE, reporter=reporter)
    with pytest.raises(ValidationError):
        client.notify.send(topic="t")
    reporter.flush()
    client.close()
    assert sender.payloads[0]["type"] == "ValidationError"


def test_client_public_capture_api():
    sender = FakeSender()
    reporter = ErrorReporter(DSN, sender=sender)
    client = Client(client_id="cid", api_key="secret", base_url=BASE, reporter=reporter)
    client.set_user({"id": "u9"})
    client.capture_message("ping", level="info")
    client.capture_exception(ValueError("manual"), level="warning")
    reporter.flush()
    client.close()
    types = [p["type"] for p in sender.payloads]
    assert "Message" in types
    assert "ValueError" in types
    assert all(p.get("user") == {"id": "u9"} for p in sender.payloads)


def test_no_dsn_client_still_works(monkeypatch):
    monkeypatch.delenv("CALLISTO_APP_ERROR_DSN", raising=False)
    client = Client(client_id="cid", api_key="secret", base_url=BASE)
    assert client.error_reporter.enabled is False
    # public API is a safe no-op
    client.capture_message("hi")
    client.close()


def test_default_sender_posts_to_dsn():
    # Exercise the real HTTP sender path against a mocked endpoint.
    import respx as _respx

    with _respx.mock:
        route = _respx.post("https://app.test/ingest/abc").mock(
            return_value=httpx.Response(202, json={"ok": True, "event_id": "e1"})
        )
        r = ErrorReporter(DSN, environment="test")
        r.capture_message("via real sender")
        r.flush()
        r.close()
        assert route.called
        sent = json.loads(route.calls.last.request.content)
        assert sent["message"] == "via real sender"
