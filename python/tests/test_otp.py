import json
import httpx
import pytest
import respx

from callisto_sdk.models import OtpProvider
from callisto_sdk.errors import ValidationError

BASE = "https://api.test/v1"


@respx.mock
def test_send_otp(client):
    route = respx.post(f"{BASE}/otp/send").mock(
        return_value=httpx.Response(200, json={
            "id": "o1", "provider": "sms", "recipient": {},
            "expires_at": "x", "expires_in": 300,
        })
    )
    res = client.otp.send(to="2250700000000", message="Code {code}")
    body = json.loads(route.calls.last.request.content)
    assert body == {"to": "2250700000000", "message": "Code {code}"}
    assert res.id == "o1"


def test_whatsapp_provider_requires_instance_code(client):
    with pytest.raises(ValidationError, match="instance_code"):
        client.otp.send(to="x", message="m", provider=OtpProvider.WHATSAPP)


@respx.mock
def test_verify_otp(client):
    respx.post(f"{BASE}/otp/verify").mock(
        return_value=httpx.Response(200, json={
            "id": "o1", "status": "verified", "verified": True, "verified_at": "now",
        })
    )
    res = client.otp.verify(otp_id="o1", code="12345")
    assert res.verified is True


@respx.mock
def test_status_and_list(client):
    status_route = respx.get(f"{BASE}/otps/o1").mock(return_value=httpx.Response(200, json={
        "otp_id": "o1", "status": "pending", "recipient": "+2250700000000",
        "expires_at": "2026-06-01 10:05:00", "verified_at": None,
        "attempts": 0, "created_at": "2026-06-01 10:00:00",
    }))
    list_route = respx.get(f"{BASE}/otps").mock(return_value=httpx.Response(200, json={
        "items": [{
            "id": "o1", "status": "verified", "recipient": "+2250700000000",
            "expires_at": "2026-06-01 10:05:00", "verified_at": "2026-06-01 10:02:00",
            "attempts": 1, "created_at": "2026-06-01 10:00:00",
        }],
        "total": 1, "per_page": 15, "current_page": 1,
        "next": 1, "previous": 1, "total_pages": 1,
    }))
    res = client.otp.get_status("o1")
    assert res.otp_id == "o1"
    out = client.otp.list(page=1, limit=10)
    assert out.items[0].id == "o1"
    assert out.total == 1
    assert str(status_route.calls.last.request.url) == f"{BASE}/otps/o1"
    assert list_route.calls.last.request.url.params["limit"] == "10"
