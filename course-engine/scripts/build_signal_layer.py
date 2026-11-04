"""신호 교차로 레이어(data/signals_busan_merged.json)를 만든다.

입력
- data/south-korea-latest.osm.pbf: 자동차 도로 교차점(연결 선분 3개 이상인 노드)
- data/signals_busan.json: OSM highway=traffic_signals 노드
- data/public/signals/busan_signals.json: 부산 공공 신호제어기(kind별)

사용: uv run --with osmium python scripts/build_signal_layer.py
"""

import json
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path

from taraang_course import signal_layer as sl

ROOT = Path(__file__).resolve().parents[1]
PBF = ROOT / "data" / "south-korea-latest.osm.pbf"
PUBLIC = ROOT / "data" / "public" / "signals" / "busan_signals.json"
BUSAN_BBOX = (128.75, 34.88, 129.32, 35.40)  # min lon, min lat, max lon, max lat (generate_course.py와 같다)
# GraphHopper config의 ignored_highways를 뺀 자동차 도로
CAR_ROADS = {
    "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street",
    "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
}


def junctions(pbf: Path) -> list[tuple[float, float]]:
    """부산 범위 자동차 도로에서 선분 3개 이상이 만나는 노드. 길 두 개가 지나가는 교차(4)와 T자(3) 모두 포함."""
    import osmium

    minlon, minlat, maxlon, maxlat = BUSAN_BBOX
    degree: Counter[int] = Counter()
    where: dict[int, tuple[float, float]] = {}
    fp = osmium.FileProcessor(str(pbf)).with_locations().with_filter(osmium.filter.KeyFilter("highway"))
    for obj in fp:
        if not obj.is_way() or obj.tags.get("highway") not in CAR_ROADS:
            continue
        nodes = [n for n in obj.nodes if n.location.valid()]
        for a, b in zip(nodes, nodes[1:]):
            if a.ref == b.ref:
                continue
            for n in (a, b):
                if minlon <= n.lon <= maxlon and minlat <= n.lat <= maxlat:
                    degree[n.ref] += 1
                    where[n.ref] = (round(n.lat, 7), round(n.lon, 7))
    return [where[ref] for ref, d in degree.items() if d >= 3]


def main() -> None:
    junction_points = junctions(PBF)
    osm = json.loads(sl.OSM_ONLY_FILE.read_text())
    public = json.loads(PUBLIC.read_text())
    snapped, stats = sl.snap_controllers(public, junction_points)
    points = sl.merge(osm, snapped)
    by_source = Counter("+".join(sorted(p["sources"])) for p in points)
    snap_d = sorted(p["snapDistanceM"] for p in snapped)
    layer = {
        "generated": datetime.now(UTC).strftime("%Y-%m-%d"),
        "description": "신호 교차로(차량 신호). OSM traffic_signals + 부산 공공 신호제어기를 가장 가까운 도로 교차점에 붙인 것",
        "attribution": [sl.ATTRIBUTION_BUSAN],
        "method": {
            "public_kinds": sorted(sl.VEHICLE_KINDS),
            "snap_radius_m": sl.SNAP_RADIUS_M,
            "dedupe_radius_m": sl.DEDUPE_RADIUS_M,
            "junction": "자동차 도로 선분 3개 이상이 만나는 OSM 노드",
        },
        "stats": {
            **stats,
            "junctions": len(junction_points),
            "snapped_points": len(snapped),
            "snap_distance_m_median": snap_d[len(snap_d) // 2] if snap_d else None,
            "osm_points": len(osm),
            "merged_points": len(points),
            "by_source": dict(by_source),
            "public_kind_counts": dict(Counter(p["kind"] for p in public)),
        },
        "points": points,
    }
    tmp = sl.MERGED_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(layer, ensure_ascii=False, separators=(",", ":")) + "\n")
    tmp.replace(sl.MERGED_FILE)
    print(json.dumps(layer["stats"], ensure_ascii=False, indent=1))
    print(f"-> {sl.MERGED_FILE} ({sl.MERGED_FILE.stat().st_size // 1024}KB)")


if __name__ == "__main__":
    main()
