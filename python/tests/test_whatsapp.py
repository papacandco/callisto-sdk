import httpx
import respx

from callisto_sdk.models import WhatsAppMediaType

BASE = "https://api.test/v1"
SEND = {
    "id": "m1", "instance_id": "i1", "recipient": {}, "message_type": "text",
    "status": "pending", "scheduled": False,
}

INSTANCE = {
    "id": "inst_1", "code": "inst_1", "client_id": "c1", "name": "Main",
    "phone_number": "+2250700000000", "phone_name": "Main Line", "status": "connected",
    "billing_status": "active", "trial_days_remaining": 0, "monthly_fee": 10.0,
    "messages_sent_today": 5, "messages_sent_month": 50, "daily_limit": 1000,
    "last_message_at": "2026-06-01 10:00:00", "webhook_url": "https://x/hook",
    "is_active": True, "created_at": "2026-05-01 10:00:00", "updated_at": "2026-06-01 10:00:00",
}

MESSAGE = {
    "id": "msg_9", "instance_id": "inst_1", "client_id": "c1", "api_client_id": "ac1",
    "recipient": "+2250700000000", "recipient_name": "Bob", "message_type": "text",
    "content": "Hi", "media_url": None, "media_mimetype": None, "media_filename": None,
    "extra_data": None, "direction": "outbound", "status": "delivered",
    "whatsapp_message_id": "wamid.1", "error_code": None, "error_message": None,
    "retry_count": 0, "is_billable": True, "cost": 0.5,
    "sent_at": "2026-06-01 10:00:00", "delivered_at": "2026-06-01 10:00:05",
    "read_at": None, "scheduled_at": None,
    "created_at": "2026-06-01 10:00:00", "updated_at": "2026-06-01 10:00:05",
    "processor_identifier": "proc_1",
}


@respx.mock
def test_create_and_read_instances(client):
    respx.post(f"{BASE}/whatsapp/instances").mock(return_value=httpx.Response(201, json=INSTANCE))
    list_route = respx.get(f"{BASE}/whatsapp/instances").mock(return_value=httpx.Response(200, json={
        "items": [INSTANCE], "total": 1, "per_page": 15, "current_page": 1,
        "next": 1, "previous": 1, "total_pages": 1,
    }))
    get_route = respx.get(f"{BASE}/whatsapp/inst_1").mock(return_value=httpx.Response(200, json=INSTANCE))
    qr_route = respx.get(f"{BASE}/whatsapp/inst_1/qr").mock(return_value=httpx.Response(200, json={"qr_code": "x"}))
    st_route = respx.get(f"{BASE}/whatsapp/inst_1/status").mock(return_value=httpx.Response(200, json={"status": "connected"}))
    created = client.whatsapp.create_instance(name="Main")
    assert created.id == "inst_1"
    assert created.status == "connected"
    out = client.whatsapp.list_instances(page=3)
    assert out.total == 1
    assert out.items[0].name == "Main"
    inst = client.whatsapp.get_instance("inst_1")
    assert inst.phone_number == "+2250700000000"
    client.whatsapp.get_qr("inst_1")
    client.whatsapp.get_status("inst_1")
    assert list_route.calls.last.request.url.params["page"] == "3"
    assert str(get_route.calls.last.request.url) == f"{BASE}/whatsapp/inst_1"
    assert qr_route.called and st_route.called


@respx.mock
def test_messages(client):
    list_route = respx.get(f"{BASE}/whatsapp/inst_1/messages").mock(return_value=httpx.Response(200, json={
        "items": [MESSAGE], "total": 1, "per_page": 15, "current_page": 1,
        "next": 1, "previous": 1, "total_pages": 1,
    }))
    msg_route = respx.get(f"{BASE}/whatsapp/messages/msg_9").mock(return_value=httpx.Response(200, json=MESSAGE))
    out = client.whatsapp.list_messages("inst_1", page=2)
    assert out.total == 1
    assert out.items[0].id == "msg_9"
    msg = client.whatsapp.get_message("msg_9")
    assert msg.status == "delivered"
    assert msg.cost == 0.5
    assert list_route.calls.last.request.url.params["page"] == "2"
    assert str(msg_route.calls.last.request.url) == f"{BASE}/whatsapp/messages/msg_9"


@respx.mock
def test_send_variants(client):
    text = respx.post(f"{BASE}/whatsapp/inst_1/send/text").mock(return_value=httpx.Response(200, json=SEND))
    media = respx.post(f"{BASE}/whatsapp/inst_1/send/media").mock(return_value=httpx.Response(200, json=SEND))
    buttons = respx.post(f"{BASE}/whatsapp/inst_1/send/buttons").mock(return_value=httpx.Response(200, json=SEND))
    location = respx.post(f"{BASE}/whatsapp/inst_1/send/location").mock(return_value=httpx.Response(200, json=SEND))
    listmsg = respx.post(f"{BASE}/whatsapp/inst_1/send/list").mock(return_value=httpx.Response(200, json=SEND))
    r = client.whatsapp.send_text("inst_1", to="+225", message="hi")
    client.whatsapp.send_media("inst_1", to="+225", type=WhatsAppMediaType.IMAGE, media_url="u")
    client.whatsapp.send_buttons("inst_1", to="+225", body="b", buttons=[{"id": "1", "title": "Yes"}])
    client.whatsapp.send_location("inst_1", to="+225", latitude=1.2, longitude=3.4)
    client.whatsapp.send_list("inst_1", to="+225", body="b", button_text="Open", sections=[])
    assert r.id == "m1"
    assert text.called and media.called and buttons.called and location.called and listmsg.called
