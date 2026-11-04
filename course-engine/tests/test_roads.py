import asyncio

import pytest
from conftest import make_gh_path

from tarang_course import engine, roads
from tarang_course.analysis import analyze
from tarang_course.geo import SignalIndex
from tarang_course.graphhopper import RouteNotFound
from tarang_course.levels import LEVELS, user_message, violations
from tarang_course.roads import FAST_ROAD, MULTI_LANE_ROAD, WIDE_ROAD, RoadLayer

M_PER_DEG_LAT = 111_195
LAT0, LON0 = 35.0, 129.0
KX = M_PER_DEG_LAT * 0.8192  # cos(35°)


def north(m: float, east_m: float = 0.0) -> list[float]:
    return [LAT0 + m / M_PER_DEG_LAT, LON0 + east_m / KX]


def way(wid, coords, lanes=None, speed=30, source="default_5030", oneway=0):
    return {"id": wid, "coords": coords, "oneway": oneway, "lanes": lanes, "maxSpeedKmh": speed,
            "speedSource": source, "lanesSource": None, "highway": "residential"}


def straight(length_m: float) -> list[list[float]]:
    return [north(0), north(length_m)]


# --- 경로 → way 매칭 ---


def test_path_along_a_way_matches_it():
    layer = RoadLayer([way(1, straight(1000)), way(2, [north(0, 300), north(1000, 300)])])
    matched = layer.match(straight(1000))
    assert len(matched) == 100 and set(matched) == {0}


def test_crossing_road_is_not_matched_by_heading():
    """큰길을 가로지를 때 그 큰길에 붙으면 안 된다. 진행 방향 ±30° 조건."""
    cross = way(9, [north(500, -300), north(500, 300)], lanes=6, speed=60, source="nodelink")
    layer = RoadLayer([cross])
    assert set(layer.match(straight(1000))) == {-1}
    assert layer.summarize(straight(1000)).stretches == {FAST_ROAD: 0, WIDE_ROAD: 0, MULTI_LANE_ROAD: 0}


def test_reverse_direction_on_two_way_road_matches():
    layer = RoadLayer([way(1, straight(1000))])
    assert set(layer.match([north(1000), north(0)])) == {0}


def test_far_or_outside_paths_are_unknown():
    layer = RoadLayer([way(1, straight(1000))])
    assert set(layer.match([north(0, 100), north(1000, 100)])) == {-1}  # 100m 옆
    s = layer.summarize([[36.0, 128.0], [36.01, 128.0]])  # 레이어 범위 밖
    assert (s.max_speed_kmh, s.matched_share, s.stretches) == (None, 0.0, {FAST_ROAD: 0, WIDE_ROAD: 0, MULTI_LANE_ROAD: 0})


# --- 요약 ---


def test_speed_shares_and_confidence():
    layer = RoadLayer(
        [
            way(1, straight(400), speed=30, source="default_5030"),
            way(2, [north(400), north(1000)], speed=50, source="nodelink"),
        ]
    )
    s = layer.summarize(straight(1000))
    assert s.max_speed_kmh == 50 and s.max_speed_confidence == "VERIFIED"
    assert s.speed_estimated_share == pytest.approx(0.4, abs=0.02)
    assert s.speed_verified_share == pytest.approx(0.6, abs=0.02)
    assert s.matched_share == 1.0


def test_estimated_max_is_marked_estimated():
    layer = RoadLayer([way(1, straight(1000), speed=30, source="default_5030")])
    s = layer.summarize(straight(1000))
    assert (s.max_speed_kmh, s.max_speed_confidence, s.speed_verified_share) == (30, "ESTIMATED", 0.0)


@pytest.mark.parametrize(
    ("lanes", "oneway", "wide", "multi"),
    [
        (6, 0, True, True),
        (4, 0, False, True),  # 편도 2차로: debug에만
        (2, 0, False, False),
        (3, 1, True, True),  # 일방통행 3차로 = 왕복 6 상당
        (2, 1, False, True),
        (1, 1, False, False),
        (None, 0, False, False),
    ],
)
def test_wide_road_is_three_lanes_per_direction(lanes, oneway, wide, multi):
    stretches = RoadLayer([way(1, straight(1000), lanes=lanes, oneway=oneway)]).summarize(straight(1000)).stretches
    assert (stretches[WIDE_ROAD], stretches[MULTI_LANE_ROAD]) == (int(wide), int(multi))


def test_fast_road_uses_only_observed_speed():
    observed = RoadLayer([way(1, straight(1000), speed=60, source="osm")])
    assert observed.summarize(straight(1000)).stretches[FAST_ROAD] == 1
    at_cap = RoadLayer([way(1, straight(1000), speed=50, source="nodelink")])
    assert at_cap.summarize(straight(1000)).stretches[FAST_ROAD] == 0


def test_short_touch_and_endpoints_are_not_stretches():
    path = straight(1000)
    # 가운데 20m만 큰길: MIN_STRETCH_M(30) 미만
    short = RoadLayer([way(1, straight(490)), way(2, [north(490), north(510)], lanes=6), way(3, [north(510), north(1000)])])
    assert short.summarize(path).stretches[WIDE_ROAD] == 0
    # 출발 직후 100m만 큰길: 출발 도로는 세지 않는다
    start = RoadLayer([way(1, straight(100), lanes=6), way(2, [north(100), north(1000)])])
    assert start.summarize(path).stretches[WIDE_ROAD] == 0
    # 가운데 200m 큰길 두 토막(10m 끊김)은 한 구간
    split = RoadLayer(
        [way(1, straight(400)), way(2, [north(400), north(500)], lanes=6), way(3, [north(500), north(510)]),
         way(4, [north(510), north(600)], lanes=6), way(5, [north(600), north(1000)])]
    )
    assert split.summarize(path).stretches[WIDE_ROAD] == 1


# --- 단계 ---


def test_level1_counts_road_stretches_other_levels_do_not():
    stretches = {FAST_ROAD: 1, WIDE_ROAD: 2}
    v1 = violations(LEVELS[1], {"RIGHT_TURN": 2}, stretches)
    assert [(v.skill, v.count) for v in v1] == [(FAST_ROAD, 1), (WIDE_ROAD, 2)]
    assert [user_message(v) for v in v1] == ["제한속도 50km/h보다 빠른 길이 1구간 있어요", "편도 3차로 이상 큰길이 2구간 있어요"]
    assert violations(LEVELS[2], {"LEFT_TURN_SIGNALIZED": 2}, stretches) == []


def test_unknown_road_data_is_not_a_violation():
    assert violations(LEVELS[1], {"RIGHT_TURN": 2}, None) == []


# --- 엔진 ---


class Client:
    def __init__(self, path):
        self.path = path

    async def round_trip(self, lat, lon, distance_m, seed, custom_model):
        if seed:
            raise RouteNotFound("no route")
        return self.path

    async def route(self, points, custom_model=None):
        """내비 경유지 확인. 기본은 경로 없음 → 회전 기준 경유지, coverage None."""
        raise RouteNotFound("no nav route in fake")


def test_course_debug_and_attribution():
    gh = make_gh_path([2, 2], distance=2000)  # 35.0에서 북쪽 300m 갔다 돌아온다
    layer = RoadLayer([way(1, straight(400), lanes=3, oneway=1, speed=30, source="nodelink")])
    body = asyncio.run(engine.generate(Client(gh), SignalIndex([]), LAT0, LON0, 1, 1, seeds=1, roads=layer))
    (c,) = body["courses"]
    road = c["debug"]["road"]
    assert road["maxSpeedKmh"] == 30 and road["maxSpeedConfidence"] == "VERIFIED"
    assert road["stretches"][WIDE_ROAD] == 1  # 편도 3차로 일방통행을 출발 150m 뒤부터 달린다
    assert "편도 3차로 이상 큰길이 1구간 있어요" in c["warnings"]


def test_two_lanes_per_direction_is_debug_only():
    gh = make_gh_path([2, 2], distance=2000)
    layer = RoadLayer([way(1, straight(400), lanes=2, oneway=1, speed=30, source="nodelink")])
    body = asyncio.run(engine.generate(Client(gh), SignalIndex([]), LAT0, LON0, 1, 1, seeds=1, roads=layer))
    (c,) = body["courses"]
    assert c["debug"]["road"]["stretches"] == {FAST_ROAD: 0, WIDE_ROAD: 0, MULTI_LANE_ROAD: 1}
    assert c["warnings"] == [] and c["debug"]["violations"] == []
    assert body["dataNotes"][-2] == roads.ATTRIBUTION


def test_no_attribution_when_course_is_outside_road_data():
    gh = make_gh_path([2, 2], distance=2000)
    far = RoadLayer([way(1, [[36.0, 128.0], [36.01, 128.0]])])
    body = asyncio.run(engine.generate(Client(gh), SignalIndex([]), LAT0, LON0, 1, 1, seeds=1, roads=far))
    assert roads.ATTRIBUTION not in body["dataNotes"]
    assert body["courses"][0]["debug"]["road"]["matchedShare"] == 0.0


def test_analysis_without_road_layer_has_no_road_summary():
    assert analyze(make_gh_path([2]), SignalIndex([])).road is None


def test_real_geumjeong_layer_loads():
    layer = roads.load_road_layer()
    assert len(layer) == 1611
    # 5030 기본값은 관측값이 아니라서 빠른 길 판정에 쓰지 않는다
    assert not any(layer._fast[i] for i, w in enumerate(layer.ways) if w["speedSource"] == "default_5030")


def test_missing_road_file_raises(tmp_path):
    with pytest.raises(FileNotFoundError):
        roads.load_road_layer(tmp_path / "nope.json")
