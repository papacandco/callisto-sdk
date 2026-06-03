import json
import httpx
import respx

BASE = "https://api.test/v1"


@respx.mock
def test_send_sms(client):
    route = respx.post(f"{BASE}/sms/send").mock(
        return_value=httpx.Response(200, json={
            "total_amount": 5, "available_credit": 95, "status": "sent",
            "recipient_count": 1, "scheduled": False, "messages": [],
        })
    )
    res = client.sms.send(sender="Acme", to="+2250700000000", message="Hi")
    body = json.loads(route.calls.last.request.content)
    assert body == {"sender": "Acme", "to": "+2250700000000", "message": "Hi"}
    assert res.status == "sent"


@respx.mock
def test_send_sms_optional_fields(client):
    route = respx.post(f"{BASE}/sms/send").mock(
        return_value=httpx.Response(200, json={
            "total_amount": 0, "available_credit": 0, "status": "sent",
            "recipient_count": 2, "scheduled": True, "messages": [],
        })
    )
    client.sms.send(
        sender="Acme", to=["+225070", "+225071"], message="Hi",
        notify_url="https://x/hook", scheduled_at="2026-06-02 10:00:00",
    )
    body = json.loads(route.calls.last.request.content)
    assert body["notify_url"] == "https://x/hook"
    assert body["scheduled_at"] == "2026-06-02 10:00:00"
    assert body["to"] == ["+225070", "+225071"]


@respx.mock
def test_list_and_status(client):
    respx.get(f"{BASE}/sms/messages").mock(return_value=httpx.Response(200, json={
        "items": [{
            "id": "m1", "sender_name": "Acme", "recipient": "+2250700000000",
            "content": "Hi", "status": "sent",
            "created_at": "2026-06-01 10:00:00", "updated_at": "2026-06-01 10:01:00",
        }],
        "total": 1, "per_page": 15, "current_page": 1,
        "next": 1, "previous": 1, "total_pages": 1,
    }))
    status_route = respx.get(f"{BASE}/sms/abc").mock(
        return_value=httpx.Response(200, json={
            "id": "abc", "sender_name": "Acme", "recipient": "+2250700000000",
            "content": "Hi", "status": "delivered",
            "created_at": "2026-06-01 10:00:00", "updated_at": "2026-06-01 10:05:00",
        })
    )
    out = client.sms.list(page=2, per_page=50)
    assert out.total == 1
    assert out.items[0].status == "sent"
    res = client.sms.get_status("abc")
    assert res.status == "delivered"
    assert str(status_route.calls.last.request.url) == f"{BASE}/sms/abc"
