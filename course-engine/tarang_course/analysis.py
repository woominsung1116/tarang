"""GraphHopper 경로를 운전 기술(maneuver) 목록으로 분해한다.

신호 유무는 신호 레이어(signal_layer) 점이 회전 지점 30m 안에 있는지로만 판단한다. 점이 없으면
"신호 없음"이 아니라 "정보 없음"(UNKNOWN)이고, 좌회전이면 비보호로 간주해 어려운 쪽으로 분류한다.
어린이보호구역에 들어가는 지점은 SCHOOL_ZONE maneuver로 더한다(zones.school_entries).
"""

from collections import Counter
from dataclasses import dataclass
from typing import Any

from .geo import SignalIndex, cumulative, point_at
from .roads import RoadLayer, RoadSummary
from .zones import ZoneLayer

SIGNAL_RADIUS_M = 30

# GraphHopper instruction sign
LEFT_SIGNS = {-2, -3}
RIGHT_SIGNS = {2, 3}
U_TURN_SIGNS = {-98, -8, 8}
ROUNDABOUT_SIGNS = {6}

RIGHT_TURN = "RIGHT_TURN"
LEFT_TURN_SIGNALIZED = "LEFT_TURN_SIGNALIZED"
LEFT_TURN_UNKNOWN = "LEFT_TURN_UNKNOWN"
U_TURN = "U_TURN"
ROUNDABOUT = "ROUNDABOUT"
SCHOOL_ZONE = "SCHOOL_ZONE"  # 회전 기술이 아니라 구간 진입 지점. 단계의 기술 종류 수에는 세지 않는다

@dataclass(frozen=True)
class Maneuver:
    skill: str
    street: str
    lat: float
    lon: float
    distance_from_start_m: float
    confidence: str  # VERIFIED | UNKNOWN (회전, 신호 근거) | ESTIMATED (보호구역, 시설 중심 원으로 어림)
    speed_limit_kmh: int | None = None  # 보호구역만. 응답에는 넣지 않고 경고 문장에 쓴다


@dataclass(frozen=True)
class Analysis:
    distance_m: int
    path: list[list[float]]  # [lat, lon]
    maneuvers: list[Maneuver]
    skill_counts: dict[str, int]
    street_lengths: dict[str, float]  # 도로명별 주행 거리, 제목용
    road: RoadSummary | None = None  # 차로 수·제한속도 요약. 도로 레이어가 없으면 None


def classify(sign: int, signalized: bool) -> str | None:
    if sign in LEFT_SIGNS:
        return LEFT_TURN_SIGNALIZED if signalized else LEFT_TURN_UNKNOWN
    if sign in RIGHT_SIGNS:
        return RIGHT_TURN
    if sign in U_TURN_SIGNS:
        return U_TURN
    if sign in ROUNDABOUT_SIGNS:
        return ROUNDABOUT
    return None


def analyze(
    gh_path: dict[str, Any], signals: SignalIndex, zones: ZoneLayer | None = None, roads: RoadLayer | None = None
) -> Analysis:
    path = [[round(lat, 6), round(lon, 6)] for lon, lat, *_ in gh_path["points"]["coordinates"]]
    cum = cumulative(path)
    maneuvers = []
    streets: Counter[str] = Counter()
    street_at: list[tuple[int, str]] = []  # (구간 시작 path 인덱스, 도로명)
    for ins in gh_path["instructions"]:
        street = ins.get("street_name") or ""
        if street:
            streets[street] += float(ins.get("distance", 0.0))
        idx = ins["interval"][0]
        street_at.append((idx, street))
        lat, lon = path[idx]
        signalized = signals.near(lat, lon, SIGNAL_RADIUS_M)
        skill = classify(int(ins["sign"]), signalized)
        if skill is None:
            continue
        maneuvers.append(
            Maneuver(
                skill=skill,
                street=street,
                lat=lat,
                lon=lon,
                distance_from_start_m=round(cum[idx], 1),
                confidence="VERIFIED" if signalized else "UNKNOWN",
            )
        )
    if zones is not None:
        for e in zones.school_entries(path):
            street = next((name for idx, name in reversed(street_at) if idx <= e.path_index), "")
            maneuvers.append(Maneuver(SCHOOL_ZONE, street, e.lat, e.lon, e.distance_m, "ESTIMATED", e.speed_limit_kmh))
        maneuvers.sort(key=lambda m: m.distance_from_start_m)
    return Analysis(
        distance_m=round(gh_path["distance"]),
        path=path,
        maneuvers=maneuvers,
        skill_counts=dict(Counter(m.skill for m in maneuvers)),
        street_lengths=dict(streets),
        road=roads.summarize(path) if roads is not None else None,
    )


MAX_WAYPOINTS = 2  # NaviHelper 3.0.5 WaypointIndex는 FIRST/SECOND뿐(ARCHITECTURE.md 4.1)
WAYPOINT_AFTER_TURN_M = 30  # 회전 지점 바로 뒤에 둬야 내비가 그 회전을 하고 지나간다
WAYPOINT_END_CLEAR_M = 100  # 출발·도착 근처 회전은 앵커로 쓰지 않는다(출발지와 겹쳐 순환을 강제하지 못한다)
MIN_ARC_SHARE = 0.15  # 고른 두 지점이 나눈 세 구간 중 가장 짧은 것이 전체의 이 비율 미만이면 고르게 나눈 점을 쓴다
PRACTICE_TURNS = (LEFT_TURN_SIGNALIZED, LEFT_TURN_UNKNOWN, U_TURN, ROUNDABOUT)


def pick_waypoints(path: list[list[float]], maneuvers: list[Maneuver] = ()) -> list[list[float]]:
    """내비가 출발지 → 출발지를 제자리 경로로 끝내지 않고 이 순환을 따라 돌게 하는 경유지 최대 2개.

    연습 회전(좌회전·유턴·회전교차로, 모자라면 우회전) 중 순환을 가장 고르게 나누는 두 개를 골라 각 회전
    WAYPOINT_AFTER_TURN_M 뒤 경로 위 지점에 둔다. 회전 바로 뒤 점이라 내비가 그 회전을 거쳐 간다.
    알맞은 두 회전이 없으면 경로를 거리로 3등분한 두 점.
    """
    if len(path) < 2:
        return []
    cum = cumulative(path)
    total = cum[-1]
    if total == 0:
        return []
    anchors = _waypoint_anchors(maneuvers, total)
    marks = [total / 3, 2 * total / 3]
    best = 0.0
    for i, a in enumerate(anchors):
        for b in anchors[i + 1 :]:
            arc = min(a, b - a, total - b)
            if arc > best:
                best, marks = arc, [a, b]
    if best < MIN_ARC_SHARE * total:
        marks = [total / 3, 2 * total / 3]
    out = []
    for s in marks:
        lat, lon, _ = point_at(path, cum, min(s, total))
        out.append([round(lat, 6), round(lon, 6)])
    return out


def _waypoint_anchors(maneuvers, total: float) -> list[float]:
    """경유지를 둘 경로 거리(회전 지점 + WAYPOINT_AFTER_TURN_M). 연습 회전이 2개 미만이면 우회전도 쓴다."""
    def after(ms):
        return sorted(
            m.distance_from_start_m + WAYPOINT_AFTER_TURN_M
            for m in ms
            if WAYPOINT_END_CLEAR_M <= m.distance_from_start_m <= total - WAYPOINT_END_CLEAR_M
        )

    practice = after([m for m in maneuvers if m.skill in PRACTICE_TURNS])
    if len(practice) >= MAX_WAYPOINTS:
        return practice
    return after([m for m in maneuvers if m.skill in PRACTICE_TURNS or m.skill == RIGHT_TURN])


# 내비 경로 확인(engine.nav_waypoints)에서 먼저 시험할 경로 거리 비율 쌍. 부산대 앞 번들 코스 9개를 0.1–0.9 격자로
# 전수 시험했을 때 가장 잘 따라온 쌍들(뒤쪽 경유지가 끝 가까이 있어야 돌아오는 길을 지름길로 바꾸지 않는다).
# 그다음 나머지 격자(0.1 간격, 두 점 사이 0.2 이상)를 시험한다
FRACTION_PAIRS = ((0.5, 0.9), (0.4, 0.75), (0.3, 0.65), (1 / 3, 2 / 3), (0.45, 0.9), (0.55, 0.9), (0.35, 0.7), (0.25, 0.6))
GRID_PAIRS = tuple((a / 10, b / 10) for a in range(1, 10) for b in range(a + 2, 10))


def waypoint_candidates(path: list[list[float]], maneuvers: list[Maneuver] = ()) -> list[list[list[float]]]:
    """경유지 후보 쌍(시험 순서대로). 회전 기준 pick_waypoints → FRACTION_PAIRS → GRID_PAIRS. 중복은 뺀다."""
    first = pick_waypoints(path, maneuvers)
    if not first:
        return []
    cum = cumulative(path)
    out = [first]
    for fa, fb in FRACTION_PAIRS + GRID_PAIRS:
        pair = []
        for f in (fa, fb):
            lat, lon, _ = point_at(path, cum, cum[-1] * f)
            pair.append([round(lat, 6), round(lon, 6)])
        if pair not in out:
            out.append(pair)
    return out
