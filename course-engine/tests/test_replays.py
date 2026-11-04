"""번들 리플레이 로그가 4.3 형식이고, 앱 피드백 엔진에서 의도한 문장·기록만 나오는지 확인한다.

판정은 앱 FeedbackEngine을 옮긴 feedback_sim으로 한다(속도 추정·발화 제한 포함). 앱 규칙이 바뀌면 그쪽도 같이 바꾼다.
"""

import json
import math
from collections import Counter
from pathlib import Path

import feedback_sim
import pytest

from tarang_course.geo import haversine

ASSETS = Path(__file__).resolve().parents[2] / "tarang-android" / "app" / "src" / "main" / "assets"
COURSES = {c["id"]: c for c in json.loads((ASSETS / "courses" / "demo_pnu.json").read_text())["courses"]}
FRAME_KEYS = {"t", "lat", "lon", "heading", "speedLimitKmh", "turnSignal", "brake", "warnings", "nextTurn", "caution"}
REPLAYS = ["demo_l1_signal_miss.json", "demo_l2_warning_brake.json"]


def load(name: str) -> dict:
    return json.loads((ASSETS / "replay" / name).read_text())


def simulate(name: str) -> feedback_sim.Result:
    data = load(name)
    return feedback_sim.run(COURSES[data["courseId"]], data["frames"])


def tick_speeds(frames: list[dict]) -> list[float]:
    """직전 프레임과의 거리로 구한 km/h."""
    out = [0.0]
    for a, b in zip(frames, frames[1:]):
        out.append(haversine(a["lat"], a["lon"], b["lat"], b["lon"]) / ((b["t"] - a["t"]) / 1000) * 3.6)
    return out


def segment_distance(lat: float, lon: float, a: list[float], b: list[float]) -> float:
    """점에서 선분 ab까지 거리(m). 수 km 범위라 평면 근사로 충분하다."""
    ky, kx = 111_320, 111_320 * math.cos(math.radians(lat))
    ax, ay, bx, by = (a[1] - lon) * kx, (a[0] - lat) * ky, (b[1] - lon) * kx, (b[0] - lat) * ky
    dx, dy = bx - ax, by - ay
    f = 0.0 if dx == dy == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / (dx * dx + dy * dy)))
    return math.hypot(ax + f * dx, ay + f * dy)


@pytest.mark.parametrize("name", REPLAYS)
def test_replay_format(name):
    data = load(name)
    assert data["schemaVersion"] == 1 and data["tickMs"] == 500 and data["title"]
    assert data["courseId"] in COURSES
    frames = data["frames"]
    assert 100 < len(frames) < 600
    for k, f in enumerate(frames):
        assert set(f) == FRAME_KEYS
        assert f["t"] == k * 500
        assert f["turnSignal"] in ("OFF", "LEFT", "RIGHT")
        assert f["nextTurn"]["type"] in ("LEFT", "RIGHT", "UTURN", "STRAIGHT", "ROUNDABOUT", "ARRIVE")
        assert all(c["type"] in ("SCHOOL_ZONE", "ACCIDENT_PRONE_AREA", "SPEED_BUMP", "OTHER") for c in f["caution"])
        assert 0 <= f["heading"] < 360


@pytest.mark.parametrize("name", REPLAYS)
def test_replay_stays_on_course_path(name):
    data = load(name)
    path = COURSES[data["courseId"]]["path"]
    for f in data["frames"]:
        assert min(segment_distance(f["lat"], f["lon"], a, b) for a, b in zip(path, path[1:])) < 1
    first, last = data["frames"][0], data["frames"][-1]
    assert haversine(first["lat"], first["lon"], *path[0]) < 1
    assert haversine(last["lat"], last["lon"], *path[-1]) < 5
    assert last["nextTurn"]["type"] == "ARRIVE"


@pytest.mark.parametrize("name", REPLAYS)
def test_city_speeds_and_stops(name):
    v = tick_speeds(load(name)["frames"])
    assert max(v) < 70  # 앱은 180km/h 넘는 이동을 GPS 튐으로 버린다
    assert any(0 < x < 18 for x in v)  # 회전 전 감속
    stops = sum(1 for a, b in zip(v, v[1:]) if a > 0.5 and b < 0.5)
    assert stops >= 3  # 중간 정지 2회 + 도착


@pytest.mark.parametrize("name", REPLAYS)
def test_nothing_spoken_back_to_back(name):
    """한 문장씩 들리도록 발화가 20초 간격 제한에 걸려 밀리지 않고 떨어져 있다."""
    times = [t for t, _ in simulate(name).spoken]
    assert all(b - a >= 20_000 for a, b in zip(times, times[1:]))


def judged_turns(course: dict) -> int:
    """앱이 깜빡이 기록 판정을 하는 회전 수: 좌·우회전·유턴 중 앞 회전(첫 회전은 출발점)과 40m 이상 떨어진 것.
    회전교차로는 판정하지 않는다(ARCHITECTURE.md 5장)."""
    n, prev = 0, 0.0
    for m in course["maneuvers"]:
        if m["skill"] == "SCHOOL_ZONE":
            continue
        if m["skill"] != "ROUNDABOUT" and m["distanceFromStartM"] - prev >= feedback_sim.MIN_SPACING_M:
            n += 1
        prev = m["distanceFromStartM"]
    return n


def test_signal_miss_replay_in_app():
    # 기본 설정: 보호구역 진입 예고(SCHOOL_ZONE_AHEAD)는 차량 내비가 하므로 꺼져 있고, 그 자리에 연습 기술 예고가 나간다.
    r = simulate("demo_l1_signal_miss.json")
    assert r.spoken_types() == ["SKILL_PREVIEW", "SCHOOL_ZONE_SPEED", "TURN_SIGNAL_MISSING", "SPEED_LIMIT_SUSTAINED"]
    events = Counter(r.event_types())
    assert events["TURN_SIGNAL_MISSED"] == 1
    course = COURSES[load("demo_l1_signal_miss.json")["courseId"]]
    assert events["TURN_SIGNAL_OK"] == judged_turns(course) - 1 >= 1
    assert events["SCHOOL_ZONE_SPEEDING"] == 1
    assert events["SPEEDING"] == 1
    for absent in ("HARD_BRAKE", "NEW_WARNING", "OFF_COURSE"):
        assert events[absent] == 0, absent


def test_school_zone_ahead_is_spoken_when_the_user_turns_it_on():
    data = load("demo_l1_signal_miss.json")
    r = feedback_sim.run(COURSES[data["courseId"]], data["frames"], enabled=feedback_sim.DEFAULT_ENABLED | {"SCHOOL_ZONE_AHEAD"})
    assert r.spoken_types() == ["SCHOOL_ZONE_AHEAD", "SCHOOL_ZONE_SPEED", "TURN_SIGNAL_MISSING", "SPEED_LIMIT_SUSTAINED"]
    # 끄든 켜든 기록(이벤트)은 같다: 설정은 말할지 말지만 바꾼다.
    assert Counter(r.event_types()) == Counter(simulate("demo_l1_signal_miss.json").event_types())


def test_warning_brake_replay_in_app():
    r = simulate("demo_l2_warning_brake.json")
    spoken = r.spoken_types()
    for want in ("SKILL_PREVIEW", "NEW_WARNING", "HARD_BRAKE_REPEAT"):
        assert want in spoken
    for absent in ("SCHOOL_ZONE_AHEAD", "SCHOOL_ZONE_SPEED", "SPEED_LIMIT_SUSTAINED", "TURN_SIGNAL_MISSING"):
        assert absent not in spoken
    events = Counter(r.event_types())
    assert events["HARD_BRAKE"] == 3  # 정지할 때는 급제동으로 잡히지 않는다
    assert events["NEW_WARNING"] == 1
    course = COURSES[load("demo_l2_warning_brake.json")["courseId"]]
    assert events["TURN_SIGNAL_MISSED"] == 0 and events["TURN_SIGNAL_OK"] == judged_turns(course) >= 3
    assert events["SPEEDING"] == 0 and events["OFF_COURSE"] == 0


def test_tire_warning_appears_mid_drive_and_stays():
    frames = load("demo_l2_warning_brake.json")["frames"]
    on = [k for k, f in enumerate(frames) if f["warnings"] == ["TIRE_PRESSURE"]]
    assert frames[0]["warnings"] == []  # 첫 프레임 경고는 기준선이라 알리지 않는다
    assert 0.2 * len(frames) < on[0] < 0.8 * len(frames)
    assert on == list(range(on[0], len(frames)))


def test_school_zone_inside_is_remain_zero_with_30_limit():
    frames = load("demo_l1_signal_miss.json")["frames"]
    inside = [f for f in frames if {"type": "SCHOOL_ZONE", "remainM": 0} in f["caution"]]
    assert inside and all(f["speedLimitKmh"] == 30 for f in inside)
    approach = [c["remainM"] for f in frames for c in f["caution"] if c["remainM"] > 0]
    assert max(approach) > 300 and approach == sorted(approach, reverse=True)


def braking_frames(from_kmh: float, to_kmh: float, decel_s: float) -> tuple[dict, list[dict]]:
    """from_kmh로 달리다 decel_s초 동안 일정하게 to_kmh까지 줄이고(브레이크 밟음) 그 속도로 4초 더 간다."""
    course = {"maneuvers": [], "path": [[35.0, 129.0], [35.02, 129.0]]}
    frames, lat, t = [], 35.0, 0.0
    while t < 20 + decel_s + 4:
        if t < 20:
            v = from_kmh
        elif t < 20 + decel_s:
            v = from_kmh + (to_kmh - from_kmh) * (t - 20) / decel_s
        else:
            v = to_kmh
        frames.append(
            {"t": round(t * 1000), "lat": lat, "lon": 129.0, "speedLimitKmh": 80, "turnSignal": "OFF", "brake": t >= 19.5,
             "warnings": [], "nextTurn": {"type": "ARRIVE", "distanceM": 900.0}, "caution": []}
        )
        lat += v / 3.6 * 0.5 / 111_195
        t += 0.5
    return course, frames


@pytest.mark.parametrize(
    ("from_kmh", "to_kmh", "decel_s", "hard"),
    [
        (45, 20, 1.0, True),  # 30 초과: 1초 25km/h 감속
        (45, 31, 1.0, False),  # 1초 14km/h, 30 초과 기준(15) 미달
        (25, 10, 1.0, True),  # 30 이하: 1초 15km/h ≥ 14, 끝 속도 10 ≥ 6
        (25, 12, 1.0, False),  # 1초 13km/h, 30 이하 기준(14) 미달
        (18, 0, 1.0, False),  # 1초 18km/h지만 끝 속도 0: 정상 정지(5km/h 이하)로 보고 세지 않는다
        (20, 4, 1.0, False),  # 끝 속도 4: 정상 정지
        (40, 0, 1.0, True),  # 1초 만에 서면 중간(약 20km/h)에서 이미 15km/h 넘게 줄어 1건. 앱 HardBrakeRule과 같다
        (40, 0, 8.0, False),  # 리플레이의 정지처럼 천천히 서면 세지 않는다
        (45, 20, 3.0, False),  # 같은 감속을 3초에 나눠 하면 1초 8km/h대
    ],
)
def test_simulator_hard_brake_thresholds(from_kmh, to_kmh, decel_s, hard):
    """feedback_sim이 ARCHITECTURE.md 5장 급제동 정의(eTAS 택시 값)를 앱처럼 판정하는지."""
    course, frames = braking_frames(from_kmh, to_kmh, decel_s)
    assert feedback_sim.run(course, frames).event_types() == (["HARD_BRAKE"] if hard else [])


def test_simulator_ignores_jitter_when_standing_still():
    """정지 중 위치 떨림: 0.5초 창 속도는 튀어도 평활 속도가 10km/h 미만이면 급제동이 아니다."""
    course = {"maneuvers": [], "path": [[35.0, 129.0], [35.01, 129.0]]}
    frames = []
    for k in range(40):
        jitter = (6 / 111_195) if k == 30 else 0.0  # 한 번 6m 튐 → 짧은 창 약 43km/h
        frames.append(
            {"t": k * 500, "lat": 35.0 + jitter, "lon": 129.0, "speedLimitKmh": 50, "turnSignal": "OFF", "brake": True,
             "warnings": [], "nextTurn": {"type": "ARRIVE", "distanceM": 900.0}, "caution": []}
        )
    assert feedback_sim.run(course, frames).event_types() == []


def test_simulator_speaks_simultaneous_warnings_once():
    course = {"maneuvers": [], "path": [[35.0, 129.0], [35.01, 129.0]]}
    frames, lat = [], 35.0
    for k in range(80):
        warnings = ["ENGINE", "TIRE_PRESSURE"] if k >= 10 else []
        frames.append(
            {"t": k * 500, "lat": lat, "lon": 129.0, "speedLimitKmh": 50, "turnSignal": "OFF", "brake": False,
             "warnings": warnings, "nextTurn": {"type": "ARRIVE", "distanceM": 900.0}, "caution": []}
        )
        lat += 30 / 3.6 * 0.5 / 111_195
    r = feedback_sim.run(course, frames)
    assert r.event_types().count("NEW_WARNING") == 2 and r.spoken_types() == ["NEW_WARNING"]


def test_simulator_school_zone_uses_posted_limit():
    """보호구역 안 과속은 지점 제한속도 기준이고, 없을 때만 30km/h."""
    course = {"maneuvers": [], "path": [[35.0, 129.0], [35.02, 129.0]]}

    def drive(kmh: float, posted):
        frames, lat = [], 35.0
        for k in range(40):
            frames.append(
                {"t": k * 500, "lat": lat, "lon": 129.0, "speedLimitKmh": posted, "turnSignal": "OFF", "brake": False,
                 "warnings": [], "nextTurn": {"type": "ARRIVE", "distanceM": 900.0},
                 "caution": [{"type": "SCHOOL_ZONE", "remainM": 0}]}
            )
            lat += kmh / 3.6 * 0.5 / 111_195
        return Counter(feedback_sim.run(course, frames).event_types())["SCHOOL_ZONE_SPEEDING"]

    assert drive(25, 20) == 1  # 지점 제한 20
    assert drive(25, None) == 0  # 기본 30
    assert drive(35, None) == 1
    assert drive(35, 40) == 0


@pytest.mark.parametrize("name", REPLAYS)
def test_expected_file_matches_replay_and_bundle(name):
    """앱 리플레이 테스트가 읽는 기대 결과가 지금 리플레이·번들 코스와 맞는다(코스를 다시 만들고 리플레이를 안 만든 경우를 잡는다)."""
    expected = json.loads((ASSETS / "replay" / name.replace(".json", ".expected.json")).read_text())
    data = load(name)
    assert expected["replay"] == name and expected["courseId"] == data["courseId"] in COURSES
    r = simulate(name)
    assert expected["spoken"] == r.spoken_types()
    events = Counter(r.event_types())
    assert expected["events"] == {t: events[t] for t in expected["events"]}
    assert set(expected["events"]) == {
        "TURN_SIGNAL_OK", "TURN_SIGNAL_MISSED", "SPEEDING", "SCHOOL_ZONE_SPEEDING", "HARD_BRAKE", "NEW_WARNING", "OFF_COURSE"
    }


def test_drive_that_never_reaches_the_course_is_not_off_course():
    # 같은 주행을 동쪽으로 약 90km 옮기면 코스에 한 번도 오르지 않는다: "이탈"이 아니라 "아직 코스 밖"이다(앱 OffCourseDetector).
    data = load("demo_l1_signal_miss.json")
    far = [dict(f, lon=f["lon"] + 1.0) for f in data["frames"]]
    r = feedback_sim.run(COURSES[data["courseId"]], far)
    assert Counter(r.event_types())["OFF_COURSE"] == 0
