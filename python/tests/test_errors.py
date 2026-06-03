import pytest
from callisto_sdk.errors import (
    error_from_status, CallistoError, AuthenticationError, ValidationError,
    NotFoundError, RateLimitError, ApiError,
)


@pytest.mark.parametrize("status,cls", [
    (401, AuthenticationError),
    (400, ValidationError),
    (422, ValidationError),
    (404, NotFoundError),
    (429, RateLimitError),
    (500, ApiError),
])
def test_maps_status_to_class(status, cls):
    err = error_from_status(status, "msg", {"k": 1})
    assert isinstance(err, cls)
    assert isinstance(err, CallistoError)
    assert err.status_code == status
    assert err.message == "msg"
    assert err.body == {"k": 1}
