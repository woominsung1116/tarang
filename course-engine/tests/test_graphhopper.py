import asyncio
import json

import httpx
import pytest
from conftest import make_gh_path

from tarang_course.graphhopper import GraphHopperClient, RouteNotFound, UpstreamUnavailable


def client_with(handler) -> GraphHopperClient:
    return GraphHopperClient("http://gh.test", transport=httpx.MockTransport(handler))


def call(client: GraphHopperClient):
    async def go():
        try:
            return await client.round_trip(35.231, 129.084, 2000, 3, {"priority": []})
        finally:
            await client.aclose()

    return asyncio.run(go())


def test_round_trip_request_and_response():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"paths": [make_gh_path([2])]})

    path = call(client_with(handler))
    assert path["instructions"][1]["sign"] == 2
    body = seen["body"]
    assert seen["url"] == "http://gh.test/route"
    assert body["points"] == [[129.084, 35.231]]  # GraphHopper는 [lon, lat]
    assert body["algorithm"] == "round_trip"
    assert body["round_trip.distance"] == 2000 and body["round_trip.seed"] == 3
    assert body["ch.disable"] is True and body["points_encoded"] is False
    assert body["custom_model"] == {"priority": []}


@pytest.mark.parametrize(
    "response, error",
    [
        (httpx.Response(500, text="boom"), UpstreamUnavailable),
        (httpx.Response(503, text="busy"), UpstreamUnavailable),
        (httpx.Response(400, json={"message": "Cannot find point 0"}), RouteNotFound),
        (httpx.Response(200, text="not json"), UpstreamUnavailable),
        (httpx.Response(200, json={"paths": []}), UpstreamUnavailable),
        (httpx.Response(200, json={"paths": [{"points": "encoded", "instructions": []}]}), UpstreamUnavailable),
    ],
)
def test_error_mapping(response, error):
    with pytest.raises(error):
        call(client_with(lambda request: response))


@pytest.mark.parametrize("exc", [httpx.ConnectError("refused"), httpx.ReadTimeout("slow")])
def test_transport_errors_are_upstream_unavailable(exc):
    def handler(request):
        raise exc

    with pytest.raises(UpstreamUnavailable):
        call(client_with(handler))


def test_route_not_found_keeps_gh_message():
    with pytest.raises(RouteNotFound, match="Cannot find point"):
        call(client_with(lambda r: httpx.Response(400, json={"message": "Cannot find point 0"})))


@pytest.mark.parametrize("status, ok", [(200, True), (503, False)])
def test_healthy(status, ok):
    client = client_with(lambda r: httpx.Response(status, text="OK"))

    async def go():
        try:
            return await client.healthy()
        finally:
            await client.aclose()

    assert asyncio.run(go()) is ok


def test_healthy_false_when_unreachable():
    def handler(request):
        raise httpx.ConnectError("refused")

    client = client_with(handler)

    async def go():
        try:
            return await client.healthy()
        finally:
            await client.aclose()

    assert asyncio.run(go()) is False
