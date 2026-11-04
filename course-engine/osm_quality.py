"""시연 지역 OSM 도로 데이터 품질 점검.

코스 난이도 채점에 쓰는 태그(lanes, maxspeed, 신호등)가 도로 등급별로 얼마나 채워져 있는지
길이 기준으로 집계한다. 로컬 .osm.pbf를 읽는다.

사용: uv run --no-project --with osmium --with shapely python osm_quality.py data/south-korea-latest.osm.pbf [relation id]
기본 relation: 4064527 (부산 금정구)
"""

import math
import sys
from collections import defaultdict

import osmium
from shapely import wkb
from shapely.geometry import Point
from shapely.prepared import prep

CAR_ROADS = {
    "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
    "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
}
TAGS = ("lanes", "maxspeed", "oneway", "name")


def boundary(pbf: str, rel_id: int):
    wkbf = osmium.geom.WKBFactory()
    fp = osmium.FileProcessor(pbf).with_areas(osmium.filter.KeyFilter("boundary"))
    for obj in fp:
        if isinstance(obj, osmium.osm.Area) and obj.from_way() is False and obj.orig_id() == rel_id:
            return wkb.loads(wkbf.create_multipolygon(obj), hex=True)
    raise SystemExit(f"relation {rel_id} boundary not found")


def length_m(nodes) -> float:
    total = 0.0
    pts = [(n.lat, n.lon) for n in nodes if n.location.valid()]
    for (la1, lo1), (la2, lo2) in zip(pts, pts[1:]):
        la1, lo1, la2, lo2 = map(math.radians, (la1, lo1, la2, lo2))
        h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
        total += 2 * 6371000 * math.asin(math.sqrt(h))
    return total


def main() -> None:
    pbf = sys.argv[1]
    rel_id = int(sys.argv[2]) if len(sys.argv) > 2 else 4064527
    area = boundary(pbf, rel_id)
    inside = prep(area)
    minx, miny, maxx, maxy = area.bounds

    stats = defaultdict(lambda: defaultdict(float))
    signals = roundabouts = 0
    fp = osmium.FileProcessor(pbf).with_locations().with_filter(osmium.filter.KeyFilter("highway"))
    for obj in fp:
        if obj.is_node():
            if obj.tags.get("highway") == "traffic_signals" and inside.contains(Point(obj.lon, obj.lat)):
                signals += 1
            continue
        if not obj.is_way() or obj.tags.get("highway") not in CAR_ROADS:
            continue
        mid = obj.nodes[len(obj.nodes) // 2]
        if not mid.location.valid() or not (minx <= mid.lon <= maxx and miny <= mid.lat <= maxy):
            continue
        if not inside.contains(Point(mid.lon, mid.lat)):
            continue
        km = length_m(obj.nodes) / 1000
        s = stats[obj.tags["highway"]]
        s["km"] += km
        s["ways"] += 1
        for key in TAGS:
            if key in obj.tags:
                s[key] += km
        if obj.tags.get("junction") == "roundabout":
            roundabouts += 1

    print(f"relation {rel_id} — 자동차 도로 태그 채움 비율(길이 기준)")
    print(f"{'class':<16}{'km':>8}{'ways':>7}" + "".join(f"{k:>9}" for k in TAGS))
    total = defaultdict(float)
    for s in stats.values():
        for k, v in s.items():
            total[k] += v
    rows = sorted(stats.items(), key=lambda kv: -kv[1]["km"]) + [("TOTAL", total)]
    for cls, s in rows:
        cells = "".join(f"{100 * s[k] / s['km']:>8.0f}%" for k in TAGS)
        print(f"{cls:<16}{s['km']:>8.1f}{int(s['ways']):>7}{cells}")
    print(f"신호등 노드(highway=traffic_signals): {signals}")
    print(f"회전교차로 way(junction=roundabout): {roundabouts}")


if __name__ == "__main__":
    main()
