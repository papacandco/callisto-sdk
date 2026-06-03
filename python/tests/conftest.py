import pytest
from callisto_sdk.client import Client

BASE = "https://api.test/v1"


@pytest.fixture
def client():
    return Client(client_id="cid", api_key="secret", base_url=BASE)
