import asyncio
import json

import pytest
from conftest import STEP_DEG, make_gh_path
from shapely.geometry import Point, shape

from taraang_course import engine, zones
from taraang_course.analysis import SCHOOL_ZONE, analyze
from taraang_course.geo import SignalIndex, haversine
from taraang_course.graphhopper import RouteNotFound
from taraang_course.levels import AVOID_ZONES, LEVELS, violations
from taraang_course.zones import Facility, ZoneLayer

M_PER_DEG_LAT = 111_195
LON = 129.0


def north(m: float) -> float:
    """make_gh_path 경로(129.0 경선, 35.0에서 북쪽) 위 m 지점의 위도."""
    return 35.0 + m / M_PER_DEG_LAT


def school_at(m: float, kmh: int = 30) -> Facility:
    return Facility(north(m), LON, kmh)


def layer(school=(), elderly=(), hotspots=(), buffer_m: float = 150) -> ZoneLayer:
    return ZoneLayer(list(school), list(elderly), list(hotspots), buffer_m)


def straight_path(length_m: float) -> list[list[float]]:
    return [[north(0), LON], [north(length_m), LON]]


# --- 보호구역 진입 지점 ---


def test_entry_is_where_path_crosses_the_buffer():
    (e,) = layer([school_at(500)]).school_entries(straight_path(1000))
    assert e.distance_m == pytest.approx(350, abs=zones.SAMPLE_M)
    assert haversine(e.lat, e.lon, north(500), LON) == pytest.approx(150, abs=zones.SAMPLE_M + 1)
    assert e.speed_limit_kmh == 30


def test_start_inside_zone_is_entry_at_zero():
    (e,) = layer([school_at(50)]).school_entries(straight_path(1000))
    assert e.distance_m == 0


def test_path_away_from_zone_has_no_entry():
    assert layer([Facility(north(500), LON + 0.01, 30)]).school_entries(straight_path(1000)) == []  # 약 900m 옆
    assert layer().school_entries(straight_path(1000)) == []


def test_overlapping_zones_are_one_entry_and_separate_zones_two():
    assert len(layer([school_at(400), school_at(600)]).school_entries(straight_path(2000))) == 1
    assert len(layer([school_at(400), school_at(1400)]).school_entries(straight_path(2000))) == 2


def test_short_gap_between_zones_is_one_entry():
    # 두 원 사이 바깥 구간 30m(<REENTRY_GAP_M)
    assert len(layer([school_at(400), school_at(730)]).school_entries(straight_path(2000))) == 1


def test_entry_uses_lowest_posted_limit():
    (e,) = layer([school_at(500, 30), school_at(520, 20)]).school_entries(straight_path(1000))
    assert e.speed_limit_kmh == 20


def test_buffer_is_configurable():
    path = straight_path(1000)
    school = Facility(north(500), LON + 200 / (M_PER_DEG_LAT * 0.819), 30)  # 경로에서 동쪽 약 200m
    assert layer([school], buffer_m=150).school_entries(path) == []
    assert len(layer([school], buffer_m=300).school_entries(path)) == 1


# --- custom_model ---


def test_custom_model_adds_rules_and_areas_without_touching_base():
    base = LEVELS[1].custom_model
    before = json.dumps(base)
    hotspot = Point(LON, north(1500)).buffer(0.002)
    z = layer([school_at(800)], [school_at(1200)], [hotspot])
    model = z.custom_model(base, AVOID_ZONES, north(0), LON, 2000)
    assert json.dumps(base) == before
    assert model["priority"][: len(base["priority"])] == base["priority"]
    assert model["priority"][len(base["priority"]) :] == [
        {"if": "in_school_zones", "multiply_by": "0.05"},
        {"if": "in_elderly_zones", "multiply_by": "0.05"},
        {"if": "in_accident_hotspots", "multiply_by": "0"},
    ]
    ids = [f["id"] for f in model["areas"]["features"]]
    assert ids == ["school_zones", "elderly_zones", "accident_hotspots"]
    school_area = shape(model["areas"]["features"][0]["geometry"])
    assert school_area.contains(Point(LON, north(800)))
    assert not school_area.contains(Point(LON, north(1000)))


def test_custom_model_is_base_when_nothing_applies():
    base = LEVELS[1].custom_model
    assert layer([school_at(800)]).custom_model(base, {}, north(0), LON, 2000) is base
    far = Facility(36.0, 129.0, 30)  # 100km 밖
    assert layer([far]).custom_model(base, AVOID_ZONES, north(0), LON, 2000) is base


def test_blocked_area_around_origin_is_left_out():
    """출발지를 덮는 차단 구역은 빼야 GraphHopper가 출발 도로를 찾는다. 감점 구역은 둔다."""
    around_origin = Point(LON, north(0)).buffer(0.001)
    base = LEVELS[1].custom_model
    z = layer([school_at(0)], hotspots=[around_origin])
    model = z.custom_model(base, AVOID_ZONES, north(0), LON, 2000)
    assert [f["id"] for f in model["areas"]["features"]] == ["school_zones"]


def test_real_busan_layer_loads_and_stays_small_at_demo_origin():
    z = zones.load_zone_layer()
    assert (len(z.school), len(z.elderly)) == (793, 58)
    assert len(z.hotspots) == 294  # 2020년 이후
    for level in (1, 2, 3):
        spec = LEVELS[level]
        model = z.custom_model(spec.custom_model, spec.zone_factors, 35.231, 129.084, spec.zone_radius_m)
        assert len(json.dumps(model, separators=(",", ":"))) < 40_000
        assert "areas" in model


def test_missing_zone_files_raise():
    with pytest.raises(FileNotFoundError):
        zones.load_zone_layer(zones.ZONES_DIR / "nope")


# --- 분석·엔진 ---


def gh_path_with_school_between_turns() -> tuple[dict, ZoneLayer]:
    """회전 3개(100m 간격). 보호구역은 두 번째 회전 바로 앞(경로 250m 지점)부터."""
    gh = make_gh_path([2, 2, 2], distance=2000)
    return gh, layer([school_at(400)])


def test_school_zone_maneuver_is_inserted_in_order():
    gh, z = gh_path_with_school_between_turns()
    a = analyze(gh, SignalIndex([]), z)
    skills = [m.skill for m in a.maneuvers]
    assert skills == ["RIGHT_TURN", "RIGHT_TURN", SCHOOL_ZONE, "RIGHT_TURN"]
    school = a.maneuvers[2]
    assert school.confidence == "ESTIMATED"
    assert school.street == "길1"  # 진입 지점이 속한 안내 구간의 도로명
    assert a.skill_counts[SCHOOL_ZONE] == 1


def test_school_zone_is_not_a_practice_skill_for_levels():
    v = violations(LEVELS[3], {"RIGHT_TURN": 3, SCHOOL_ZONE: 1})
    assert [x.kind for x in v] == ["distinct"]


class ModelClient:
    """seed → path. 받은 custom_model을 seed별로 남긴다."""

    def __init__(self, by_seed: dict, fail_with_areas: bool = False):
        self.by_seed = by_seed
        self.fail_with_areas = fail_with_areas
        self.models: dict[int, list[dict]] = {}

    async def round_trip(self, lat, lon, distance_m, seed, custom_model):
        self.models.setdefault(seed, []).append(custom_model)
        if self.fail_with_areas and "areas" in custom_model:
            raise RouteNotFound("Connection between locations not found")
        r = self.by_seed.get(seed, RouteNotFound("no route"))
        if isinstance(r, Exception):
            raise r
        return r

    async def route(self, points, custom_model=None):
        """내비 경유지 확인. 기본은 경로 없음 → 회전 기준 경유지, coverage None."""
        raise RouteNotFound("no nav route in fake")


def generate(client, z, level=1, count=3, seeds=4):
    return asyncio.run(engine.generate(client, SignalIndex([]), 35.0, 129.0, level, count, seeds=seeds, zones=z))


def test_course_json_carries_school_zone_warning_and_notes():
    gh, z = gh_path_with_school_between_turns()
    body = generate(ModelClient({0: gh}), z, count=1)
    (c,) = body["courses"]
    school = [m for m in c["maneuvers"] if m["skill"] == SCHOOL_ZONE]
    assert len(school) == 1 and school[0]["index"] == 2
    assert [m["index"] for m in c["maneuvers"]] == list(range(len(c["maneuvers"])))
    assert c["skillCounts"] == {"RIGHT_TURN": 3, SCHOOL_ZONE: 1}
    assert c["warnings"] == ["어린이보호구역을 지나요 — 시속 30 이하로 천천히"]
    assert c["debug"]["violations"] == []  # 1단계 조건 위반이 아니다
    notes = body["dataNotes"]
    assert notes[-1] == engine.NOTE_OSM
    assert notes[-2] == zones.ATTRIBUTION
    assert "어린이보호구역은 학교·유치원 등 시설 주변 150m로 어림한 범위예요" in notes


def test_even_seeds_avoid_zones_odd_seeds_only_block():
    gh, z = gh_path_with_school_between_turns()
    hotspot = Point(129.0, north(1000)).buffer(0.002)
    z = layer(z.school, hotspots=[hotspot])
    client = ModelClient({s: gh for s in range(4)})
    generate(client, z)
    even, odd = client.models[0][0], client.models[1][0]
    assert [f["id"] for f in even["areas"]["features"]] == ["school_zones", "accident_hotspots"]
    assert [f["id"] for f in odd["areas"]["features"]] == ["accident_hotspots"]
    assert client.models[2][0] is even and client.models[3][0] is odd


def test_level4_sends_no_areas():
    gh = make_gh_path([2, -2, 2] + [0] * 30, distance=6000)
    _, z = gh_path_with_school_between_turns()
    client = ModelClient({0: gh})
    generate(client, z, level=4, count=1)
    assert "areas" not in client.models[0][0]


def test_equal_level_fit_prefers_fewer_school_zones():
    plain = make_gh_path([2], distance=2100)  # 200m까지만 가서 보호구역(250m부터)에 닿지 않는다
    gh, z = gh_path_with_school_between_turns()  # 더 짧지만 보호구역 통과
    body = generate(ModelClient({0: gh, 1: plain}), z, count=2)
    assert [f"s{c['debug']['seed']}" for c in body["courses"]] == ["s1", "s0"]
    # 4단계는 보호구역을 피하지 않으므로 짧은 순
    z = layer([school_at(600)])  # 450m부터
    long_gh = make_gh_path([2, -2, 2, 2, 2], distance=5000)  # 600m까지
    long_plain = make_gh_path([2, -2], distance=5100)  # 300m까지
    body = generate(ModelClient({0: long_gh, 1: long_plain}), z, level=4, count=2)
    assert body["courses"][0]["skillCounts"].get(SCHOOL_ZONE) == 1
    assert [f"s{c['debug']['seed']}" for c in body["courses"]] == ["s0", "s1"]


def test_falls_back_to_no_zones_when_zones_leave_no_route():
    gh, z = gh_path_with_school_between_turns()
    z = layer(z.school, hotspots=[Point(129.0, north(1000)).buffer(0.002)])  # 홀수 seed(차단만)도 areas를 갖게
    client = ModelClient({s: gh for s in range(4)}, fail_with_areas=True)
    body = generate(client, z, count=1)
    assert body["courses"]
    assert [len(client.models[s]) for s in range(4)] == [2, 2, 2, 2]
    assert all("areas" not in calls[1] for calls in client.models.values())
    # 보호구역 진입 표시는 그대로
    assert body["courses"][0]["skillCounts"][SCHOOL_ZONE] == 1


def test_without_zone_layer_nothing_changes():
    gh, _ = gh_path_with_school_between_turns()
    client = ModelClient({0: gh})
    body = asyncio.run(engine.generate(client, SignalIndex([]), 35.0, 129.0, 1, 1, seeds=1))
    assert client.models[0][0] is LEVELS[1].custom_model
    assert SCHOOL_ZONE not in body["courses"][0]["skillCounts"]
    assert zones.ATTRIBUTION not in body["dataNotes"]


def test_step_matches_conftest():
    # make_gh_path 점 간격이 약 100m라는 가정(gh_path_with_school_between_turns)
    assert STEP_DEG * M_PER_DEG_LAT == pytest.approx(100, abs=1)
