"""앱 번들 코스(오프라인 대체 + 에뮬레이터 시연)를 실행 중인 GraphHopper로 다시 만든다.

출력: taraang-android/app/src/main/assets/courses/demo_pnu.json (ARCHITECTURE.md 4.1)
사용: uv run python scripts/build_demo_courses.py [--gh http://localhost:8989]
"""

import argparse
import asyncio
import json
from datetime import UTC, datetime
from pathlib import Path

from taraang_course import engine
from taraang_course.graphhopper import GraphHopperClient
from taraang_course.signal_layer import default_signals_file, load_signal_index
from taraang_course.roads import load_road_layer
from taraang_course.zones import load_zone_layer

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT.parent / "taraang-android" / "app" / "src" / "main" / "assets" / "courses" / "demo_pnu.json"

ORIGIN = (35.2310, 129.0840)  # 부산대 정문 앞
PLACE = "부산대 앞"
LEVELS = (1, 2, 3)
COUNT = 3
# 서버(24)보다 많다. 내비가 경유지 2개로 코스대로 도는(일치도 0.95 이상) 코스만 넣으려면 후보가 더 필요하다.
# 번들은 오프라인 대체·시연용이라 생성 비용은 상관없다
SEEDS = 96
NAV_CHECK_FACTOR = 32  # 경유지를 확인하는 후보 수(× COUNT). 서버는 3


def distinguish_titles(courses: list[dict]) -> None:
    """도로명·거리까지 같으면 신호 좌회전 수를, 그래도 같으면 순번을 붙인다. 목록에서 같은 제목 두 개는 고를 수 없다."""
    for key in ("signal", "number"):
        seen: dict[str, list[dict]] = {}
        for c in courses:
            seen.setdefault(c["title"], []).append(c)
        for same in (group for group in seen.values() if len(group) > 1):
            for n, c in enumerate(same, 1):
                if key == "signal":
                    c["title"] += f" · 신호 좌회전 {c['skillCounts'].get('LEFT_TURN_SIGNALIZED', 0)}곳"
                else:
                    c["title"] += f" ({n})"


async def build(gh: str) -> dict:
    signals = load_signal_index(default_signals_file())
    zones = load_zone_layer()  # 서버 기본값(어린이보호구역 150m)과 같게
    roads = load_road_layer()
    client = GraphHopperClient(gh, timeout_s=10)
    try:
        results = [await engine.generate(client, signals, *ORIGIN, level, COUNT, seeds=SEEDS, zones=zones, roads=roads, nav_check_factor=NAV_CHECK_FACTOR) for level in LEVELS]
    finally:
        await client.aclose()
    courses = []
    strict_by_level: dict[int, int] = {}  # 조건에 딱 맞는 코스 수, 대체 코스가 섞인 단계만
    for level, r in zip(LEVELS, results):
        if any(c["debug"]["penalty"] for c in r["courses"]):
            strict_by_level[level] = sum(1 for c in r["courses"] if not c["debug"]["penalty"])
        for c in r["courses"]:
            # 같은 단계에 도로명이 같은 코스가 생기므로 거리로 구분한다
            c["title"] = f"{PLACE} {c['title']} {c['distanceM'] / 1000:.1f}km"
        distinguish_titles(r["courses"])
        courses += r["courses"]
    # 단계별 대체 안내는 딱 맞는 코스 수가 같은 단계끼리 한 줄로 합친다
    notes = [engine.NOTE_UNKNOWN_LEFT]
    for strict in sorted(set(strict_by_level.values())):
        levels = "·".join(str(lv) for lv, n in strict_by_level.items() if n == strict)
        notes.append(engine.fallback_note(levels, strict))
    notes += engine.school_note(courses, zones)
    notes += signals.attribution + zones.attribution + roads.attribution
    notes.append(engine.NOTE_OSM)
    return {
        "schemaVersion": engine.SCHEMA_VERSION,
        "origin": {"lat": ORIGIN[0], "lon": ORIGIN[1]},
        "generatedAt": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "dataNotes": notes,
        "courses": courses,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gh", default="http://localhost:8989")
    args = ap.parse_args()
    data = asyncio.run(build(args.gh))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    # 앱이 반쯤 쓴 파일을 번들하지 않도록 임시 파일에 쓰고 바꿔 끼운다
    tmp = OUT.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n")
    tmp.replace(OUT)
    print(f"{len(data['courses'])} courses -> {OUT}")
    for c in data["courses"]:
        print(f"  {c['id']:<26} {c['distanceM']:>5}m  {c['title']}  {c['skillCounts']}")


if __name__ == "__main__":
    main()
