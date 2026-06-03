import base64
import httpx
import pytest
import respx

from callisto_sdk._config import resolve_config
from callisto_sdk._http import Transport
from callisto_sdk.errors import NotFoundError, RateLimitError

BASE = "https://api.test/v1"


def _transport():
    cfg = resolve_config(client_id="cid", api_key="secret", base_url=BASE)
    return Transport(cfg)


@respx.mock
def test_sends_basic_auth_and_decodes_json():
    route = respx.get(f"{BASE}/sms/balance").mock(
        return_value=httpx.Response(200, json={"ok": True})
    )
    out = _transport().request("GET", "/sms/balance")
    assert out == {"ok": True}
    sent = route.calls.last.request
    expected = "Basic " + base64.b64encode(b"cid:secret").decode()
    assert sent.headers["authorization"] == expected
    assert sent.headers["accept"] == "application/json"


@respx.mock
def test_sends_body_and_query():
    route = respx.post(f"{BASE}/sms/send").mock(return_value=httpx.Response(200, json={}))
    _transport().request("POST", "/sms/send", body={"message": "hi"}, query={"page": 2, "skip": None})
    req = route.calls.last.request
    assert req.url.params["page"] == "2"
    assert "skip" not in req.url.params
    assert req.content == b'{"message":"hi"}'


@respx.mock
def test_maps_error_status():
    respx.get(f"{BASE}/sms/x").mock(
        return_value=httpx.Response(404, json={"message": "Message not found"})
    )
    with pytest.raises(NotFoundError) as exc:
        _transport().request("GET", "/sms/x")
    assert exc.value.status_code == 404
    assert exc.value.message == "Message not found"


@respx.mock
def test_rate_limit_surfaces_retry_after():
    respx.get(f"{BASE}/sms/y").mock(
        return_value=httpx.Response(429, headers={"Retry-After": "30"}, json={"message": "slow down"})
    )
    with pytest.raises(RateLimitError) as exc:
        _transport().request("GET", "/sms/y")
    assert exc.value.status_code == 429
    assert exc.value.retry_after == 30
