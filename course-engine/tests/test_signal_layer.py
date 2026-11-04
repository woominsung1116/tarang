import json

import pytest

from taraang_course import signal_layer as sl
from taraang_course.analysis import analyze
from taraang_course.geo import haversine

from conftest import make_gh_path, turn_point

# 기준 교차점과 그로부터 북쪽 d미터 지점
J = (35.2300, 129.0800)
M_PER_DEG_LAT = 111_195


def north(d_m: float, lon_offset_deg: float = 0.0) -> tuple[float, float]:
    return J[0] + d_m / M_PER_DEG_LAT, J[1] + lon_offset_deg


def controller(kind: str, d_m: float, cid: str = "c1") -> dict:
    lat, lon = north(d_m)
    return {"lat": lat, "lon": lon, "kind": kind, "id": cid}


def test_intersection_controller_snaps_to_nearest_junction():
    far_junction = north(200)
    points, stats = sl.snap_controllers([controller("intersection", 40)], [J, far_junction])
    assert len(points) == 1
    p = points[0]
    assert (p["lat"], p["lon"]) == J  # 제어기함이 아니라 교차점 좌표
    assert p["snapDistanceM"] == pytest.approx(40, abs=0.5)
    assert p["sources"] == ["busan_public"] and p["ids"] == ["c1"]
    assert stats["snapped"] == 1


def test_reversible_lane_is_vehicle_signal():
    points, _ = sl.snap_controllers([controller("reversible_lane", 10)], [J])
    assert len(points) == 1


@pytest.mark.parametrize("kind", ["pedestrian_crossing", "midblock", "unknown"])
def test_pedestrian_and_midblock_signals_are_excluded(kind):
    points, stats = sl.snap_controllers([controller(kind, 5)], [J])
    assert points == []
    assert stats["excluded_kind"] == 1


def test_controller_without_junction_within_60m_is_dropped():
    points, stats = sl.snap_controllers([controller("intersection", 70)], [J])
    assert points == []
    assert stats["no_junction_within_radius"] == 1


def test_controllers_on_same_junction_merge_into_one_point():
    points, stats = sl.snap_controllers([controller("intersection", 30, "a"), controller("intersection", 20, "b")], [J])
    assert len(points) == 1
    assert points[0]["ids"] == ["a", "b"]
    assert points[0]["snapDistanceM"] == pytest.approx(20, abs=0.5)
    assert stats["snapped"] == 2


def test_merge_dedupes_with_osm_within_30m_and_keeps_both_sources():
    snapped = [{"lat": J[0], "lon": J[1], "sources": ["busan_public"], "ids": ["a"], "snapDistanceM": 25.0}]
    osm_near = [list(north(20))]
    merged = sl.merge(osm_near, snapped)
    assert len(merged) == 1
    assert merged[0]["sources"] == ["osm", "busan_public"]
    assert (merged[0]["lat"], merged[0]["lon"]) == north(20)  # OSM 좌표 유지
    assert merged[0]["ids"] == ["a"]

    osm_far = [list(north(50))]
    assert len(sl.merge(osm_far, snapped)) == 2


def test_grid_index_matches_brute_force():
    pts = [north(d, o) for d in range(0, 400, 37) for o in (-0.0007, 0.0, 0.0005)]
    index = sl.GridIndex(pts)
    for q in [north(13, 0.0002), north(190, -0.0004), north(500)]:
        brute = min(((i, haversine(*q, *p)) for i, p in enumerate(pts)), key=lambda x: x[1])
        hit = index.nearest(*q, 60)
        if brute[1] <= 60:
            assert hit == (brute[0], pytest.approx(brute[1]))
        else:
            assert hit is None


def test_load_both_formats(tmp_path):
    plain = tmp_path / "osm.json"
    plain.write_text(json.dumps([[35.0, 129.0]]))
    assert len(sl.load_signal_index(plain)) == 1
    assert sl.load_signal_index(plain).attribution == []

    layer = tmp_path / "merged.json"
    layer.write_text(json.dumps({"attribution": [sl.ATTRIBUTION_BUSAN], "points": [{"lat": 35.0, "lon": 129.0}]}))
    idx = sl.load_signal_index(layer)
    assert len(idx) == 1 and idx.attribution == [sl.ATTRIBUTION_BUSAN]


def test_snapped_public_signal_makes_left_turn_verified(tmp_path):
    """제어기함 좌표(40m)로는 30m 조회에 안 걸리지만, 교차점에 붙이면 신호 좌회전으로 확인된다."""
    lat, lon = turn_point(0)
    box = {"lat": lat + 40 / M_PER_DEG_LAT, "lon": lon, "kind": "intersection", "id": "x"}
    raw_index = sl.SignalIndex([[box["lat"], box["lon"]]])
    assert analyze(make_gh_path([-2]), raw_index).maneuvers[0].confidence == "UNKNOWN"

    points, _ = sl.snap_controllers([box], [(lat, lon)])
    layer = tmp_path / "merged.json"
    layer.write_text(json.dumps({"attribution": [], "points": sl.merge([], points)}))
    m = analyze(make_gh_path([-2]), sl.load_signal_index(layer)).maneuvers[0]
    assert (m.skill, m.confidence) == ("LEFT_TURN_SIGNALIZED", "VERIFIED")


def test_crosswalk_near_turn_does_not_verify_left_turn(tmp_path):
    lat, lon = turn_point(0)
    crosswalk = {"lat": lat, "lon": lon, "kind": "pedestrian_crossing", "id": "p"}
    points, _ = sl.snap_controllers([crosswalk], [(lat, lon)])
    layer = tmp_path / "merged.json"
    layer.write_text(json.dumps({"attribution": [], "points": sl.merge([], points)}))
    assert analyze(make_gh_path([-2]), sl.load_signal_index(layer)).maneuvers[0].skill == "LEFT_TURN_UNKNOWN"


def test_default_layer_is_merged_when_present():
    assert sl.default_signals_file() == (sl.MERGED_FILE if sl.MERGED_FILE.exists() else sl.OSM_ONLY_FILE)
