import asyncio
import json
import time
from pathlib import Path

import httpx
import pytest
from conftest import make_gh_path
from fastapi.testclient import TestClient

from taraang_course.graphhopper import GraphHopperClient
from taraang_course.server import Settings, TTLCache, create_app

URL = "/v1/courses?lat=35.23101&lon=129.08399&level=1&count=2"


def make_client(handler, deadline_s: float = 6.0, raise_server_exceptions: bool = True, **overrides) -> tuple[TestClient, dict]:
    stats = {"route": 0}

    def counting(request: httpx.Request) -> httpx.Response:
        # 코스 후보(round_trip)만 센다. 내비 경유지 확인(점 대 점 /route)은 따로 센다
        if request.url.path == "/route" and b"round_trip" in request.content:
            stats["route"] += 1
        elif request.url.path == "/route":
            stats["nav"] = stats.get("nav", 0) + 1
        return handler(request)

    gh = GraphHopperClient("http://gh.test", transport=httpx.MockTransport(counting))
    settings = Settings(seeds=4, deadline_s=deadline_s, signals_file=Path("/nonexistent/signals.json"), **overrides)
    app = create_app(settings, client=gh)
    return TestClient(app, raise_server_exceptions=raise_server_exceptions), stats


def ok_handler(request: httpx.Request) -> httpx.Response:
    if request.url.path == "/health":
        return httpx.Response(200, text="OK")
    return httpx.Response(200, json={"paths": [make_gh_path([2, 2], distance=1800)]})


def assert_error(resp, status: int, code: str):
    assert resp.status_code == status
    body = resp.json()
    assert set(body) == {"error"}
    assert body["error"]["code"] == code
    assert isinstance(body["error"]["message"], str) and body["error"]["message"]


def test_courses_ok_and_origin_rounded():
    client, _ = make_client(ok_handler)
    with client:
        resp = client.get(URL)
    assert resp.status_code == 200
    body = resp.json()
    assert body["origin"] == {"lat": 35.2310, "lon": 129.0840}
    # seed 4개가 같은 경로라 중복 제거 후 1개
    assert [c["id"].rsplit("-", 1)[0] for c in body["courses"]] == ["L1-35.2310-129.0840-s0"]


def test_count_defaults_to_3():
    client, stats = make_client(ok_handler)
    with client:
        assert client.get("/v1/courses?lat=35.23&lon=129.08&level=2").status_code == 200
    assert stats["route"] == 4


def test_cache_hit_skips_graphhopper():
    client, stats = make_client(ok_handler)
    with client:
        first = client.get(URL).json()
        # 소수점 4자리로 같은 위치
        second = client.get("/v1/courses?lat=35.23098&lon=129.08402&level=1&count=2").json()
        assert stats["route"] == 4
        client.get("/v1/courses?lat=35.23098&lon=129.08402&level=2&count=2")
        assert stats["route"] == 8
    assert first == second


@pytest.mark.parametrize(
    "query",
    [
        "lat=32.9&lon=129.0&level=1",
        "lat=39.1&lon=129.0&level=1",
        "lat=35.2&lon=123.9&level=1",
        "lat=35.2&lon=132.1&level=1",
        "lat=35.2&lon=129.0&level=0",
        "lat=35.2&lon=129.0&level=5",
        "lat=35.2&lon=129.0&level=1&count=0",
        "lat=35.2&lon=129.0&level=1&count=6",
        "lat=35.2&lon=129.0&level=1.5",
        "lat=abc&lon=129.0&level=1",
        "lat=nan&lon=129.0&level=1",
        "lat=inf&lon=129.0&level=1",
        "lon=129.0&level=1",
        "lat=35.2&lon=129.0",
        "",
    ],
)
def test_invalid_arguments(query):
    client, stats = make_client(ok_handler)
    with client:
        resp = client.get(f"/v1/courses?{query}")
    assert_error(resp, 400, "INVALID_ARGUMENT")
    assert stats["route"] == 0


def test_graphhopper_down_is_503():
    def down(request):
        raise httpx.ConnectError("refused")

    client, _ = make_client(down)
    with client:
        assert_error(client.get(URL), 503, "UPSTREAM_UNAVAILABLE")
        assert client.get("/healthz").json() == {"status": "ok", "graphhopper": "down"}


def test_graphhopper_5xx_is_503():
    client, _ = make_client(lambda r: httpx.Response(500, text="boom"))
    with client:
        assert_error(client.get(URL), 503, "UPSTREAM_UNAVAILABLE")


def test_slow_graphhopper_hits_deadline():
    async def slow(request):
        await asyncio.sleep(1.0)
        return ok_handler(request)

    gh = GraphHopperClient("http://gh.test", transport=httpx.MockTransport(slow))
    app = create_app(Settings(seeds=2, gh_timeout_s=0.05, deadline_s=0.1, signals_file=Path("/nonexistent")), client=gh)
    with TestClient(app) as client:
        assert_error(client.get(URL), 503, "UPSTREAM_UNAVAILABLE")


def test_no_route_is_404():
    client, _ = make_client(lambda r: httpx.Response(400, json={"message": "Cannot find point 0"}))
    with client:
        assert_error(client.get(URL), 404, "NO_COURSE")


def test_only_outliers_is_404():
    client, _ = make_client(lambda r: httpx.Response(200, json={"paths": [make_gh_path([2], distance=20000)]}))
    with client:
        assert_error(client.get(URL), 404, "NO_COURSE")


def test_errors_are_not_cached():
    calls = {"n": 0}

    def flaky(request):
        calls["n"] += 1
        if calls["n"] <= 4:
            raise httpx.ConnectError("refused")
        return ok_handler(request)

    client, stats = make_client(flaky, negative_ttl_s=0.2)
    with client:
        assert client.get(URL).status_code == 503
        # 10초(여기선 0.2초) 동안은 경로 엔진을 다시 부르지 않고 바로 503
        assert client.get(URL).status_code == 503
        assert stats["route"] == 4
        time.sleep(0.25)
        assert client.get(URL).status_code == 200  # 성공은 캐시되지만 실패는 짧게만 기억한다


def test_unexpected_error_is_500_without_details():
    def broken(request):
        raise RuntimeError("secret internal detail")

    client, _ = make_client(broken, raise_server_exceptions=False)
    with client:
        resp = client.get(URL)
    assert_error(resp, 500, "INTERNAL")
    assert "secret" not in resp.text


def test_healthz_ok():
    client, _ = make_client(ok_handler)
    with client:
        resp = client.get("/healthz")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok", "graphhopper": "ok"}


def test_unknown_route_uses_error_envelope():
    client, _ = make_client(ok_handler)
    with client:
        assert_error(client.get("/v1/nope"), 404, "INVALID_ARGUMENT")
        assert_error(client.post("/v1/courses"), 405, "INVALID_ARGUMENT")


def test_ttl_cache_expiry_and_lru():
    now = [0.0]
    cache = TTLCache(ttl_s=10, max_entries=2, clock=lambda: now[0])
    cache.put(("a",), 1)
    cache.put(("b",), 2)
    assert cache.get(("a",)) == 1  # a가 최근 사용
    cache.put(("c",), 3)  # b 밀려남
    assert cache.get(("b",)) is None
    now[0] = 11
    assert cache.get(("a",)) is None and cache.get(("c",)) is None


@pytest.mark.parametrize(
    "query",
    [
        "lat=35.2&lon=129.0&level=1&debug=1",
        "lat=35.2&lat=35.3&lon=129.0&level=1",
        "lat=35.2&lon=129.0&level=1&pad=" + "x" * 300,
        "lat=" + "3" * 300 + "&lon=129.0&level=1",
    ],
)
def test_extra_duplicate_or_oversized_params_rejected(query):
    client, stats = make_client(ok_handler)
    with client:
        assert_error(client.get(f"/v1/courses?{query}"), 400, "INVALID_ARGUMENT")
    assert stats["route"] == 0


def test_request_body_too_large_rejected():
    client, _ = make_client(ok_handler)
    with client:
        resp = client.request("GET", URL, content=b"x" * 2048)
    assert_error(resp, 400, "INVALID_ARGUMENT")


def test_origin_rounded_to_3_decimals_for_cache_and_ids():
    client, stats = make_client(ok_handler)
    with client:
        a = client.get("/v1/courses?lat=35.2312&lon=129.0838&level=1").json()
        b = client.get("/v1/courses?lat=35.2308&lon=129.0841&level=1").json()
    assert a == b and stats["route"] == 4
    assert a["origin"] == {"lat": 35.231, "lon": 129.084}
    assert a["courses"][0]["id"].startswith("L1-35.2310-129.0840-s0-")


def test_concurrent_identical_requests_share_one_generation():
    async def slowish(request):
        await asyncio.sleep(0.2)
        return ok_handler(request)

    gh = GraphHopperClient("http://gh.test", transport=httpx.MockTransport(slowish))
    calls = {"n": 0}
    original = gh.round_trip

    async def counting(*args, **kwargs):
        calls["n"] += 1
        return await original(*args, **kwargs)

    gh.round_trip = counting
    app = create_app(Settings(seeds=4, signals_file=Path("/nonexistent")), client=gh)

    async def go():
        transport = httpx.ASGITransport(app=app)
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=transport, base_url="http://t") as c:
                return await asyncio.gather(*(c.get(URL) for _ in range(5)))

    responses = asyncio.run(go())
    assert [r.status_code for r in responses] == [200] * 5
    assert calls["n"] == 4  # seed 4개를 한 번만


def test_malformed_path_from_graphhopper_is_skipped():
    calls = {"n": 0}

    def half_broken(request):
        calls["n"] += 1
        if calls["n"] % 2:
            return httpx.Response(200, json={"paths": [{"distance": 1800, "points": {"coordinates": [[1]]}, "instructions": [{}]}]})
        return ok_handler(request)

    client, _ = make_client(half_broken)
    with client:
        assert client.get(URL).status_code == 200


def test_all_malformed_paths_is_503():
    broken = {"paths": [{"distance": 1800, "points": {"coordinates": []}, "instructions": [{"sign": 2}]}]}
    client, _ = make_client(lambda r: httpx.Response(200, json=broken))
    with client:
        assert_error(client.get(URL), 503, "UPSTREAM_UNAVAILABLE")


def test_no_docs_and_hardening_headers():
    client, _ = make_client(ok_handler)
    with client:
        for path in ("/docs", "/redoc", "/openapi.json"):
            assert client.get(path).status_code == 404
        resp = client.get("/healthz", headers={"Origin": "http://evil.example"})
    assert resp.headers["X-Content-Type-Options"] == "nosniff"
    assert "access-control-allow-origin" not in resp.headers  # CORS 미허용


def test_settings_from_env():
    s = Settings.from_env({"TARAANG_GH_URL": "http://gh:8989", "TARAANG_PORT": "9000", "TARAANG_CACHE_MAX_ENTRIES": "10"})
    assert (s.gh_url, s.port, s.cache_max_entries) == ("http://gh:8989", 9000, 10)
    assert Settings.from_env({}) == Settings()


@pytest.mark.parametrize(
    "env",
    [
        {"TARAANG_GH_URL": "file:///etc/passwd"},
        {"TARAANG_PORT": "abc"},
        {"TARAANG_SEEDS": "0"},
        {"TARAANG_SEEDS": "500"},
        {"TARAANG_CACHE_TTL_S": "-1"},
        {"TARAANG_GH_TIMEOUT_S": "10"},  # 전체 상한보다 길다
        {"TARAANG_SCHOOL_ZONE_BUFFER_M": "0"},
        {"TARAANG_SCHOOL_ZONE_BUFFER_M": "5000"},
        {"TARAANG_SCHOOL_ZONE_BUFFER_M": "넓게"},
    ],
)
def test_settings_reject_bad_env(env):
    with pytest.raises(ValueError):
        Settings.from_env(env)


def test_zone_settings_from_env():
    s = Settings.from_env({"TARAANG_SCHOOL_ZONE_BUFFER_M": "120", "TARAANG_ZONES_DIR": "/tmp/zones"})
    assert s.school_zone_buffer_m == 120 and s.zones_dir == Path("/tmp/zones")
    assert Settings().school_zone_buffer_m == 150


def test_missing_zone_and_road_files_still_serve_courses():
    gh = GraphHopperClient("http://gh.test", transport=httpx.MockTransport(ok_handler))
    settings = Settings(
        seeds=2, signals_file=Path("/nonexistent"), zones_dir=Path("/nonexistent/zones"), roads_file=Path("/nonexistent/roads.json")
    )
    with TestClient(create_app(settings, client=gh)) as client:
        resp = client.get(URL)
        assert client.app.state.zones is None and client.app.state.roads is None
    assert resp.status_code == 200
    assert not any("보호구역" in n or "노드링크" in n for n in resp.json()["dataNotes"])
    assert "road" not in resp.json()["courses"][0]["debug"]


def test_count_is_not_part_of_the_cache_key():
    """count만 다른 요청은 GraphHopper를 다시 부르지 않고 같은 생성 결과를 잘라 쓴다."""
    by_seed = {s: make_gh_path([2] * (s + 1), distance=1500 + 100 * s) for s in range(4)}

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        if "round_trip.seed" not in body:
            return httpx.Response(400, json={"message": "no route"})  # 내비 경유지 확인: 못 함 → 코스 유지
        return httpx.Response(200, json={"paths": [by_seed[body["round_trip.seed"]]]})

    client, stats = make_client(handler)
    base = "/v1/courses?lat=35.231&lon=129.084&level=1"
    with client:
        one = client.get(base + "&count=1").json()
        three = client.get(base + "&count=3").json()
        five = client.get(base + "&count=5").json()
    assert stats["route"] == 4  # seed 4개, 한 번만
    assert [len(b["courses"]) for b in (one, three, five)] == [1, 3, 4]
    assert one["courses"][0] == three["courses"][0]
    # 대체 안내는 잘라낸 코스 기준: 1개만 주면 그 코스가 조건에 맞아 대체 안내가 없다
    assert not any("가장 가까운" in n for n in one["dataNotes"])


def test_too_many_distinct_generations_is_503_but_same_key_joins():
    release = asyncio.Event()

    async def held(request):
        await release.wait()
        return ok_handler(request)

    gh = GraphHopperClient("http://gh.test", transport=httpx.MockTransport(held))
    app = create_app(Settings(seeds=2, max_inflight=1, signals_file=Path("/nonexistent")), client=gh)

    async def go():
        transport = httpx.ASGITransport(app=app)
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=transport, base_url="http://t") as c:
                first = asyncio.create_task(c.get("/v1/courses?lat=35.231&lon=129.084&level=1"))
                await asyncio.sleep(0.05)
                same = asyncio.create_task(c.get("/v1/courses?lat=35.231&lon=129.084&level=1&count=1"))
                other = await c.get("/v1/courses?lat=35.300&lon=129.084&level=1")
                release.set()
                return await first, await same, other

    first, same, other = asyncio.run(go())
    assert (first.status_code, same.status_code) == (200, 200)
    assert_error(other, 503, "UPSTREAM_UNAVAILABLE")
    assert other.json()["error"]["message"] == "잠시 후 다시 시도해 주세요"


@pytest.mark.parametrize("url", ["http://user:pw@gh:8989", "http://token@gh:8989", "http://:8989"])
def test_gh_url_rejects_userinfo_and_missing_host(url):
    with pytest.raises(ValueError):
        Settings(gh_url=url)


def test_logged_graphhopper_address_is_host_and_port_only():
    from taraang_course.server import url_host

    assert url_host("http://localhost:8989/route?key=x") == "localhost:8989"
    assert url_host("https://gh.example") == "gh.example:443"


def test_defaults_bind_loopback_and_limit_load():
    s = Settings()
    assert s.host == "127.0.0.1"
    assert (s.max_inflight, s.limit_concurrency, s.keep_alive_s) == (16, 64, 5.0)


ROUTE_URL = "/v1/route-skills?fromLat=35.231&fromLon=129.084&toLat=35.158&toLon=129.059"


def test_route_skills_ok_and_cached():
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/health":
            return httpx.Response(200, text="OK")
        body = json.loads(request.content)
        assert "algorithm" not in body and len(body["points"]) == 2  # 점 대 점
        return httpx.Response(200, json={"paths": [make_gh_path([2, -2], distance=9000)]})

    client, stats = make_client(handler)
    with client:
        first = client.get(ROUTE_URL)
        second = client.get(ROUTE_URL)
    assert first.status_code == 200 and second.json() == first.json()
    body = first.json()
    assert set(body) == {"schemaVersion", "distanceM", "skillCounts", "maneuvers", "warnings", "dataNotes"}
    assert body["skillCounts"] == {"LEFT_TURN_UNKNOWN": 1, "RIGHT_TURN": 1}
    assert stats.get("nav") == 1  # 두 번째는 캐시


@pytest.mark.parametrize(
    "query",
    [
        "fromLat=35.231&fromLon=129.084&toLat=35.158",  # 빠짐
        "fromLat=30&fromLon=129.084&toLat=35.158&toLon=129.059",  # 한국 밖
        "fromLat=35.231&fromLon=129.084&toLat=35.158&toLon=129.059&lat=1",  # 모르는 인자
        "fromLat=35.231&fromLon=129.084&toLat=35.231&toLon=129.0841",  # 너무 가깝다
        "fromLat=35.231&fromLon=129.084&toLat=35.9&toLon=129.084",  # 직선 70km
    ],
)
def test_route_skills_rejects_bad_input(query):
    client, stats = make_client(ok_handler)
    with client:
        assert_error(client.get("/v1/route-skills?" + query), 400, "INVALID_ARGUMENT")
    assert stats.get("nav", 0) == 0


def test_route_skills_without_route_is_404():
    client, _ = make_client(lambda r: httpx.Response(400, json={"message": "Connection between locations not found"}))
    with client:
        resp = client.get(ROUTE_URL)
    assert_error(resp, 404, "NO_COURSE")
    assert resp.json()["error"]["message"] == "두 지점 사이 경로를 찾을 수 없습니다"


def test_focus_is_validated_and_part_of_the_cache_key():
    client, stats = make_client(ok_handler)
    base = "/v1/courses?lat=35.231&lon=129.084&level=1"
    with client:
        assert client.get(base).status_code == 200
        assert client.get(base + "&focus=U_TURN,LEFT_TURN_SIGNALIZED").status_code == 200
        assert client.get(base + "&focus=LEFT_TURN_SIGNALIZED,U_TURN").status_code == 200  # 순서만 다름 → 캐시
        assert_error(client.get(base + "&focus=FLYING"), 400, "INVALID_ARGUMENT")
        assert_error(client.get(base + "&focus=" + "U_TURN," * 30), 400, "INVALID_ARGUMENT")
    assert stats["route"] == 4 * 2  # focus 없음 1회 + focus 1회, seed 4개씩


def test_analysis_bug_is_500_not_retryable_503(monkeypatch):
    from taraang_course import engine

    def boom(*args, **kwargs):
        raise ZeroDivisionError("bug")

    monkeypatch.setattr(engine, "analyze", boom)
    client, _ = make_client(ok_handler)
    with client:
        resp = client.get(URL)
    assert_error(resp, 500, "INTERNAL")
    assert "ZeroDivision" not in resp.text


def test_graphhopper_path_shape_is_validated():
    from taraang_course.graphhopper import valid_path

    ok = make_gh_path([2, -2])
    assert valid_path(ok)
    bad_interval = {**ok, "instructions": [{"sign": 2, "interval": [0, 99]}]}
    bad_coord = {**ok, "points": {"coordinates": [[129.0, "35"], [129.0, 35.1]]}}
    bad_sign = {**ok, "instructions": [{"sign": "2", "interval": [0, 1]}]}
    assert not any(valid_path(p) for p in (bad_interval, bad_coord, bad_sign))
