import json
import httpx
import pytest
import respx

from callisto_sdk.errors import ValidationError

BASE = "https://api.test/v1"


@respx.mock
def test_notify_send(client):
    route = respx.post(f"{BASE}/notify/send").mock(
        return_value=httpx.Response(200, json={
            "status": "queued", "topic": "t", "queued_events": 1, "topic_messages": [],
        })
    )
    res = client.notify.send(topic="welcome", sms=[{"to": "+225"}])
    body = json.loads(route.calls.last.request.content)
    assert body == {"topic": "welcome", "sms": [{"to": "+225"}]}
    assert res.status == "queued"


def test_notify_requires_event_block(client):
    with pytest.raises(ValidationError, match="event block"):
        client.notify.send(topic="welcome")
