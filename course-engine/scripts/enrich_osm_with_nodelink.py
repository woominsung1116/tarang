# /// script
# requires-python = ">=3.12"
# dependencies = ["osmium>=4", "shapely>=2", "pyproj>=3.6", "numpy"]
# ///
"""OSM 자동차 도로 way마다 겹치는 표준노드링크 링크를 찾아 매칭 근거를 남긴다.

입력: collect_roadnet.py가 만든 geumjeong_links.geojson, geumjeong_boundary.geojson과 OSM pbf.
대상 way: osm_quality.py와 같은 기준(CAR_ROADS, way 중간 노드가 금정구 경계 안). 매칭이 안 된 way도 OSM 태그와 함께 남긴다.
어떤 값을 쓸지(OSM 우선, 기본값)는 build_way_attributes.py가 정한다. 이 스크립트는 근거만 만든다.

매칭
- way를 SAMPLE_M 간격으로 찍고, 각 점에서 MAX_DIST_M 안의 링크 중 진행 방향이 맞는 가장 가까운 링크를 고른다.
  - oneway way(oneway=yes/-1, 회전교차로, motorway): 링크 진행 방향이 통행 방향과 ±MAX_ANGLE 이내여야 한다.
  - 양방향 way: 같은 방향(fwd)·반대 방향(bwd) 링크를 모두 받는다. 노드링크는 방향마다 링크가 따로 있고
    중심선 양옆 약 8~15m에 평행하게 그려져 있다.
- 방향마다 가장 많은 점을 차지한 링크를 대표 링크로 쓴다. matchScore = 어느 방향이든 링크가 잡힌 점 비율(길이 비율 근사).
- matchScore >= MIN_SCORE 인 way만 match를 채운다.
- lanes (OSM 관례에 맞춘다: oneway는 통행 방향 차로 수, 양방향은 양쪽 합계)
  - oneway way: 대표 링크의 LANES.
  - 양방향 way: fwd·bwd 대표 링크가 모두 있으면 두 LANES의 합(lanesBasis=both_directions).
    한쪽만 잡혔으면 그 링크의 반대 방향 짝(oppositeLinkId)이 있을 때만 lanesTotal(lanesBasis=paired_link).
    짝도 없으면 합계를 알 수 없으므로 lanes=None(lanesBasis=one_direction_only, 방향 차로 수는 lanesOneDirection).
- maxSpeedKmh·roadRank: 매칭된 점이 가장 많은 링크의 값. fwd·bwd 제한속도가 다르면 speedSplit에 둘 다 남긴다.

사용 (course-engine/ 에서):
  uv run --python 3.12 scripts/enrich_osm_with_nodelink.py
"""

import argparse
import json
import math
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
import osmium
import shapely
from pyproj import Transformer
from shapely import STRtree
from shapely.geometry import LineString, Point, shape
from shapely.prepared import prep

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "data" / "public" / "roadnet"
PBF = ROOT / "data" / "south-korea-latest.osm.pbf"

CAR_ROADS = {
    "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
    "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
}
OSM_TAGS = ("highway", "name", "oneway", "junction", "lanes", "maxspeed")
SAMPLE_M = 10.0
MAX_DIST_M = 15.0
MAX_ANGLE = 30.0
MIN_SCORE = 0.5

TO_5186 = Transformer.from_crs(4326, 5186, always_xy=True)


def reproject(geom):
    return shapely.transform(geom, lambda xy: np.column_stack(TO_5186.transform(xy[:, 0], xy[:, 1])))


def local_bearing(line: LineString, d: float) -> float:
    a = line.interpolate(max(d - 2, 0))
    b = line.interpolate(min(d + 2, line.length))
    return math.degrees(math.atan2(b.x - a.x, b.y - a.y)) % 360


def angle_diff(a: float, b: float) -> float:
    d = abs(a - b) % 360
    return min(d, 360 - d)


def osm_oneway(tags) -> int:
    """1: way 방향, -1: 역방향, 0: 양방향."""
    v = tags.get("oneway")
    if v in ("yes", "1", "true"):
        return 1
    if v == "-1":
        return -1
    if v == "no":
        return 0
    if tags.get("junction") == "roundabout" or tags.get("highway") in ("motorway", "motorway_link"):
        return 1
    return 0


def load_ways(pbf: Path, area):
    inside = prep(area)
    minx, miny, maxx, maxy = area.bounds
    fp = osmium.FileProcessor(str(pbf)).with_locations().with_filter(osmium.filter.KeyFilter("highway"))
    for obj in fp:
        if not obj.is_way() or obj.tags.get("highway") not in CAR_ROADS:
            continue
        mid = obj.nodes[len(obj.nodes) // 2]
        if not mid.location.valid() or not (minx <= mid.lon <= maxx and miny <= mid.lat <= maxy):
            continue
        if not inside.contains(Point(mid.lon, mid.lat)):
            continue
        coords = [(n.lon, n.lat) for n in obj.nodes if n.location.valid()]
        if len(coords) < 2:
            continue
        yield obj.id, {k: obj.tags.get(k) for k in OSM_TAGS if obj.tags.get(k) is not None}, LineString(coords)


def sample_offsets(length: float) -> list[float]:
    """way 위 SAMPLE_M 간격 점(구간 중앙). 짧은 way는 중점 하나."""
    n = int(length // SAMPLE_M)
    if n < 1:
        return [length / 2]
    return [k * SAMPLE_M + SAMPLE_M / 2 for k in range(n)]


def match_way(line, direction, tree, links):
    """점마다 (링크 인덱스, 'fwd'|'bwd', 거리). 잡힌 링크가 없으면 None."""
    hits = []
    for d in sample_offsets(line.length):
        pt = line.interpolate(d)
        b = local_bearing(line, d)
        if direction == -1:
            b = (b + 180) % 360
        best, best_dist = None, MAX_DIST_M
        for j in tree.query(pt, predicate="dwithin", distance=MAX_DIST_M):
            lk = links[j]
            lb = local_bearing(lk, lk.project(pt))
            if angle_diff(b, lb) <= MAX_ANGLE:
                side = "fwd"
            elif not direction and angle_diff(b, (lb + 180) % 360) <= MAX_ANGLE:
                side = "bwd"
            else:
                continue
            dist = lk.distance(pt)
            if dist <= best_dist:
                best, best_dist = (j, side), dist
        hits.append((*best, best_dist) if best else None)
    return hits


def summarize(hits, direction, props):
    got = [h for h in hits if h]
    score = len(got) / len(hits)
    if score < MIN_SCORE:
        return None
    side_votes = {s: Counter(j for j, sd, _ in got if sd == s) for s in ("fwd", "bwd")}
    all_votes = Counter(j for j, _, _ in got)
    main = all_votes.most_common(1)[0][0]
    rep = {s: v.most_common(1)[0][0] for s, v in side_votes.items() if v}
    p = props[main]

    lanes_one_dir = None
    if direction:
        lanes, basis = p["lanes"], "one_way"
    elif "fwd" in rep and "bwd" in rep:
        a, b = props[rep["fwd"]]["lanes"], props[rep["bwd"]]["lanes"]
        lanes, basis = (a + b if a and b else None), "both_directions"
    elif p["oppositeLinkId"]:
        lanes, basis = p["lanesTotal"], "paired_link"
    else:
        lanes, basis, lanes_one_dir = None, "one_direction_only", p["lanes"]

    speeds = {s: props[j]["maxSpeedKmh"] for s, j in rep.items()}
    dists = [dist for j, _, dist in got if j == main]
    return {
        "lanes": lanes,
        "lanesBasis": basis,
        "lanesOneDirection": lanes_one_dir,
        "maxSpeedKmh": p["maxSpeedKmh"],
        "speedSplit": speeds if len(set(speeds.values())) > 1 else None,
        "roadRank": p["roadRank"],
        "roadRankName": p["roadRankName"],
        "linkId": p["linkId"],
        "linkIds": sorted({props[j]["linkId"] for j in all_votes}),
        "linkName": p["roadName"],
        "linkInUse": p["inUse"],
        "matchScore": round(score, 2),
        "meanDistM": round(float(np.mean(dists)), 1),
    }


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--pbf", type=Path, default=PBF)
    ap.add_argument("--links", type=Path, default=OUT / "geumjeong_links.geojson")
    ap.add_argument("--boundary", type=Path, default=OUT / "geumjeong_boundary.geojson")
    ap.add_argument("--out", type=Path, default=OUT / "osm_way_enrichment_geumjeong.json")
    args = ap.parse_args()

    area = shape(json.loads(args.boundary.read_text())["features"][0]["geometry"])
    links_doc = json.loads(args.links.read_text())
    feats = links_doc["features"]
    props = [f["properties"] for f in feats]
    links = [reproject(shape(f["geometry"])) for f in feats]
    tree = STRtree(links)

    ways = {}
    stats = defaultdict(lambda: defaultdict(float))
    for way_id, tags, geom in load_ways(args.pbf, area):
        line = reproject(geom)
        direction = osm_oneway(tags)
        match = summarize(match_way(line, direction, tree, links), direction, props)
        ways[str(way_id)] = {"osm": tags, "oneway": direction, "lengthM": round(line.length, 1), "match": match}
        for key in (tags["highway"], "TOTAL"):
            s = stats[key]
            s["km"] += line.length / 1000
            s["ways"] += 1
            if match:
                s["matchedKm"] += line.length / 1000
                s["matchedWays"] += 1

    pct = lambda a, b: round(100 * a / b, 1) if b else None
    report = {cls: {"km": round(s["km"], 1), "ways": int(s["ways"]), "matchedWays": int(s["matchedWays"]),
                    "matchedLengthPct": pct(s["matchedKm"], s["km"])}
              for cls, s in sorted(stats.items(), key=lambda kv: -kv[1]["km"])}
    args.out.write_text(json.dumps({
        "source": {"nodelink": links_doc.get("source"), "osm": args.pbf.name},
        "params": {"sampleM": SAMPLE_M, "maxDistM": MAX_DIST_M, "maxAngleDeg": MAX_ANGLE, "minScore": MIN_SCORE},
        "report": report, "ways": ways}, ensure_ascii=False, indent=1))

    print(f"{len(ways)} ways, {report['TOTAL']['matchedWays']} matched → {args.out}")
    print(f"{'class':<16}{'km':>7}{'ways':>6}{'matched':>9}{'len%':>7}")
    for cls, r in report.items():
        print(f"{cls:<16}{r['km']:>7}{r['ways']:>6}{r['matchedWays']:>9}{r['matchedLengthPct']:>7}")


if __name__ == "__main__":
    main()
