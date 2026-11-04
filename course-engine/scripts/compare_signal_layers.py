"""신호 레이어별로 조건에 딱 맞는(대체 아닌) 코스가 몇 개 나오는지 비교한다. GraphHopper가 떠 있어야 한다.

사용: uv run python scripts/compare_signal_layers.py [--before 이전_레이어.json] [--zones]
  --before  비교 기준 레이어(기본: OSM 신호만). 이전 합친 레이어 파일을 주면 레이어 갱신 효과를 잰다
  --zones   서버처럼 보호구역·사고다발지역을 반영해 잰다
  --roads   도로 속성(금정구 차로 수·제한속도)을 반영해 잰다. --before를 주지 않으면 같은 신호 레이어로 도로 반영 전후를 잰다
"""

import argparse
import asyncio
from collections import Counter
from pathlib import Path

from taraang_course import engine
from taraang_course.graphhopper import GraphHopperClient
from taraang_course.signal_layer import MERGED_FILE, OSM_ONLY_FILE, load_signal_index
from taraang_course.roads import load_road_layer
from taraang_course.zones import load_zone_layer

# 시연 출발지(부산대 정문) + 부산 8곳(서버 지연 측정과 같은 점)
ORIGINS = {
    "부산대 정문": (35.231, 129.084),
    "부산대 북쪽": (35.237, 129.090),
    "온천장": (35.225, 129.080),
    "동래": (35.180, 129.075),
    "서면": (35.158, 129.059),
    "남포": (35.100, 129.030),
    "반여": (35.205, 129.100),
    "노포": (35.250, 129.095),
}
LEVELS = (1, 2, 3)
COUNT = 5


async def measure(client: GraphHopperClient, signals, zones=None, roads=None) -> dict:
    out = {}
    for name, (lat, lon) in ORIGINS.items():
        for level in LEVELS:
            try:
                body = await engine.generate(client, signals, lat, lon, level, COUNT, zones=zones, roads=roads)
            except engine.NoCourse:
                out[(name, level)] = None
                continue
            courses = body["courses"]
            maneuvers = [m for c in courses for m in c["maneuvers"]]
            lefts = [m for m in maneuvers if m["skill"].startswith("LEFT_TURN")]
            out[(name, level)] = {
                "strict": sum(1 for c in courses if not c["debug"]["penalty"]),
                "courses": len(courses),
                "lefts": len(lefts),
                "lefts_verified": sum(1 for m in lefts if m["confidence"] == "VERIFIED"),
            }
    return out


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--before", type=Path, default=OSM_ONLY_FILE)
    ap.add_argument("--zones", action="store_true")
    ap.add_argument("--roads", action="store_true")
    args = ap.parse_args()
    zones = load_zone_layer() if args.zones else None
    client = GraphHopperClient("http://localhost:8989", timeout_s=10)
    try:
        if args.roads:
            # 같은 신호 레이어에서 도로 속성 반영 전후
            merged = load_signal_index(MERGED_FILE)
            before = await measure(client, merged, zones)
            after = await measure(client, merged, zones, load_road_layer())
        else:
            before = await measure(client, load_signal_index(args.before), zones)
            after = await measure(client, load_signal_index(MERGED_FILE), zones)
    finally:
        await client.aclose()
    print(f"{'출발지':<8} 단계  딱맞는코스(전→후)/반환  신호확인 좌회전(전→후)/좌회전")
    totals = Counter()
    for key in before:
        b, a = before[key], after[key]
        if b is None or a is None:
            print(f"{key[0]:<8} {key[1]}    코스 없음")
            continue
        totals["strict_before"] += b["strict"]
        totals["strict_after"] += a["strict"]
        totals["courses"] += a["courses"]
        totals["cells"] += 1
        totals["cells_with_strict_before"] += b["strict"] > 0
        totals["cells_with_strict_after"] += a["strict"] > 0
        totals["lv_before"] += b["lefts_verified"]
        totals["lv_after"] += a["lefts_verified"]
        totals["lefts_before"] += b["lefts"]
        totals["lefts_after"] += a["lefts"]
        print(
            f"{key[0]:<8} {key[1]}     {b['strict']}→{a['strict']} / {a['courses']}"
            f"             {b['lefts_verified']}/{b['lefts']} → {a['lefts_verified']}/{a['lefts']}"
        )
    print(dict(totals))


if __name__ == "__main__":
    asyncio.run(main())
