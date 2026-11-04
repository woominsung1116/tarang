import asyncio
import re
from datetime import UTC, datetime

import pytest
from conftest import make_gh_path, turn_point

from tarang_course import engine
from tarang_course.geo import SignalIndex
from tarang_course.graphhopper import RouteNotFound, UpstreamUnavailable

NOW = datetime(2026, 10, 22, 8, 0, tzinfo=UTC)
COURSE_KEYS = {"id", "level", "title", "distanceM", "path", "waypoints", "maneuvers", "skillCounts", "warnings", "debug"}
# 사용자에게 보이는 문장에 들어가면 안 되는 내부 용어
JARGON = re.compile(r"기준|최대|최소|[A-Z_]{3,}|seed|penalty")
MANEUVER_KEYS = {"index", "skill", "street", "lat", "lon", "distanceFromStartM", "confidence"}


class FakeClient:
    """seed → GraphHopper path 또는 예외. 없는 seed는 RouteNotFound."""

    def __init__(self, by_seed: dict):
        self.by_seed = by_seed
        self.calls = []

    async def round_trip(self, lat, lon, distance_m, seed, custom_model):
        self.calls.append((distance_m, seed))
        r = self.by_seed.get(seed, RouteNotFound("no route"))
        if isinstance(r, Exception):
            raise r
        return r

    async def route(self, points, custom_model=None):
        """내비 경유지 확인. 기본은 경로 없음 → 회전 기준 경유지, coverage None."""
        raise RouteNotFound("no nav route in fake")


def run(by_seed: dict, level: int = 1, count: int = 3, signals: SignalIndex | None = None, seeds: int = 6) -> dict:
    client = FakeClient(by_seed)
    return asyncio.run(engine.generate(client, signals if signals is not None else SignalIndex([]), 35.2310, 129.0840, level, count, seeds=seeds, now=NOW))


def test_response_matches_course_contract():
    body = run({0: make_gh_path([2, 2, 3], distance=1800)}, count=1)
    assert set(body) == {"schemaVersion", "origin", "generatedAt", "dataNotes", "courses"}
    assert body["schemaVersion"] == 1
    assert body["origin"] == {"lat": 35.2310, "lon": 129.0840}
    assert body["generatedAt"] == "2026-10-22T08:00:00Z"
    (c,) = body["courses"]
    assert set(c) == COURSE_KEYS
    assert re.fullmatch(r"L1-35\.2310-129\.0840-s0-[0-9a-f]{8}", c["id"])
    assert c["id"].endswith(engine.path_hash(c["path"]))
    assert c["level"] == 1 and c["distanceM"] == 1800
    assert all(len(p) == 2 and 34 < p[0] < 36 and 128 < p[1] < 130 for p in c["path"])
    assert 1 <= len(c["waypoints"]) <= 3
    assert [m["index"] for m in c["maneuvers"]] == [0, 1, 2]
    assert all(set(m) == MANEUVER_KEYS for m in c["maneuvers"])
    assert c["skillCounts"] == {"RIGHT_TURN": 3}
    assert c["warnings"] == []
    assert c["debug"] == {"seed": 0, "penalty": 0, "violations": [], "navMatch": None}
    assert c["title"]
    assert engine.NOTE_OSM in body["dataNotes"]


def test_strict_candidate_wins_and_no_fallback_note():
    body = run(
        {
            0: make_gh_path([2, -2], distance=1500),  # 비보호 좌회전 1 → 위반
            1: make_gh_path([2, 2], distance=2400),  # 조건 충족, 더 길다
        }
    )
    ids = [c["id"].rsplit("-", 1)[0] for c in body["courses"]]
    assert ids == ["L1-35.2310-129.0840-s1", "L1-35.2310-129.0840-s0"]
    assert body["courses"][0]["warnings"] == []
    assert body["courses"][1]["warnings"] == ["신호등 정보가 없는 좌회전 1곳 — 출발 전에 지도에서 확인해 주세요"]
    # 하나라도 조건 밖 코스가 섞이면 밝힌다
    assert "1단계 조건에 딱 맞는 코스가 1개뿐이라 가장 가까운 코스로 채웠어요" in body["dataNotes"]


def test_strict_courses_sorted_shortest_first():
    body = run({0: make_gh_path([2, 2], distance=2600), 1: make_gh_path([2], distance=1500), 2: make_gh_path([3, 3, 3], distance=2000)})
    assert [c["distanceM"] for c in body["courses"]] == [1500, 2000, 2600]
    assert len(body["dataNotes"]) == 2  # 비보호 간주 + 출처


def test_fallback_when_nothing_is_strict_ranks_by_fewest_violations():
    body = run(
        {
            0: make_gh_path([-2, -2, -2], distance=1500),  # 위반 3
            1: make_gh_path([2, -2], distance=2500),  # 위반 1
            2: make_gh_path([-2, -98], distance=1800),  # 위반 2
        }
    )
    assert [f"s{c['debug']['seed']}" for c in body["courses"]] == ["s1", "s2", "s0"]
    for c in body["courses"]:
        assert c["warnings"], "조건 밖 코스는 반드시 경고가 있어야 한다"
    assert body["courses"][1]["warnings"] == [
        "신호등 정보가 없는 좌회전 1곳 — 출발 전에 지도에서 확인해 주세요",
        "유턴이 1곳 있어요",
    ]
    assert body["courses"][1]["debug"] == {
        "seed": 2,
        "penalty": 2,
        "violations": [
            {"kind": "max", "skill": "LEFT_TURN_UNKNOWN", "count": 1, "limit": 0},
            {"kind": "max", "skill": "U_TURN", "count": 1, "limit": 0},
        ],
        "navMatch": None,
    }
    assert body["dataNotes"] == [
        "신호등 정보가 없는 좌회전은 어려운 쪽으로 보고 골랐어요",
        "1단계 조건에 딱 맞는 코스가 없어 가장 가까운 코스를 골랐어요",
        engine.NOTE_OSM,
    ]
    for c in body["courses"]:
        for text in c["warnings"]:
            assert not JARGON.search(text), text
    for text in body["dataNotes"][:-1]:
        assert not JARGON.search(text), text


def test_unknown_left_warned_even_when_level_allows_it():
    body = run({0: make_gh_path([-2, -2, 2, 2] + [0] * 30, distance=6000)}, level=4)
    (c,) = body["courses"]
    assert c["warnings"] == ["신호등 정보가 없는 좌회전 2곳 — 출발 전에 지도에서 확인해 주세요"]
    assert c["debug"]["violations"] == []
    assert c["skillCounts"] == {"LEFT_TURN_UNKNOWN": 2, "RIGHT_TURN": 2}


def test_signalized_left_counts_for_level2():
    lat, lon = turn_point(0)
    lat2, lon2 = turn_point(2)
    signals = SignalIndex([[lat, lon], [lat2, lon2]])
    body = run({0: make_gh_path([-2, 2, -3], distance=3000)}, level=2, signals=signals)
    (c,) = body["courses"]
    assert c["skillCounts"] == {"LEFT_TURN_SIGNALIZED": 2, "RIGHT_TURN": 1}
    assert c["warnings"] == []
    assert [m["confidence"] for m in c["maneuvers"]] == ["VERIFIED", "UNKNOWN", "VERIFIED"]
    assert "신호 좌회전" in c["title"]


def test_distance_outliers_and_duplicates_are_dropped():
    same = make_gh_path([2, 2], distance=2000)
    body = run({0: same, 1: dict(same), 2: make_gh_path([2], distance=18000), 3: make_gh_path([2], distance=500)})
    assert [f"s{c['debug']['seed']}" for c in body["courses"]] == ["s0"]


def test_only_outliers_is_no_course():
    with pytest.raises(engine.NoCourse):
        run({0: make_gh_path([2], distance=18000)})


def test_no_route_anywhere_is_no_course():
    with pytest.raises(engine.NoCourse):
        run({})


def test_graphhopper_down_is_upstream_unavailable():
    with pytest.raises(UpstreamUnavailable):
        run({s: UpstreamUnavailable("ConnectError") for s in range(6)})


def test_partial_upstream_failure_still_returns_courses():
    by_seed = {s: UpstreamUnavailable("ReadTimeout") for s in range(5)}
    by_seed[5] = make_gh_path([2], distance=2000)
    body = run(by_seed)
    assert [f"s{c['debug']['seed']}" for c in body["courses"]] == ["s5"]


def test_seeds_use_level_target_distance():
    client = FakeClient({})
    with pytest.raises(engine.NoCourse):
        asyncio.run(engine.generate(client, SignalIndex([]), 35.2, 129.0, 3, 1, seeds=4))
    assert sorted(client.calls) == [(4500, s) for s in range(4)]


def test_count_limits_courses():
    body = run({s: make_gh_path([2] * (s + 1), distance=1500 + 100 * s) for s in range(6)}, count=2)
    assert len(body["courses"]) == 2


def test_title_uses_street_names():
    body = run({0: make_gh_path([2, 2], distance=2000, streets=["금강로", "부산대학로"])}, count=1)
    title = body["courses"][0]["title"]
    assert "금강로" in title and "우회전" in title
    assert re.fullmatch(r"[^\n]{3,40}", title)


def test_signal_data_attribution_goes_before_osm():
    body = run({0: make_gh_path([2], distance=2000)}, signals=SignalIndex([], ["신호 데이터: 부산광역시, 공공데이터포털(data.go.kr)"]))
    assert body["dataNotes"][-2:] == ["신호 데이터: 부산광역시, 공공데이터포털(data.go.kr)", engine.NOTE_OSM]


def test_course_id_changes_when_path_changes():
    a = run({0: make_gh_path([2, 2], distance=2000)}, count=1)["courses"][0]
    b = run({0: make_gh_path([2, 2, 2], distance=2000)}, count=1)["courses"][0]
    same = run({0: make_gh_path([2, 2], distance=2000)}, count=1)["courses"][0]
    assert a["id"].rsplit("-", 1)[0] == b["id"].rsplit("-", 1)[0]  # 같은 출발지·단계·seed
    assert a["id"] != b["id"] and a["id"] == same["id"]


def test_focus_prefers_courses_with_the_goal_skills_after_level_rules():
    by_seed = {
        0: make_gh_path([2, 2], distance=1500),  # 조건 충족, 짧다
        1: make_gh_path([2, -2], distance=2400, streets=["a", "b"]),  # 비보호 좌회전(1단계 위반)
        2: make_gh_path([2, -98], distance=1800),  # 유턴(1단계 위반)
    }
    client = FakeClient(by_seed)
    body = asyncio.run(
        engine.generate(client, SignalIndex([]), 35.231, 129.084, 1, 3, seeds=3, now=NOW, focus=frozenset({"U_TURN"}))
    )
    # 단계 조건이 먼저(s0), 위반이 같으면 focus 기술(유턴)이 있는 코스가 앞선다(s2 > s1)
    assert [c["debug"]["seed"] for c in body["courses"]] == [0, 2, 1]


class NavClient(FakeClient):
    """seed별 round_trip 경로를 주고, 내비 경로(점 대 점)는 nav_path를 돌려준다."""

    def __init__(self, by_seed, nav_paths):
        super().__init__(by_seed)
        self.nav_paths = nav_paths  # 경유지 첫 점의 위도(소수 4자리) → GraphHopper path

    async def route(self, points, custom_model=None):
        return self.nav_paths(points)


def nav_300m_north(points):
    """내비는 경유지와 상관없이 북쪽 300m까지만 갔다 온다고 가정한다."""
    return {"points": {"coordinates": [[129.0, 35.0], [129.0, 35.0027], [129.0, 35.0]]}, "instructions": []}


def test_course_the_navigation_would_not_follow_is_dropped():
    good = make_gh_path([2, 2], distance=1500)  # 북쪽 300m 왕복: 내비와 같다
    bad = make_gh_path([2, 2, 2, 2, 2], distance=1400)  # 북쪽 600m 왕복. 위반 없고 더 짧아 순위로는 먼저
    client = NavClient({0: bad, 1: good}, nav_300m_north)
    body = asyncio.run(engine.generate(client, SignalIndex([]), 35.0, 129.0, 1, 2, seeds=2, now=NOW))
    (only,) = body["courses"]
    assert only["debug"]["seed"] == 1 and only["debug"]["navMatch"] >= engine.NAV_MIN_MATCH
    assert len(only["waypoints"]) == 2 and engine.WARN_NAV_DIFFERS not in only["warnings"]


def test_when_no_course_matches_the_best_one_comes_with_a_warning():
    bad = make_gh_path([2, 2, 2, 2, 2], distance=1400)
    worse = make_gh_path([2, 2, 2, 2, 2, 2, 2], distance=1900)
    client = NavClient({0: bad, 1: worse}, nav_300m_north)
    body = asyncio.run(engine.generate(client, SignalIndex([]), 35.0, 129.0, 1, 2, seeds=2, now=NOW))
    (only,) = body["courses"]
    assert only["debug"]["seed"] == 0 and only["debug"]["navMatch"] < engine.NAV_MIN_MATCH
    assert only["warnings"][-1] == engine.WARN_NAV_DIFFERS


def test_analysis_bug_is_not_an_upstream_error(monkeypatch):
    def boom(*args, **kwargs):
        raise ZeroDivisionError("bug")

    monkeypatch.setattr(engine, "analyze", boom)
    with pytest.raises(engine.AnalysisFailed):
        run({0: make_gh_path([2], distance=2000)})


def test_route_skills_breaks_down_a_point_to_point_route():
    class RouteClient(FakeClient):
        async def route(self, points, custom_model=None):
            assert custom_model is None  # 단계 조건 없는 기본 차량 경로
            return make_gh_path([2, -2, -3], distance=4200)

    lat, lon = turn_point(2)
    body = asyncio.run(
        engine.route_skills(RouteClient({}), SignalIndex([[lat, lon]]), (35.0, 129.0), (35.05, 129.0))
    )
    assert set(body) == {"schemaVersion", "distanceM", "skillCounts", "maneuvers", "warnings", "dataNotes"}
    assert body["distanceM"] == 4200
    assert body["skillCounts"] == {"LEFT_TURN_SIGNALIZED": 1, "LEFT_TURN_UNKNOWN": 1, "RIGHT_TURN": 1}
    assert all(set(m) == MANEUVER_KEYS for m in body["maneuvers"])
    assert body["warnings"] == ["신호등 정보가 없는 좌회전 1곳 — 출발 전에 지도에서 확인해 주세요"]
    assert body["dataNotes"][-1] == engine.NOTE_OSM


def test_route_skills_without_route_is_no_course():
    with pytest.raises(engine.NoCourse):
        asyncio.run(engine.route_skills(FakeClient({}), SignalIndex([]), (35.0, 129.0), (35.05, 129.0)))


class StubZones:
    """구역 감점·차단을 custom_model에 붙이기만 하는 구역 레이어. 보호구역 진입은 없다."""

    attribution: list = []
    buffer_m = 150

    def custom_model(self, base, factors, lat, lon, radius_m):
        return {**base, "areas": {"type": "FeatureCollection", "features": [{"id": "z"}]}}

    def school_entries(self, path):
        return []


def test_zone_relaxed_retry_still_runs_when_one_seed_hit_an_upstream_error():
    """구역을 건 seed 하나가 GraphHopper 오류, 나머지는 경로 없음이어도 구역 없이 다시 만든다(404가 아니라 코스)."""

    class ZoneClient(FakeClient):
        async def round_trip(self, lat, lon, distance_m, seed, custom_model):
            self.calls.append((distance_m, seed))
            if "areas" in custom_model:
                raise UpstreamUnavailable("ReadTimeout") if seed == 0 else RouteNotFound("boxed in")
            return make_gh_path([2], distance=2000)

    client = ZoneClient({})
    body = asyncio.run(engine.generate(client, SignalIndex([]), 35.231, 129.084, 1, 3, seeds=4, now=NOW, zones=StubZones()))
    assert body["courses"]
    assert len(client.calls) == 8  # 구역 4 + 구역 없이 4


def test_zone_retry_skipped_when_every_seed_hit_an_upstream_error():
    class DownClient(FakeClient):
        async def round_trip(self, lat, lon, distance_m, seed, custom_model):
            self.calls.append((distance_m, seed))
            raise UpstreamUnavailable("ConnectError")

    client = DownClient({})
    with pytest.raises(UpstreamUnavailable):
        asyncio.run(engine.generate(client, SignalIndex([]), 35.231, 129.084, 1, 3, seeds=4, now=NOW, zones=StubZones()))
    assert len(client.calls) == 4  # 죽은 경로 엔진에 한 번 더 보내지 않는다


class SlowNavClient(FakeClient):
    """round_trip은 바로, 내비 경유지 확인은 delay_s 뒤에 답한다."""

    def __init__(self, by_seed, delay_s):
        super().__init__(by_seed)
        self.delay_s = delay_s
        self.nav_calls = 0

    async def route(self, points, custom_model=None):
        self.nav_calls += 1
        await asyncio.sleep(self.delay_s)
        return nav_300m_north(points)


def test_nav_deadline_keeps_round_trip_courses_unverified():
    """내비 확인 마감을 넘기면 확인을 멈추고, 이미 만든 코스는 navMatch None(통과)으로 준다. 버리지 않는다."""
    by_seed = {s: make_gh_path([2] * (s + 1), distance=1500 + 100 * s) for s in range(4)}
    client = SlowNavClient(by_seed, delay_s=5.0)

    async def go():
        loop = asyncio.get_running_loop()
        started = loop.time()
        p = await engine.plan(client, SignalIndex([]), 35.0, 129.0, 1, 3, seeds=4, now=NOW, nav_deadline=started + 0.1)
        return p, loop.time() - started

    p, took = asyncio.run(go())
    assert took < 1.0
    body = engine.render(p, 3)
    assert len(body["courses"]) == 3
    assert all(c["debug"]["navMatch"] is None and len(c["waypoints"]) == 2 for c in body["courses"])
    assert all(engine.WARN_NAV_DIFFERS not in c["warnings"] for c in body["courses"])


def test_passed_nav_deadline_sends_no_nav_requests():
    client = SlowNavClient({0: make_gh_path([2, 2], distance=1500)}, delay_s=0.0)

    async def go():
        deadline = asyncio.get_running_loop().time() - 1
        return await engine.plan(client, SignalIndex([]), 35.0, 129.0, 1, 3, seeds=2, now=NOW, nav_deadline=deadline)

    p = asyncio.run(go())
    assert client.nav_calls == 0
    assert [p.nav[c.seed][1] for c in p.chosen] == [None]
