"""단계별 코스 조건(IDEA 3.2). 거리와 횟수는 가설이며 시험 주행으로 조정한다.

- 1단계 출발·정지: 2km 안팎, 우회전 위주, 신호 정보 없는 좌회전·유턴·회전교차로 없음, 저속 일반도로
- 2단계 기술 하나: 3km, 신호 좌회전 2회 이상, 그보다 어려운 요소 없음
- 3단계 기술 조합: 4.5km, 기술 2종 이상, 간선(primary) 허용, 유턴 1회까지, 신호 정보 없는 좌회전 없음
- 4단계 목표 경로 연습: 6km, 3단계와 같되 신호 정보 없는 좌회전 허용, 유턴 2회까지

보호구역·사고다발지역(zones): 1–2단계는 어린이·노인보호구역을 강하게 감점하고 2020년 이후 사고다발지역을 막는다.
3단계는 셋 다 약하게 감점, 4단계는 그대로 둔다. 보호구역을 막지 않고 감점하는 이유는 zones 모듈 설명 참고.
"""

from dataclasses import dataclass, field
from typing import Any

from .analysis import (
    LEFT_TURN_SIGNALIZED,
    LEFT_TURN_UNKNOWN,
    RIGHT_TURN,
    ROUNDABOUT,
    SCHOOL_ZONE,
    U_TURN,
)
from .roads import FAST_ROAD, WIDE_ROAD
from .zones import ACCIDENT_HOTSPOTS, ELDERLY_ZONES, SCHOOL_ZONES

BLOCK_MOTORWAY_TRUNK = {"if": "road_class == MOTORWAY || road_class == TRUNK || road_class_link", "multiply_by": "0"}

# 구역 id → custom_model multiply_by. 가설값이며 시험 주행으로 조정한다
AVOID_ZONES = {SCHOOL_ZONES: "0.05", ELDERLY_ZONES: "0.05", ACCIDENT_HOTSPOTS: "0"}
MILD_ZONES = {SCHOOL_ZONES: "0.5", ELDERLY_ZONES: "0.5", ACCIDENT_HOTSPOTS: "0.5"}


@dataclass(frozen=True)
class LevelSpec:
    level: int
    target_m: int
    custom_model: dict[str, Any]
    max_counts: dict[str, int] = field(default_factory=dict)
    min_counts: dict[str, int] = field(default_factory=dict)
    min_distinct_skills: int = 0
    zone_factors: dict[str, str] = field(default_factory=dict)
    # 도로 속성(roads) 구간 수 상한. GraphHopper 태그가 아니라 표준노드링크로 채운 값이라 선택 단계에서 본다
    max_road_stretches: dict[str, int] = field(default_factory=dict)

    @property
    def zone_radius_m(self) -> float:
        """구역을 모을 출발지 반경. 순환 경로는 출발지에서 거리의 절반 넘게 가지 않고, 허용 거리 상한이 목표의 1.5배다."""
        return 0.75 * self.target_m

    def distance_ok(self, distance_m: float) -> bool:
        # round_trip은 가끔 목표의 수 배(실측 9배)이거나 아주 짧은 경로를 돌려준다
        return 0.5 * self.target_m <= distance_m <= 1.5 * self.target_m


LEVELS: dict[int, LevelSpec] = {
    1: LevelSpec(
        level=1,
        target_m=2000,
        custom_model={
            "priority": [
                BLOCK_MOTORWAY_TRUNK,
                {"if": "road_class == PRIMARY", "multiply_by": "0.2"},
                {"if": "max_speed > 60", "multiply_by": "0.1"},
            ]
        },
        max_counts={LEFT_TURN_UNKNOWN: 0, LEFT_TURN_SIGNALIZED: 2, U_TURN: 0, ROUNDABOUT: 0},
        zone_factors=AVOID_ZONES,
        max_road_stretches={FAST_ROAD: 0, WIDE_ROAD: 0},
    ),
    2: LevelSpec(
        level=2,
        target_m=3000,
        custom_model={
            "priority": [
                BLOCK_MOTORWAY_TRUNK,
                {"if": "max_speed > 60", "multiply_by": "0.3"},
            ]
        },
        min_counts={LEFT_TURN_SIGNALIZED: 2},
        max_counts={LEFT_TURN_UNKNOWN: 0, U_TURN: 0, ROUNDABOUT: 0},
        zone_factors=AVOID_ZONES,
    ),
    3: LevelSpec(
        level=3,
        target_m=4500,
        custom_model={
            "priority": [
                {"if": "road_class == MOTORWAY", "multiply_by": "0"},
                {"if": "road_class == TRUNK", "multiply_by": "0.2"},
                {"if": "max_speed > 70", "multiply_by": "0.3"},
            ]
        },
        max_counts={LEFT_TURN_UNKNOWN: 0, U_TURN: 1},
        min_distinct_skills=2,
        zone_factors=MILD_ZONES,
    ),
    4: LevelSpec(
        level=4,
        target_m=6000,
        custom_model={
            "priority": [
                {"if": "road_class == MOTORWAY", "multiply_by": "0"},
                {"if": "road_class == TRUNK", "multiply_by": "0.5"},
            ]
        },
        max_counts={U_TURN: 2},
        min_distinct_skills=2,
    ),
}


@dataclass(frozen=True)
class Violation:
    """단계 조건에서 벗어난 내용. 사용자 문장은 user_message가 따로 만든다(기계용 값과 섞지 않는다)."""

    kind: str  # "max" | "min" | "distinct"
    skill: str | None  # distinct면 None
    count: int  # 실제 횟수(distinct면 기술 종류 수)
    limit: int  # 조건 값

    @property
    def amount(self) -> int:
        """조건에서 벗어난 정도. 후보 순위에 쓴다."""
        return abs(self.count - self.limit)

    def as_debug(self) -> dict:
        return {"kind": self.kind, "skill": self.skill, "count": self.count, "limit": self.limit}


def violations(spec: LevelSpec, skill_counts: dict[str, int], road_stretches: dict[str, int] | None = None) -> list[Violation]:
    """road_stretches가 None이면(도로 레이어 없음) 도로 조건은 보지 않는다. 모르는 것을 위반으로 세지 않는다."""
    out = []
    for skill, limit in spec.max_counts.items():
        n = skill_counts.get(skill, 0)
        if n > limit:
            out.append(Violation("max", skill, n, limit))
    for skill, need in spec.min_counts.items():
        n = skill_counts.get(skill, 0)
        if n < need:
            out.append(Violation("min", skill, n, need))
    for kind, limit in spec.max_road_stretches.items() if road_stretches is not None else ():
        n = road_stretches.get(kind, 0)
        if n > limit:
            out.append(Violation("max", kind, n, limit))
    distinct = sum(1 for skill, v in skill_counts.items() if v > 0 and skill != SCHOOL_ZONE)
    if distinct < spec.min_distinct_skills:
        out.append(Violation("distinct", None, distinct, spec.min_distinct_skills))
    return out


def user_message(v: Violation) -> str | None:
    """초보 운전자에게 보여줄 짧은 문장. 단계·기준 같은 내부 용어는 쓰지 않는다.

    신호등 정보 없는 좌회전은 단계와 상관없이 따로 알리므로 여기서는 None.
    """
    if v.kind == "distinct":
        return "연습할 기술 종류가 적은 코스예요"
    if v.skill == LEFT_TURN_UNKNOWN:
        return None
    if v.kind == "min":
        return "신호 있는 좌회전이 없어요" if v.count == 0 else f"신호 있는 좌회전이 {v.count}곳뿐이에요"
    return MORE_THAN_LEVEL[v.skill].format(n=v.count)


# 단계 상한을 넘은 기술. 조사까지 맞춘 문장
MORE_THAN_LEVEL = {
    RIGHT_TURN: "우회전이 {n}곳으로 많은 편이에요",
    LEFT_TURN_SIGNALIZED: "좌회전이 {n}곳으로 많은 편이에요",
    U_TURN: "유턴이 {n}곳 있어요",
    ROUNDABOUT: "회전교차로가 {n}곳 있어요",
    FAST_ROAD: "제한속도 50km/h보다 빠른 길이 {n}구간 있어요",
    WIDE_ROAD: "편도 3차로 이상 큰길이 {n}구간 있어요",
}
