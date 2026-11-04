"""시연용 리플레이 주행 로그(ARCHITECTURE.md 4.3)를 번들 코스에서 만든다.

번들 코스(demo_pnu.json)의 path를 도시 주행 속도로 따라가며 0.5초마다 프레임을 찍는다. 속도는 프레임에
넣지 않는다(앱이 위치 변화로 추정). 그래서 속도 시나리오는 위치 간격으로만 표현된다.

- 순항 40–45km/h, 회전 지점 ±12m에서 15km/h, 일반 감속 1.6m/s²(5.8km/h/s, 급제동 기준 1초 14–15km/h 미만)
- 정지 2회(신호 대기처럼 4초), 끝에서 정지
- 방향지시등: 회전 60m 전부터 회전 후 8m까지 회전 방향(유턴은 LEFT, 회전교차로는 RIGHT)
- nextTurn: 코스 maneuver까지 남은 거리, 마지막 회전 뒤에는 ARRIVE

시나리오
- demo_l1_signal_miss.json (1단계 코스): 깜빡이 누락 1회, 어린이보호구역 예고·진입·30km/h 초과,
  제한속도 50에서 66km/h 약 6초
- demo_l2_warning_brake.json (2단계 코스): 급제동 3회(45→20km/h, 6m/s² = 1초 21.6km/h, brake=true),
  주행 중간에 TIRE_PRESSURE 경고

기대 결과: 리플레이마다 `<이름>.expected.json`(말하는 문장 타입 순서 + 기록 이벤트 수)을 함께 쓴다.
앱 Kotlin 리플레이 테스트가 이 파일로 판정한다(단일 판정 기준). 값은 시나리오가 약속한 것(Scenario.expect_*)을
tests/feedback_sim.py(앱 엔진의 파이썬 이식, 튜닝 보조)로 확인한 뒤 그 결과를 적는다. 약속이 깨지면 쓰지 않고 멈춘다.

사용: uv run python scripts/build_replays.py
"""

import json
import math
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

from tarang_course.geo import bearing, cumulative, point_at

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT.parent / "tarang-android" / "app" / "src" / "main" / "assets"
COURSES = ASSETS / "courses" / "demo_pnu.json"
REPLAY_DIR = ASSETS / "replay"

DT = 0.5
KMH = 1 / 3.6
ACCEL = 1.5  # m/s²
ACCEL_FAST = 2.2  # 과속 구간 진입
DECEL = 1.6  # m/s², 회전 전 감속
STOP_DECEL = 0.9  # m/s², 정지 직전. 신호 대기처럼 부드럽게 선다(앱 급제동 기준 1초 14km/h에 한참 못 미침)
HARD_DECEL = 6.0  # m/s², 급제동
HARD_BRAKE_TO = 20 * KMH
TURN_SPEED = 15 * KMH
TURN_ZONE_M = 12
SIGNAL_LEAD_M = 60
SIGNAL_TAIL_M = 8
MIN_SPACING_M = 40  # 앱 turnSignalMinSpacingM: 앞 회전과 이보다 붙은 회전은 깜빡이 판정을 하지 않는다
STOP_DWELL_S = 4.0
CAUTION_LEAD_M = 500
SPEED_LIMIT = 50
SCHOOL_LIMIT = 30

# 앱 DriveEventType 전체. 기대 파일에는 0인 것도 적는다(없어야 하는 기록을 판정하려고)
EVENT_TYPES = ("TURN_SIGNAL_OK", "TURN_SIGNAL_MISSED", "SPEEDING", "SCHOOL_ZONE_SPEEDING", "HARD_BRAKE", "NEW_WARNING", "OFF_COURSE")

TURN_TYPE = {
    "RIGHT_TURN": "RIGHT",
    "LEFT_TURN_SIGNALIZED": "LEFT",
    "LEFT_TURN_UNKNOWN": "LEFT",
    "U_TURN": "UTURN",
    "ROUNDABOUT": "ROUNDABOUT",
}
SIGNAL_FOR = {"RIGHT": "RIGHT", "LEFT": "LEFT", "UTURN": "LEFT", "ROUNDABOUT": "RIGHT"}


@dataclass
class Scenario:
    file: str
    title: str
    cruise_kmh: float
    stops: list[float] = field(default_factory=list)  # 정지 지점(코스 누적 거리 m)
    speed_ranges: list[tuple[float, float, float]] = field(default_factory=list)  # (시작, 끝, km/h) 순항 속도 덮어쓰기
    school_zone: tuple[float, float] | None = None
    hard_brakes: list[float] = field(default_factory=list)
    missed_signal: set[int] = field(default_factory=set)  # 깜빡이를 켜지 않는 maneuver index
    warning_at_fraction: float | None = None  # 전체 주행 시간 중 TIRE_PRESSURE가 켜지는 비율
    # 약속: 말하는 문장 타입(정확한 순서, 또는 포함만), 기록 이벤트 수(적은 것만 확인)
    expect_spoken: list[str] | None = None
    expect_spoken_includes: list[str] = field(default_factory=list)
    expect_events: dict[str, int] = field(default_factory=dict)


@dataclass
class Tick:
    t: float
    s: float
    v: float
    brake: bool


def turns(course: dict) -> list[dict]:
    """회전 maneuver만. SCHOOL_ZONE은 구간 진입 지점이라 회전·감속·방향지시등 대상이 아니다."""
    return [m for m in course["maneuvers"] if m["skill"] in TURN_TYPE]


def gaps(course: dict) -> list[tuple[float, float]]:
    """회전 지점 사이의 직선 구간 [시작, 끝]. 출발점과 도착점 포함."""
    marks = [0.0] + [m["distanceFromStartM"] for m in turns(course)] + [cumulative(course["path"])[-1]]
    return [(a, b) for a, b in zip(marks, marks[1:])]


def speed_profile(length: float, course: dict, sc: Scenario) -> list[float]:
    """1m 간격 허용 속도. 순항·회전·정지 상한을 두고 뒤에서부터 일반 감속으로 이어지게 깎는다."""
    n = int(length) + 1
    cap = [sc.cruise_kmh * KMH] * n
    for a, b, kmh in sc.speed_ranges:
        for i in range(max(0, int(a)), min(n, int(b) + 1)):
            cap[i] = kmh * KMH
    for m in turns(course):
        d = m["distanceFromStartM"]
        for i in range(max(0, int(d - TURN_ZONE_M)), min(n, int(d + TURN_ZONE_M) + 1)):
            cap[i] = min(cap[i], TURN_SPEED)
    # 0 상한 칸에 들어선 뒤에도 감속 한도 때문에 약 1m 더 굴러가므로 0을 3칸 둔다
    stop = [math.inf] * n
    for p in sc.stops:
        for i in range(int(p), min(n, int(p) + 3)):
            stop[i] = 0.0
    for i in range(max(0, n - 3), n):
        stop[i] = 0.0
    for i in range(n - 2, -1, -1):
        cap[i] = min(cap[i], math.sqrt(cap[i + 1] ** 2 + 2 * DECEL))
        stop[i] = min(stop[i], math.sqrt(stop[i + 1] ** 2 + 2 * STOP_DECEL))
    return [min(a, b) for a, b in zip(cap, stop)]


def simulate(length: float, course: dict, sc: Scenario) -> list[Tick]:
    """0.05초 단위로 적분하고 0.5초마다 기록한다. 큰 시간 간격으로 적분하면 감속이 늦어 정지 지점을 지나친다."""
    allowed = speed_profile(length, course, sc)
    stops = sorted(sc.stops)
    brakes = sorted(sc.hard_brakes)
    sub = 10
    h = DT / sub
    ticks = [Tick(0.0, 0.0, 0.0, True)]
    s = v = 0.0
    dwell = STOP_DWELL_S  # 출발 전 정차
    braking_hard = False
    released_until = -1.0  # 정지 직후 그 지점의 0 상한에 다시 묶이지 않게
    while len(ticks) < 5000:
        t = len(ticks) * DT
        if dwell > 0:
            dwell -= DT
            ticks.append(Tick(t, s, 0.0, True))
            continue
        decelerating = False
        for _ in range(sub):
            if brakes and s >= brakes[0]:
                brakes.pop(0)
                braking_hard = True
            if braking_hard:
                nv = max(v - HARD_DECEL * h, HARD_BRAKE_TO)
                if nv == HARD_BRAKE_TO:
                    braking_hard = False
            else:
                limit = allowed[min(int(s), len(allowed) - 1)]
                if s < released_until:
                    limit = max(limit, 2.0)
                fast = any(a <= s <= b and kmh > 55 for a, b, kmh in sc.speed_ranges)
                nv = max(min(v + (ACCEL_FAST if fast else ACCEL) * h, limit), v - DECEL * h, 0.0)
            decelerating |= nv < v - 1e-6
            s += (v + nv) / 2 * h
            v = nv
            if v == 0:
                break
        if v == 0 and s >= length - 5:
            ticks += [Tick(t + DT * k, s, 0.0, True) for k in range(5)]
            return ticks
        if v == 0:
            if not stops or abs(s - stops[0]) > 3:
                raise RuntimeError(f"unexpected standstill at s={s:.1f}m")
            stops.pop(0)
            dwell = STOP_DWELL_S - DT
            released_until = s + 2
        ticks.append(Tick(t, s, v, decelerating or v == 0))
    raise RuntimeError(f"simulation did not reach the end (s={s:.1f} of {length:.1f}m)")


def frames(course: dict, sc: Scenario, ticks: list[Tick]) -> list[dict]:
    path = course["path"]
    cum = cumulative(path)
    marks = [(m["distanceFromStartM"], TURN_TYPE[m["skill"]], m["index"]) for m in turns(course)]
    zone = sc.school_zone
    warn_t = ticks[-1].t * sc.warning_at_fraction if sc.warning_at_fraction is not None else None
    out = []
    heading = bearing(*path[0], *path[1])
    for tk in ticks:
        s = tk.s
        lat, lon, seg = point_at(path, cum, s)
        if tk.v > 0.5:
            heading = bearing(*path[seg], *path[seg + 1])
        nxt = next((tr for tr in marks if tr[0] > s), None)
        prev = next((tr for tr in reversed(marks) if tr[0] <= s), None)
        signal = "OFF"
        if nxt and nxt[0] - s <= SIGNAL_LEAD_M:
            if nxt[2] not in sc.missed_signal:
                signal = SIGNAL_FOR[nxt[1]]
        elif prev and s - prev[0] <= SIGNAL_TAIL_M and prev[2] not in sc.missed_signal:
            signal = SIGNAL_FOR[prev[1]]
        caution = []
        limit = SPEED_LIMIT
        if zone:
            if zone[0] <= s <= zone[1]:
                caution = [{"type": "SCHOOL_ZONE", "remainM": 0}]
                limit = SCHOOL_LIMIT
            elif 0 < zone[0] - s <= CAUTION_LEAD_M:
                # 올림: 구역 앞에서 0으로 반올림되면 앱이 구역 안(remainM 0)으로 읽는다
                caution = [{"type": "SCHOOL_ZONE", "remainM": math.ceil(zone[0] - s)}]
        warn = ["TIRE_PRESSURE"] if warn_t is not None and tk.t >= warn_t else []
        next_turn = (
            {"type": nxt[1], "distanceM": round(nxt[0] - s, 1)}
            if nxt
            else {"type": "ARRIVE", "distanceM": round(cum[-1] - s, 1)}
        )
        out.append(
            {
                "t": round(tk.t * 1000),
                "lat": round(lat, 7),
                "lon": round(lon, 7),
                "heading": round(heading, 1),
                "speedLimitKmh": limit,
                "turnSignal": signal,
                "brake": tk.brake,
                "warnings": warn,
                "nextTurn": next_turn,
                "caution": caution,
            }
        )
    return out


def separated_turns(course: dict, min_gap: float = 80) -> list[int]:
    """앞 회전과 min_gap 이상 떨어진 좌·우회전 index. 깜빡이 판정이 앞 회전과 섞이지 않는 지점."""
    out = []
    prev = 0.0
    for m in turns(course):
        if m["skill"] in ("RIGHT_TURN", "LEFT_TURN_SIGNALIZED", "LEFT_TURN_UNKNOWN") and m["distanceFromStartM"] - prev >= min_gap:
            out.append(m["index"])
        prev = m["distanceFromStartM"]
    return out


def cluster_start(course: dict, i: int) -> float:
    """turns[i]와 MIN_SPACING_M 미만으로 붙은 앞 회전들 중 첫 회전 위치. 신호 대기 정지는 그 앞에 둔다."""
    ts = turns(course)
    while i > 0 and ts[i]["distanceFromStartM"] - ts[i - 1]["distanceFromStartM"] < MIN_SPACING_M:
        i -= 1
    return ts[i]["distanceFromStartM"]


def signal_miss_scenario(course: dict) -> Scenario:
    """보호구역용 직선(≥380m) 뒤에 과속용 직선(≥340m)이 오는 코스.

    앱은 발화 사이 20초를 두므로 사건을 한 직선에 몰지 않는다. 순서: 보호구역 예고(300m 전) → 보호구역 안
    30km/h 초과 → 그 직선 끝 회전에서 깜빡이 누락 → 다음 긴 직선에서 66km/h.
    - 직선에 들어오기 전 신호 대기 정지: 그 사이 연습 기술 예고(150–50m 전) 구간이 보호구역 예고 뒤 20초 안에 지나간다.
    - 누락 회전은 앞 회전과 40m 넘게 떨어져야 기록 판정 대상이다(앱 turnSignalMinSpacingM). 직선 길이 조건이 이를 보장한다.
    - 누락 회전 25m 앞에서 신호 대기: 회전 ±20m 침묵 밖이라 그동안 깜빡이 안내가 나온다.
    - 앱은 caution의 remainM 0인 동안만 구역 안으로 본다. 구역은 회전 전 90m에서 끝낸다.
    """
    gs = gaps(course)
    ts = turns(course)
    for i, (a, b) in enumerate(gs[:-1]):
        later = [(c, d) for c, d in gs[i + 1 :] if d - c >= 340]
        missed = ts[i] if i < len(ts) else None
        if b - a < 380 or not later or missed is None or TURN_TYPE[missed["skill"]] not in ("LEFT", "RIGHT"):
            continue
        z0 = a + 55
        z1 = min(z0 + 320, b - 90)
        fast, fast_to = later[0]
        stops = [b - 25]
        if i > 0:
            stops.insert(0, cluster_start(course, i - 1) - TURN_ZONE_M - 3)
        return Scenario(
            file="demo_l1_signal_miss.json",
            title="시연: 깜빡이 1회 누락 + 어린이보호구역 + 과속 지속",
            cruise_kmh=40,
            stops=stops,
            speed_ranges=[
                (z0 - 60, z0 + 60, 38),  # 보호구역에 줄이지 않고 들어가 30km/h 초과
                (z0 + 60, z1 + 30, 25),
                (fast + 30, min(fast + 400, fast_to - 80), 66),  # 제한속도 50에서 66km/h. 깜빡이 안내 20초 뒤까지 이어지게
            ],
            school_zone=(z0, z1),
            missed_signal={missed["index"]},
            # 기본 설정 기준(보호구역 진입 예고는 기본 꺼짐 → 그 자리에 연습 기술 예고).
            expect_spoken=["SKILL_PREVIEW", "SCHOOL_ZONE_SPEED", "TURN_SIGNAL_MISSING", "SPEED_LIMIT_SUSTAINED"],
            expect_events={"TURN_SIGNAL_MISSED": 1, "SCHOOL_ZONE_SPEEDING": 1, "SPEEDING": 1, "HARD_BRAKE": 0,
                           "NEW_WARNING": 0, "OFF_COURSE": 0},
        )
    raise SystemExit(f"{course['id']}: 보호구역(380m)·과속(340m) 직선이 순서대로 없습니다")


def warning_brake_scenario(course: dict) -> Scenario:
    long_gaps = sorted((ab for ab in gaps(course) if ab[1] - ab[0] >= 150), key=lambda ab: ab[1] - ab[0], reverse=True)
    points = [a + (b - a) / 2 for a, b in long_gaps[:3]]
    if len(points) < 3 and long_gaps:
        a, b = long_gaps[0]
        points = [a + (b - a) * f for f in (0.3, 0.6, 0.85)]
    if len(points) < 3:
        raise SystemExit(f"{course['id']}: 급제동 3회를 넣을 직선 구간이 부족합니다")
    sep = separated_turns(course)
    by_index = {m["index"]: m for m in course["maneuvers"]}
    stops = [by_index[i]["distanceFromStartM"] - TURN_ZONE_M - 3 for i in sep[-2:]]
    return Scenario(
        file="demo_l2_warning_brake.json",
        title="시연: 급제동 3회 + 주행 중 타이어 공기압 경고",
        cruise_kmh=45,
        stops=stops,
        hard_brakes=sorted(points),
        warning_at_fraction=0.55,
        expect_spoken_includes=["SKILL_PREVIEW", "NEW_WARNING", "HARD_BRAKE_REPEAT"],
        expect_events={"HARD_BRAKE": 3, "NEW_WARNING": 1, "TURN_SIGNAL_MISSED": 0, "SPEEDING": 0, "OFF_COURSE": 0},
    )


def build(course: dict, sc: Scenario) -> dict:
    # maneuver 거리는 path 누적 거리 기준이므로 길이도 path로 잰다(GraphHopper distanceM과 수 m 차이)
    ticks = simulate(cumulative(course["path"])[-1], course, sc)
    return {
        "schemaVersion": 1,
        "courseId": course["id"],
        "title": sc.title,
        "tickMs": round(DT * 1000),
        "frames": frames(course, sc, ticks),
    }


def pick(courses: list[dict], level: int, fits) -> dict:
    """시나리오가 들어가는 가장 짧은 코스. 실제 보호구역(SCHOOL_ZONE)이 없는 코스를 먼저 본다.
    리플레이의 보호구역은 합성 구간이라, 실제 보호구역이 있는 코스에 쓰면 코스 안내와 주행 안내가 어긋난다."""
    level_courses = (c for c in courses if c["level"] == level)
    for c in sorted(level_courses, key=lambda c: (bool(c["skillCounts"].get("SCHOOL_ZONE")), c["distanceM"])):
        try:
            fits(c)
            return c
        except SystemExit:
            continue
    raise SystemExit(f"level {level}: 시나리오에 맞는 번들 코스가 없습니다")


def expected(course: dict, sc: Scenario, data: dict) -> dict:
    """feedback_sim으로 돌려 약속(sc.expect_*)을 확인하고, 앱 테스트가 쓸 기대 결과를 만든다."""
    sys.path.insert(0, str(ROOT / "tests"))
    import feedback_sim

    result = feedback_sim.run(course, data["frames"])
    spoken = result.spoken_types()
    events = Counter(result.event_types())
    problems = []
    if sc.expect_spoken is not None and spoken != sc.expect_spoken:
        problems.append(f"말함 {spoken} != {sc.expect_spoken}")
    problems += [f"말함에 {t} 없음" for t in sc.expect_spoken_includes if t not in spoken]
    problems += [f"{k} {events[k]} != {n}" for k, n in sc.expect_events.items() if events[k] != n]
    if problems:
        raise SystemExit(f"{sc.file}: 시나리오 약속이 깨졌습니다: {'; '.join(problems)}")
    return {
        "schemaVersion": 1,
        "replay": sc.file,
        "courseId": course["id"],
        "spoken": spoken,
        "events": {t: events[t] for t in EVENT_TYPES},
    }


def write_atomic(path: Path, obj: dict, compact: bool) -> None:
    tmp = path.with_suffix(".tmp")  # 반쯤 쓴 파일을 남기지 않는다
    text = json.dumps(obj, ensure_ascii=False, separators=(",", ":")) if compact else json.dumps(obj, ensure_ascii=False, indent=1)
    tmp.write_text(text + "\n")
    tmp.replace(path)


def main() -> None:
    courses = json.loads(COURSES.read_text())["courses"]
    REPLAY_DIR.mkdir(parents=True, exist_ok=True)
    for level, make in ((1, signal_miss_scenario), (2, warning_brake_scenario)):
        course = pick(courses, level, make)
        sc = make(course)
        data = build(course, sc)
        expect = expected(course, sc, data)
        out = REPLAY_DIR / sc.file
        write_atomic(out, data, compact=True)
        write_atomic(out.with_name(out.stem + ".expected.json"), expect, compact=False)
        n = len(data["frames"])
        print(f"{sc.file}: {course['id']} {course['distanceM']}m, {n} frames ({n * DT:.0f}s), {out.stat().st_size // 1024}KB")
        print(f"  expected: {expect['spoken']} {expect['events']}")


if __name__ == "__main__":
    main()
