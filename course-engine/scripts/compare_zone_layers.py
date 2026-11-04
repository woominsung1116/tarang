"""보호구역·사고다발지역 반영 전후로 조건에 딱 맞는 코스 수와 보호구역 통과를 비교한다. GraphHopper가 떠 있어야 한다.

전: 합친 신호 레이어만(zones 없음). 후: 같은 신호 레이어 + zones(단계별 감점·차단, levels.zone_factors).
"전"에도 보호구역 진입 지점은 센다(zones를 분석에만 쓰고 custom_model에는 넣지 않음).

사용: uv run python scripts/compare_zone_layers.py [--buffer 150]
"""

import argparse
import asyncio
import time
from collections import Counter

from compare_signal_layers import COUNT, LEVELS
from compare_signal_layers import ORIGINS as SIGNAL_ORIGINS

from taraang_course import engine
from taraang_course.analysis import SCHOOL_ZONE
from taraang_course.graphhopper import GraphHopperClient
from taraang_course.levels import LEVELS as SPECS
from taraang_course.signal_layer import MERGED_FILE, load_signal_index
from taraang_course.zones import load_zone_layer

# 시연 출발지 + 부산 8곳: 신호 비교의 8곳(부산대 정문 포함)에 해운대를 더했다
ORIGINS = {**SIGNAL_ORIGINS, "해운대": (35.163, 129.163)}


async def measure(client: GraphHopperClient, signals, zones, apply_zones: bool) -> dict:
    saved = {lv: spec.zone_factors for lv, spec in SPECS.items()}
    if not apply_zones:
        for spec in SPECS.values():
            object.__setattr__(spec, "zone_factors", {})  # 분석은 그대로, custom_model에만 빼기
    out = {}
    try:
        for name, (lat, lon) in ORIGINS.items():
            for level in LEVELS:
                started = time.perf_counter()
                try:
                    body = await engine.generate(client, signals, lat, lon, level, COUNT, zones=zones)
                except engine.NoCourse:
                    out[(name, level)] = None
                    continue
                courses = body["courses"]
                out[(name, level)] = {
                    "strict": sum(1 for c in courses if not c["debug"]["penalty"]),
                    "courses": len(courses),
                    "with_school": sum(1 for c in courses if c["skillCounts"].get(SCHOOL_ZONE)),
                    "school_entries": sum(c["skillCounts"].get(SCHOOL_ZONE, 0) for c in courses),
                    "ms": round((time.perf_counter() - started) * 1000),
                }
    finally:
        for lv, spec in SPECS.items():
            object.__setattr__(spec, "zone_factors", saved[lv])
    return out


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--buffer", type=float, default=150)
    args = ap.parse_args()
    signals = load_signal_index(MERGED_FILE)
    zones = load_zone_layer(buffer_m=args.buffer)
    client = GraphHopperClient("http://localhost:8989", timeout_s=10)
    try:
        before = await measure(client, signals, zones, apply_zones=False)
        after = await measure(client, signals, zones, apply_zones=True)
    finally:
        await client.aclose()
    print(f"{'출발지':<8} 단계  딱맞는코스(전→후)/반환  보호구역 지나는 코스(전→후)  ms(전→후)")
    totals: Counter = Counter()
    for key in before:
        b, a = before[key], after[key]
        if b is None or a is None:
            print(f"{key[0]:<8} {key[1]}    코스 없음 (전 {b is not None}, 후 {a is not None})")
            totals["no_course_before"] += b is None
            totals["no_course_after"] += a is None
            continue
        for k in ("strict", "courses", "with_school", "school_entries"):
            totals[f"{k}_before"] += b[k]
            totals[f"{k}_after"] += a[k]
        totals["cells_with_strict_before"] += b["strict"] > 0
        totals["cells_with_strict_after"] += a["strict"] > 0
        print(
            f"{key[0]:<8} {key[1]}     {b['strict']}→{a['strict']} / {a['courses']}"
            f"               {b['with_school']}→{a['with_school']}"
            f"                     {b['ms']}→{a['ms']}"
        )
    print(dict(totals))


if __name__ == "__main__":
    asyncio.run(main())
