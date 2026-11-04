"""후보 생성 → 분석 → 채점 → 선택. 결과는 ARCHITECTURE.md 4.1 코스 JSON.

선택 규칙: 단계 조건 위반 정도(합계)가 적은 순, 같으면 짧은 순. 조건을 모두 만족하는 후보가 있으면
그것이 먼저 오고, 모자라면 위반이 적은 후보로 채운다. 위반이 있는 코스는 warnings에 쉬운 말로 알리고
dataNotes에 대체 선택임을 밝힌다. 쉬운 코스라고 조용히 주장하지 않는다.

warnings·dataNotes는 초보 운전자에게 그대로 보이는 문장이다. 단계 기준 같은 기계용 내용은 코스의 debug에만
넣는다(앱은 모르는 필드를 무시한다, ARCHITECTURE.md 7장 16).
"""

import asyncio
import hashlib
import json
import logging
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from .analysis import (
    LEFT_TURN_SIGNALIZED,
    LEFT_TURN_UNKNOWN,
    RIGHT_TURN,
    ROUNDABOUT,
    SCHOOL_ZONE,
    U_TURN,
    Analysis,
    analyze,
    waypoint_candidates,
)
from .geo import SignalIndex, route_match
from .graphhopper import GraphHopperClient, RouteNotFound, UpstreamUnavailable
from .levels import LEVELS, LevelSpec, Violation, user_message, violations
from .roads import RoadLayer
from .zones import SCHOOL_ZONES, ZoneLayer

log = logging.getLogger(__name__)

SCHEMA_VERSION = 1
DEFAULT_SEEDS = 24  # 번들 코스(build_demo_courses)와 같은 후보 수. 16이면 부산대 앞 3단계 딱 맞는 코스(s23)를 놓친다

NOTE_UNKNOWN_LEFT = "신호등 정보가 없는 좌회전은 어려운 쪽으로 보고 골랐어요"
NOTE_OSM = "도로 데이터 © OpenStreetMap contributors (ODbL)"
WARN_NAV_DIFFERS = "내비 안내가 코스와 다를 수 있어요 — 화면의 코스 선을 따라가 주세요"
# 경유지 2개로 이은 내비 경로와 코스의 일치도(geo.route_match)가 이 값 이상이어야 "코스대로 도는 코스".
# 코드 리뷰 실측: 경유지 3개 중 가운데를 빼면 순환의 40–45%, 고르게 1/3·2/3에 두어도 12–28%를 지름길로 건너뛴다
NAV_MIN_MATCH = 0.95
NAV_MAX_PAIRS = 16  # 코스 하나에 시험하는 경유지 후보 쌍 상한
NAV_BATCH = 4  # 경유지 후보를 한 번에 시험하는 수
NAV_CHECK_FACTOR = 3  # 경유지를 확인하는 코스 수 상한 = 이 값 × count
NOTE_SCHOOL_ESTIMATE = "어린이보호구역은 학교·유치원 등 시설 주변 {m}m로 어림한 범위예요"


class NoCourse(Exception):
    pass


class AnalysisFailed(Exception):
    """GraphHopper 응답은 형식 검사를 통과했는데 우리 분석이 실패했다(우리 버그). 서버는 500 INTERNAL로 답한다."""


@dataclass(frozen=True)
class Candidate:
    seed: int
    analysis: Analysis
    violations: list[Violation]

    @property
    def penalty(self) -> int:
        return sum(v.amount for v in self.violations)


def score(spec: LevelSpec, seed: int, analysis: Analysis) -> Candidate:
    stretches = analysis.road.stretches if analysis.road is not None else None
    return Candidate(seed, analysis, violations(spec, analysis.skill_counts, stretches))


def school_entries(c: Candidate) -> int:
    return c.analysis.skill_counts.get(SCHOOL_ZONE, 0)


def missing_focus(c: Candidate, focus: frozenset[str]) -> int:
    return sum(1 for skill in focus if not c.analysis.skill_counts.get(skill))


def select(spec: LevelSpec, candidates: list[Candidate], count: int, focus: frozenset[str] = frozenset()) -> list[Candidate]:
    """거리 이상치와 중복 경로를 빼고 (위반 합계, 빠진 focus 기술 수, 보호구역 진입 수(피하는 단계만), 거리, seed) 순으로 count개.

    focus(목표 경로에서 뽑은 기술)는 단계 조건 다음 기준이다. 단계 조건을 넘어서 고르지 않는다."""
    seen = set()
    pool = []
    for c in candidates:
        if not spec.distance_ok(c.analysis.distance_m):
            continue
        # seed가 달라도 같은 경로가 나오는 일이 잦다(같은 경로면 id 해시도 같다)
        key = (path_hash(c.analysis.path), tuple(m.skill for m in c.analysis.maneuvers))
        if key in seen:
            continue
        seen.add(key)
        pool.append(c)
    avoid_school = float(spec.zone_factors.get(SCHOOL_ZONES, "1")) < 1
    pool.sort(
        key=lambda c: (
            c.penalty,
            missing_focus(c, focus),
            school_entries(c) if avoid_school else 0,
            c.analysis.distance_m,
            c.seed,
        )
    )
    return pool[:count]


def route_warnings(a: Analysis) -> list[str]:
    """경로 자체에 대한 경고(단계와 무관): 신호등 정보 없는 좌회전, 어린이보호구역."""
    out = []
    unknown_left = a.skill_counts.get(LEFT_TURN_UNKNOWN, 0)
    if unknown_left:
        out.append(f"신호등 정보가 없는 좌회전 {unknown_left}곳 — 출발 전에 지도에서 확인해 주세요")
    school = [m for m in a.maneuvers if m.skill == SCHOOL_ZONE]
    if school:
        where = "어린이보호구역을" if len(school) == 1 else f"어린이보호구역 {len(school)}곳을"
        kmh = min(m.speed_limit_kmh or 30 for m in school)
        out.append(f"{where} 지나요 — 시속 {kmh} 이하로 천천히")
    return out


def course_warnings(c: Candidate) -> list[str]:
    """신호등 정보 없는 좌회전은 단계가 허용해도 항상 맨 앞에 알린다. 다음은 어린이보호구역, 나머지는 단계 조건에서 벗어난 점."""
    return route_warnings(c.analysis) + [m for m in map(user_message, c.violations) if m]


def course_title(a: Analysis) -> str:
    """주로 달리는 도로 이름 1–2개 + 코스 성격. 예: '금강로·부산대학로 우회전 순환'."""
    streets = [s for s, _ in sorted(a.street_lengths.items(), key=lambda kv: -kv[1])][:2]
    counts = a.skill_counts
    if counts.get(U_TURN) or counts.get(ROUNDABOUT):
        kind = "회전 기술 조합"
    elif counts.get(LEFT_TURN_UNKNOWN):
        kind = "좌·우회전 순환"
    elif counts.get(LEFT_TURN_SIGNALIZED, 0) >= 2:
        kind = "신호 좌회전 연습"
    elif counts.get(LEFT_TURN_SIGNALIZED):
        kind = "좌·우회전 순환"
    elif counts.get(RIGHT_TURN):
        kind = "우회전 순환"
    else:
        kind = "직진 순환"
    return f"{'·'.join(streets)} {kind}" if streets else f"근처 {kind}"


def maneuvers_json(a: Analysis) -> list[dict[str, Any]]:
    return [
        {
            "index": i,
            "skill": m.skill,
            "street": m.street,
            "lat": m.lat,
            "lon": m.lon,
            "distanceFromStartM": m.distance_from_start_m,
            "confidence": m.confidence,
        }
        for i, m in enumerate(a.maneuvers)
    ]


def path_hash(path: list[list[float]]) -> str:
    """경로 지문 8자리. 같은 출발지·단계·seed라도 데이터·엔진이 바뀌어 경로가 달라지면 id가 바뀐다(ARCHITECTURE.md 4.1)."""
    rounded = [[round(lat, 5), round(lon, 5)] for lat, lon in path]
    return hashlib.sha1(json.dumps(rounded, separators=(",", ":")).encode()).hexdigest()[:8]


def course_json(
    c: Candidate, level: int, lat: float, lon: float, waypoints: list[list[float]], nav_match: float | None
) -> dict[str, Any]:
    a = c.analysis
    warnings = course_warnings(c)
    if nav_match is not None and nav_match < NAV_MIN_MATCH:
        warnings.append(WARN_NAV_DIFFERS)
    return {
        "id": f"L{level}-{lat:.4f}-{lon:.4f}-s{c.seed}-{path_hash(a.path)}",
        "level": level,
        "title": course_title(a),
        "distanceM": a.distance_m,
        "path": a.path,
        "waypoints": waypoints,
        "maneuvers": maneuvers_json(a),
        "skillCounts": dict(sorted(a.skill_counts.items())),
        "warnings": warnings,
        "debug": {
            "seed": c.seed,
            "penalty": c.penalty,
            "violations": [v.as_debug() for v in c.violations],
            "navMatch": nav_match,
            **({"road": a.road.as_debug()} if a.road is not None else {}),
        },
    }


def fallback_note(levels: str, strict: int) -> str:
    """조건에 딱 맞는 코스가 모자라 가까운 코스로 채웠다는 한 줄. levels는 "1" 또는 "1·2·3"."""
    if strict == 0:
        return f"{levels}단계 조건에 딱 맞는 코스가 없어 가장 가까운 코스를 골랐어요"
    return f"{levels}단계 조건에 딱 맞는 코스가 {strict}개뿐이라 가장 가까운 코스로 채웠어요"


def school_note(courses: list[dict[str, Any]], zones: ZoneLayer | None) -> list[str]:
    """보호구역을 지나는 코스가 있으면 범위가 어림이라는 한 줄."""
    if zones is None or not any(c["skillCounts"].get(SCHOOL_ZONE) for c in courses):
        return []
    return [NOTE_SCHOOL_ESTIMATE.format(m=round(zones.buffer_m))]


def data_notes(spec: LevelSpec, chosen: list[Candidate], attribution: list[str], extra: list[str] = ()) -> list[str]:
    """extra: 그 밖의 안내(보호구역 범위 등). attribution: 신호·구역 레이어 출처 표기(공공데이터). 도로망 출처(OSM)는 항상 맨 끝."""
    notes = [NOTE_UNKNOWN_LEFT]
    strict = sum(1 for c in chosen if not c.violations)
    if strict < len(chosen):
        notes.append(fallback_note(str(spec.level), strict))
    notes += extra
    notes += attribution
    notes.append(NOTE_OSM)
    return notes


async def _candidates(
    client: GraphHopperClient,
    signals: SignalIndex,
    zones: ZoneLayer | None,
    roads: RoadLayer | None,
    spec: LevelSpec,
    lat: float,
    lon: float,
    models: list[dict[str, Any]],
) -> tuple[list[Candidate], int, int]:
    """seed별 round_trip(models[seed]) → 분석·채점. 반환: (후보, GraphHopper 오류 수, 경로 없음 수).

    응답 형식은 GraphHopperClient가 끝까지 검사하므로 분석 중 예외는 우리 버그다. 나머지 seed로 응답하되 기록하고,
    후보가 하나도 없으면 AnalysisFailed(500)로 올린다. GraphHopper 탓(503)과 섞지 않는다."""
    results = await asyncio.gather(
        *(client.round_trip(lat, lon, spec.target_m, seed, model) for seed, model in enumerate(models)),
        return_exceptions=True,
    )
    candidates = []
    upstream_errors = route_errors = analysis_errors = 0
    for seed, r in enumerate(results):
        if isinstance(r, UpstreamUnavailable):
            upstream_errors += 1
        elif isinstance(r, RouteNotFound):
            route_errors += 1
        elif isinstance(r, BaseException):
            raise r
        else:
            try:
                analysis = analyze(r, signals, zones, roads)
            except Exception:
                if not analysis_errors:
                    log.error("analysis_failed", extra={"fields": {"seed": seed}}, exc_info=True)
                analysis_errors += 1
                continue
            candidates.append(score(spec, seed, analysis))
    if analysis_errors and not candidates:
        raise AnalysisFailed(f"{analysis_errors} seeds failed in analysis")
    return candidates, upstream_errors, route_errors


async def nav_waypoints(
    client: GraphHopperClient, lat: float, lon: float, a: Analysis, deadline: float | None = None
) -> tuple[list[list[float]], float | None]:
    """내비가 이 순환을 따라 돌게 하는 경유지 2개와 그 일치도(route_match).

    출발지 → 경유지1 → 경유지2 → 출발지를 GraphHopper 기본 차량 경로(내비 경로 탐색의 근사)로 이어 보고, 코스와
    가장 같은 후보를 고른다(analysis.waypoint_candidates 순서, NAV_MAX_PAIRS개까지). NAV_MIN_MATCH를 넘으면 멈춘다.
    GraphHopper가 하나도 답하지 않으면 회전 기준 후보를 그대로 쓰고 일치도는 None(확인 못 함).
    deadline(이벤트 루프 시각)을 넘기면 도는 요청을 끊고 지금까지 가장 잘 맞은 경유지를 일치도 None(확인 못 함)으로 준다.
    """
    candidates = waypoint_candidates(a.path, a.maneuvers)[:NAV_MAX_PAIRS]
    if not candidates:
        return [], None
    best: tuple[list[list[float]], float | None] = (candidates[0], None)
    origin = (lat, lon)
    loop = asyncio.get_running_loop()
    for i in range(0, len(candidates), NAV_BATCH):
        remaining = None if deadline is None else deadline - loop.time()
        if remaining is not None and remaining <= 0:
            return best[0], None
        batch = candidates[i : i + NAV_BATCH]
        tasks = [asyncio.ensure_future(client.route([origin, *(tuple(w) for w in pair), origin])) for pair in batch]
        _, pending = await asyncio.wait(tasks, timeout=remaining)
        for t in pending:
            t.cancel()
        await asyncio.gather(*pending, return_exceptions=True)
        for pair, t in zip(batch, tasks):
            if t.cancelled():
                continue
            r = t.exception() or t.result()
            if isinstance(r, (UpstreamUnavailable, RouteNotFound)):
                continue
            if isinstance(r, BaseException):
                raise r
            try:
                nav_path = [[la, lo] for lo, la, *_ in r["points"]["coordinates"]]
            except (KeyError, TypeError, ValueError):
                continue
            match = round(route_match(a.path, nav_path), 3)
            if best[1] is None or match > best[1]:
                best = (pair, match)
        if best[1] is not None and best[1] >= NAV_MIN_MATCH:
            break
        if pending:
            return best[0], None
    return best


async def choose_navigable(
    client: GraphHopperClient,
    spec: LevelSpec,
    candidates: list[Candidate],
    count: int,
    lat: float,
    lon: float,
    focus: frozenset[str],
    check_factor: int = NAV_CHECK_FACTOR,
    deadline: float | None = None,
) -> tuple[list[Candidate], dict[int, tuple[list[list[float]], float | None]]]:
    """순위대로 count개씩 내비 경유지를 확인해 내비가 코스대로 도는 코스(일치도 ≥ NAV_MIN_MATCH, 또는 확인 불가)만
    고른다. 확인은 NAV_CHECK_FACTOR×count개까지(GraphHopper 부하 상한). deadline을 넘기면 확인하지 못한 코스는
    일치도 None(확인 불가 → 통과)이라 이미 만든 round_trip 코스를 버리지 않는다.

    하나도 통과하지 못하면 가장 잘 맞는 코스 1개를 경고와 함께 준다(빈 응답보다 낫다). 그 밖의 미달 코스는 뺀다."""
    ranked = select(spec, candidates, len(candidates), focus)
    nav: dict[int, tuple[list[list[float]], float | None]] = {}
    checked = 0
    while checked < min(len(ranked), check_factor * count):
        batch = ranked[checked : checked + count]
        for c, result in zip(batch, await asyncio.gather(*(nav_waypoints(client, lat, lon, c.analysis, deadline) for c in batch))):
            nav[c.seed] = result
        checked += len(batch)
        if sum(1 for c in ranked[:checked] if _navigable(nav[c.seed][1])) >= count:
            break
    seen = ranked[:checked]
    chosen = [c for c in seen if _navigable(nav[c.seed][1])][:count]
    if not chosen and seen:
        chosen = [max(seen, key=lambda c: nav[c.seed][1] or 0.0)]
    return chosen, nav


def _navigable(match: float | None) -> bool:
    return match is None or match >= NAV_MIN_MATCH


MAX_COUNT = 5  # API count 상한(ARCHITECTURE.md 4.2). 서버는 이만큼 만들어 두고 count로 자른다


@dataclass(frozen=True)
class Plan:
    """한 출발지·단계의 고른 후보(최대 MAX_COUNT). count가 달라도 같은 Plan을 잘라 쓴다(캐시·동시 요청 공유)."""

    spec: LevelSpec
    lat: float
    lon: float
    chosen: list[Candidate]
    nav: dict[int, tuple[list[list[float]], float | None]]  # seed → (경유지, 내비 경로 coverage). coverage None = 확인 못 함
    attribution: list[str]
    zones: ZoneLayer | None
    generated_at: datetime


async def plan(
    client: GraphHopperClient,
    signals: SignalIndex,
    lat: float,
    lon: float,
    level: int,
    count: int = MAX_COUNT,
    seeds: int = DEFAULT_SEEDS,
    now: datetime | None = None,
    zones: ZoneLayer | None = None,
    roads: RoadLayer | None = None,
    focus: frozenset[str] = frozenset(),
    nav_check_factor: int = NAV_CHECK_FACTOR,
    nav_deadline: float | None = None,
) -> Plan:
    """후보 생성·채점·선택. 후보가 하나도 없으면 NoCourse, GraphHopper가 응답하지 않으면 UpstreamUnavailable.

    nav_deadline(이벤트 루프 시각): 내비 경유지 확인 마감. 넘기면 확인을 멈추고 남은 코스는 일치도 None(choose_navigable).

    zones가 있으면 단계별 구역 감점·차단(levels.zone_factors)을 custom_model에 더한다. 짝수 seed는 감점·차단 모두,
    홀수 seed는 차단만 쓴다. 감점만 쓰면 후보가 한쪽으로 몰려 단계 조건에 딱 맞는 코스가 절반으로 준다(실측
    23→12개). 고를 때 단계 조건이 먼저고, 같으면 보호구역을 덜 지나는 코스가 앞선다(select).
    구역 때문에 고를 경로가 하나도 없으면(구역이 출발지를 둘러싼 경우 등) 구역 없이 한 번 더 만든다. seed가 모두
    GraphHopper 오류일 때만(경로 엔진 고장) 다시 만들지 않는다.
    보호구역 진입 지점은 어느 쪽이든 표시한다.
    """
    spec = LEVELS[level]
    models = [spec.custom_model] * seeds
    if zones is not None and spec.zone_factors:
        hard = {k: v for k, v in spec.zone_factors.items() if float(v) == 0}
        avoid = zones.custom_model(spec.custom_model, spec.zone_factors, lat, lon, spec.zone_radius_m)
        block = zones.custom_model(spec.custom_model, hard, lat, lon, spec.zone_radius_m)
        models = [avoid if seed % 2 == 0 else block for seed in range(seeds)]
    candidates, upstream_errors, route_errors = await _candidates(client, signals, zones, roads, spec, lat, lon, models)
    zones_relaxed = False
    if any(m is not spec.custom_model for m in models) and not select(spec, candidates, count) and upstream_errors < seeds:
        zones_relaxed = True
        candidates, upstream_errors, route_errors = await _candidates(
            client, signals, zones, roads, spec, lat, lon, [spec.custom_model] * seeds
        )
    if not candidates and upstream_errors:
        raise UpstreamUnavailable(f"{upstream_errors}/{seeds} seeds failed")
    chosen, nav = await choose_navigable(client, spec, candidates, count, lat, lon, focus, nav_check_factor, nav_deadline)
    nav_cut = nav_deadline is not None and asyncio.get_running_loop().time() >= nav_deadline
    log.info(
        "courses_generated",
        extra={
            "fields": {
                "level": level,
                "candidates": len(candidates),
                "upstream_errors": upstream_errors,
                "route_errors": route_errors,
                "chosen": len(chosen),
                "strict": sum(1 for c in chosen if not c.violations),
                "zone_areas": len(models[0].get("areas", {}).get("features", [])),
                "zones_relaxed": zones_relaxed,
                "nav_checked": len(nav),
                "nav_below_min": sum(1 for c in chosen if not _navigable(nav[c.seed][1])),
                "nav_cut": nav_cut,
            }
        },
    )
    if not chosen:
        raise NoCourse(f"no usable candidate among {seeds} seeds")
    attribution = signals.attribution + (zones.attribution if zones is not None else [])
    if roads is not None and any(c.analysis.road and c.analysis.road.matched_share for c in chosen):
        attribution += roads.attribution
    return Plan(spec, lat, lon, chosen, nav, attribution, zones, now or datetime.now(UTC))


def render(p: Plan, count: int) -> dict[str, Any]:
    """Plan의 앞 count개를 4.1 형식으로. 대체 안내·출처는 잘라낸 코스 기준으로 다시 만든다."""
    chosen = p.chosen[:count]
    courses = [course_json(c, p.spec.level, p.lat, p.lon, *p.nav[c.seed]) for c in chosen]
    return {
        "schemaVersion": SCHEMA_VERSION,
        "origin": {"lat": p.lat, "lon": p.lon},
        "generatedAt": p.generated_at.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "dataNotes": data_notes(p.spec, chosen, p.attribution, school_note(courses, p.zones)),
        "courses": courses,
    }


async def generate(
    client: GraphHopperClient,
    signals: SignalIndex,
    lat: float,
    lon: float,
    level: int,
    count: int,
    seeds: int = DEFAULT_SEEDS,
    now: datetime | None = None,
    zones: ZoneLayer | None = None,
    roads: RoadLayer | None = None,
    focus: frozenset[str] = frozenset(),
    nav_check_factor: int = NAV_CHECK_FACTOR,
) -> dict[str, Any]:
    """4.1 형식 응답(plan + render). 스크립트·테스트용. 서버는 plan을 캐시하고 count마다 render한다."""
    return render(await plan(client, signals, lat, lon, level, count, seeds, now, zones, roads, focus, nav_check_factor), count)


async def route_skills(
    client: GraphHopperClient,
    signals: SignalIndex,
    origin: tuple[float, float],
    destination: tuple[float, float],
    zones: ZoneLayer | None = None,
    roads: RoadLayer | None = None,
) -> dict[str, Any]:
    """목표 경로(출발 → 도착)를 운전 기술로 분해한다(IDEA 3.1, ARCHITECTURE.md 4.2 `/v1/route-skills`).

    경로는 GraphHopper 기본 차량 경로(단계 조건·구역 감점 없음)다. 사용자가 실제로 갈 길에 가깝게 본다.
    경로가 없으면 NoCourse, GraphHopper가 답하지 않으면 UpstreamUnavailable.
    """
    try:
        gh_path = await client.route([origin, destination])
    except RouteNotFound as e:
        raise NoCourse(str(e)) from e
    try:
        a = analyze(gh_path, signals, zones, roads)
    except Exception as e:
        raise AnalysisFailed("route analysis failed") from e
    attribution = signals.attribution + (zones.attribution if zones is not None else [])
    if roads is not None and a.road is not None and a.road.matched_share:
        attribution += roads.attribution
    notes = [NOTE_UNKNOWN_LEFT]
    if zones is not None and a.skill_counts.get(SCHOOL_ZONE):
        notes.append(NOTE_SCHOOL_ESTIMATE.format(m=round(zones.buffer_m)))
    notes += attribution
    notes.append(NOTE_OSM)
    return {
        "schemaVersion": SCHEMA_VERSION,
        "distanceM": a.distance_m,
        "skillCounts": dict(sorted(a.skill_counts.items())),
        "maneuvers": maneuvers_json(a),
        "warnings": route_warnings(a),
        "dataNotes": notes,
    }
