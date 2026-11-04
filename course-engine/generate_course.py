"""연습 코스 생성 CLI. 엔진은 taraang_course 패키지에 있다.

사용:
  uv run --no-project --with osmium python generate_course.py signals data/south-korea-latest.osm.pbf
  uv run python generate_course.py course 35.2310 129.0840 --level 1
"""

import argparse
import asyncio
import json
from pathlib import Path

SIGNALS_FILE = Path(__file__).parent / "data" / "signals_busan.json"
BUSAN_BBOX = (128.75, 34.88, 129.32, 35.40)  # min lon, min lat, max lon, max lat


def extract_signals(pbf: str) -> None:
    import osmium

    minlon, minlat, maxlon, maxlat = BUSAN_BBOX
    pts = []
    for n in osmium.FileProcessor(pbf, osmium.osm.NODE).with_filter(osmium.filter.TagFilter(("highway", "traffic_signals"))):
        if minlon <= n.lon <= maxlon and minlat <= n.lat <= maxlat:
            pts.append([n.lat, n.lon])
    SIGNALS_FILE.write_text(json.dumps(pts))
    print(f"{len(pts)} signals -> {SIGNALS_FILE}")


async def course(lat: float, lon: float, level: int, count: int, seeds: int, gh: str) -> None:
    from taraang_course import engine
    from taraang_course.graphhopper import GraphHopperClient
    from taraang_course.signal_layer import default_signals_file, load_signal_index
    from taraang_course.roads import load_road_layer
    from taraang_course.zones import load_zone_layer

    signals = load_signal_index(default_signals_file())
    client = GraphHopperClient(gh)
    try:
        result = await engine.generate(client, signals, lat, lon, level, count, seeds=seeds, zones=load_zone_layer(), roads=load_road_layer())
    finally:
        await client.aclose()
    for note in result["dataNotes"]:
        print(f"* {note}")
    for c in result["courses"]:
        print(f"{c['id']}  {c['distanceM']}m  {c['title']}  {c['skillCounts']}")
        for w in c["warnings"]:
            print(f"    ! {w}")
        for m in c["maneuvers"]:
            print(f"    {m['index']:>2}. {m['distanceFromStartM']:>7.0f}m {m['skill']:<21} {m['confidence']:<8} {m['street']}")


def main() -> None:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("signals")
    s.add_argument("pbf")
    c = sub.add_parser("course")
    c.add_argument("lat", type=float)
    c.add_argument("lon", type=float)
    c.add_argument("--level", type=int, default=1, choices=[1, 2, 3, 4])
    c.add_argument("--count", type=int, default=3)
    c.add_argument("--seeds", type=int, default=20)
    c.add_argument("--gh", default="http://localhost:8989")
    args = ap.parse_args()
    if args.cmd == "signals":
        extract_signals(args.pbf)
    else:
        asyncio.run(course(args.lat, args.lon, args.level, args.count, args.seeds, args.gh))


if __name__ == "__main__":
    main()
