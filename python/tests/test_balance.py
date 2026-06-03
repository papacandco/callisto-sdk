import httpx
import respx

BASE = "https://api.test/v1"


@respx.mock
def test_get_balance(client):
    route = respx.get(f"{BASE}/sms/balance").mock(
        return_value=httpx.Response(200, json={"credit": 100.5, "currency": "XOF"})
    )
    bal = client.balance.get()
    assert bal.credit == 100.5
    assert route.calls.last.request.url.params["format"] == "full"


@respx.mock
def test_get_balance_with_currency(client):
    route = respx.get(f"{BASE}/sms/balance").mock(
        return_value=httpx.Response(200, json={"credit": 1, "currency": "USD"})
    )
    client.balance.get(format="short", currency="USD")
    params = route.calls.last.request.url.params
    assert params["format"] == "short"
    assert params["currency"] == "USD"
