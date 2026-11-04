import pytest
from conftest import make_gh_path, turn_point

from taraang_course.analysis import Maneuver, analyze, classify, pick_waypoints
from taraang_course.geo import SignalIndex, cumulative, haversine, point_at

NO_SIGNALS = SignalIndex([])


@pytest.mark.parametrize(
    "sign, signalized, skill",
    [
        (-2, False, "LEFT_TURN_UNKNOWN"),
        (-3, False, "LEFT_TURN_UNKNOWN"),
        (-2, True, "LEFT_TURN_SIGNALIZED"),
        (-3, True, "LEFT_TURN_SIGNALIZED"),
        (2, False, "RIGHT_TURN"),
        (3, True, "RIGHT_TURN"),
        (-98, False, "U_TURN"),
        (-8, False, "U_TURN"),
        (8, False, "U_TURN"),
        (6, False, "ROUNDABOUT"),
        (0, False, None),  # 직진
        (1, False, None),  # 약간 오른쪽
        (-1, False, None),
        (7, False, None),  # 차로 유지
        (-7, False, None),
        (4, False, None),  # 도착
        (5, False, None),  # 경유지
    ],
)
def test_classify_gh_signs(sign, signalized, skill):
    assert classify(sign, signalized) == skill


def test_analyze_real_response(pnu_gh_response):
    a = analyze(pnu_gh_response["paths"][0], NO_SIGNALS)
    assert a.distance_m == 1356
    assert [m.skill for m in a.maneuvers] == ["RIGHT_TURN", "LEFT_TURN_UNKNOWN", "RIGHT_TURN", "RIGHT_TURN"]
    assert [m.street for m in a.maneuvers] == ["금강로279번길", "부산대학로64번길", "금강로", "금정로"]
    assert a.skill_counts == {"RIGHT_TURN": 3, "LEFT_TURN_UNKNOWN": 1}
    assert all(m.confidence == "UNKNOWN" for m in a.maneuvers)
    d = [m.distance_from_start_m for m in a.maneuvers]
    assert d == sorted(d) and 0 < d[0] < d[-1] < a.distance_m
    # GraphHopper는 [lon, lat], 코스 JSON은 [lat, lon]
    assert a.path[0] == [35.230986, 129.084092]
    assert max(a.street_lengths, key=a.street_lengths.get) == "금강로"


def test_signal_within_30m_marks_left_turn_verified():
    lat, lon = turn_point(0)
    near = SignalIndex([[lat + 0.0002, lon]])  # 약 22m
    a = analyze(make_gh_path([-2, 2]), near)
    assert a.maneuvers[0].skill == "LEFT_TURN_SIGNALIZED"
    assert a.maneuvers[0].confidence == "VERIFIED"
    assert a.maneuvers[1].confidence == "UNKNOWN"  # 다음 회전은 100m 떨어져 있다


def test_signal_beyond_30m_is_unknown():
    lat, lon = turn_point(0)
    far = SignalIndex([[lat, lon + 0.0004]])  # 약 36m
    assert haversine(lat, lon, lat, lon + 0.0004) > 30
    a = analyze(make_gh_path([-2]), far)
    assert a.maneuvers[0].skill == "LEFT_TURN_UNKNOWN"
    assert a.maneuvers[0].confidence == "UNKNOWN"


def test_maneuver_distance_and_counts():
    a = analyze(make_gh_path([2, -2, -98, 6, 0, 1]), NO_SIGNALS)
    assert [m.skill for m in a.maneuvers] == ["RIGHT_TURN", "LEFT_TURN_UNKNOWN", "U_TURN", "ROUNDABOUT"]
    assert a.skill_counts == {"RIGHT_TURN": 1, "LEFT_TURN_UNKNOWN": 1, "U_TURN": 1, "ROUNDABOUT": 1}
    assert a.maneuvers[0].distance_from_start_m == pytest.approx(100, abs=1)
    assert a.maneuvers[3].distance_from_start_m == pytest.approx(400, abs=2)


def test_waypoints_without_turns_split_path_in_thirds():
    path = analyze(make_gh_path([0] * 10), NO_SIGNALS).path
    wps = pick_waypoints(path)
    assert len(wps) == 2
    total = cumulative(path)[-1]
    # 북쪽으로 1.1km 갔다가 직선으로 돌아오는 경로: 1/3 지점은 가는 길, 2/3 지점은 돌아오는 길 위
    assert haversine(*path[0], *wps[0]) == pytest.approx(total / 3, abs=1)
    assert haversine(*path[0], *wps[1]) == pytest.approx(total / 3, abs=1)
    assert all(abs(w[1] - 129.0) < 1e-6 for w in wps)


def square_loop() -> list[list[float]]:
    """한 변 400m 정사각형 순환(북 → 동 → 남 → 서), 총 1.6km."""
    d_lat, d_lon = 400 / 111_195, 400 / (111_195 * 0.8192)
    return [[35.0, 129.0], [35.0 + d_lat, 129.0], [35.0 + d_lat, 129.0 + d_lon], [35.0, 129.0 + d_lon], [35.0, 129.0]]


def turn(skill: str, at_m: float) -> Maneuver:
    return Maneuver(skill, "", 0.0, 0.0, at_m, "UNKNOWN")


def test_waypoints_sit_just_after_the_practice_turns_that_split_the_loop_best():
    path = square_loop()
    ms = [turn("RIGHT_TURN", 400), turn("LEFT_TURN_UNKNOWN", 500), turn("U_TURN", 800), turn("LEFT_TURN_SIGNALIZED", 1100)]
    wps = pick_waypoints(path, ms)
    cum = cumulative(path)
    # 연습 회전 500·800·1100 중 (시작~a, a~b, b~끝) 가장 짧은 구간이 가장 긴 쌍은 500·1100. 각 30m 뒤
    expected = [point_at(path, cum, 530)[:2], point_at(path, cum, 1130)[:2]]
    for w, (lat, lon) in zip(wps, expected):
        assert haversine(w[0], w[1], lat, lon) < 1


def test_waypoints_fall_back_to_right_turns_and_ignore_turns_near_the_ends():
    path = square_loop()
    ms = [turn("RIGHT_TURN", 50), turn("RIGHT_TURN", 400), turn("RIGHT_TURN", 800), turn("RIGHT_TURN", 1200)]
    wps = pick_waypoints(path, ms)
    cum = cumulative(path)
    # 50은 출발 근처라 제외. 430·830 쌍이 세 구간(430, 400, 770) 중 최소가 가장 길다
    expected = [point_at(path, cum, 430)[:2], point_at(path, cum, 830)[:2]]
    for w, (lat, lon) in zip(wps, expected):
        assert haversine(w[0], w[1], lat, lon) < 1


def test_waypoints_use_thirds_when_turns_bunch_together():
    path = square_loop()
    wps = pick_waypoints(path, [turn("LEFT_TURN_UNKNOWN", 700), turn("LEFT_TURN_UNKNOWN", 760)])
    cum = cumulative(path)
    for w, s in zip(wps, (1600 / 3, 3200 / 3)):
        assert haversine(w[0], w[1], *point_at(path, cum, s)[:2]) < 1


def test_waypoints_short_path():
    assert pick_waypoints([[35.0, 129.0]]) == []
    assert pick_waypoints([[35.0, 129.0], [35.0, 129.0]]) == []
    assert len(pick_waypoints([[35.0, 129.0], [35.001, 129.0]])) == 2
