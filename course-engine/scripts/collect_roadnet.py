# /// script
# requires-python = ">=3.12"
# dependencies = ["osmium>=4", "shapely>=2", "pyproj>=3.6", "pyshp>=2.3"]
# ///
"""국가 표준노드링크(국토교통부/국가교통정보센터)에서 금정구 링크를 잘라내 차로 수·제한속도·도로등급을 정리한다.

OSM 금정구는 차로 수가 길이 기준 13%, 제한속도가 9%만 채워져 있어 코스 난이도 채점에 쓸 수 없다.
표준노드링크는 국가가 관리하는 링크 단위 도로망이고 링크마다 LANES, MAX_SPD, ROAD_RANK가 들어 있다.

단계
1. (--download) its.go.kr 노드링크 자료실이 브라우저에서 하는 그대로 최신 파일 목록을 조회하고 zip을 받는다.
   로그인은 필요 없다. 이미 받은 zip이 있으면 --zip으로 넘긴다.
2. OSM relation 4064527(금정구) 경계를 pbf에서 뽑아 캐시한다.
3. MOCT_LINK(EPSG:5186)에서 경계와 겹치는 링크만 골라 WGS84 GeoJSON으로 쓰고, 등급별 채움 비율을 낸다.

링크는 방향별이다. 양방향 도로는 방향마다 링크와 노드가 따로 있고, 두 링크는 약 8~15m 떨어져 평행하게
그려져 있다(같은 노드 쌍의 역방향 링크는 전국 0.1% 미만). LANES는 그 방향의 차로 수다.
그래서 같은 도로명·반대 진행 방향의 링크가 OPPOSITE_SEARCH_M 안에 있으면 양방향 도로로 보고 짝(oppositeLinkId)을
기록한다. 짝이 없으면 일방통행 후보다(도로명이 없는 링크는 판별하지 않는다).
길이 통계의 roadKm은 짝이 있는 링크를 반씩 센 도로 연장 근사, linkKm은 방향별 링크 연장 합이다.

사용 (course-engine/ 에서):
  uv run --python 3.12 scripts/collect_roadnet.py --download
  uv run --python 3.12 scripts/collect_roadnet.py --zip "data/public/roadnet/raw/[2026-10-14]NODELINKDATA.zip"
"""

import argparse
import http.cookiejar
import json
import re
import sys
import urllib.request
import zipfile
from collections import defaultdict
from pathlib import Path

import shapefile
from pyproj import Transformer
import numpy as np
import shapely
from shapely import STRtree
from shapely.geometry import LineString, mapping, shape

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "data" / "public" / "roadnet"
PBF = ROOT / "data" / "south-korea-latest.osm.pbf"
REL_ID = 4064527  # 부산광역시 금정구
ITS = "https://www.its.go.kr"

# 지능형교통체계 표준 노드·링크 구축기준(국토교통부고시 제2023-22호) 링크 속성 코드표
ROAD_RANK = {
    "101": "고속국도", "102": "도시고속국도", "103": "일반국도", "104": "특별·광역시도",
    "105": "국가지원지방도", "106": "지방도", "107": "시·군도", "108": "기타",
}
ROAD_TYPE = {"000": "일반도로", "001": "고가차도", "002": "지하차도", "003": "교량", "004": "터널"}

TO_WGS84 = Transformer.from_crs(5186, 4326, always_xy=True)
TO_5186 = Transformer.from_crs(4326, 5186, always_xy=True)
OPPOSITE_SEARCH_M = 40  # 반대 방향 링크를 찾는 반경(실측 짝 간격 중앙값 12m, 95%ile 24m)


def reproject(geom, tf: Transformer):
    return shapely.transform(geom, lambda xy: np.column_stack(tf.transform(xy[:, 0], xy[:, 1])))


def bearing(line) -> float:
    (x0, y0), (x1, y1) = line.coords[0], line.coords[-1]
    return float(np.degrees(np.arctan2(x1 - x0, y1 - y0)) % 360)


def opposite_links(links) -> list[int | None]:
    """같은 도로명이고 진행 방향이 150° 이상 다른 링크 중 가장 가까운 것의 인덱스. 없으면 None."""
    lines = [l for _, l in links]
    tree = STRtree(lines)
    out = []
    for i, (rec, line) in enumerate(links):
        name, b = rec["ROAD_NAME"].strip(), bearing(line)
        best, best_d = None, OPPOSITE_SEARCH_M + 1
        if name:
            for j in tree.query(line, predicate="dwithin", distance=OPPOSITE_SEARCH_M):
                if j == i or links[j][0]["ROAD_NAME"].strip() != name:
                    continue
                d = abs(b - bearing(lines[j])) % 360
                dist = line.centroid.distance(lines[j])
                if min(d, 360 - d) >= 150 and dist < best_d:
                    best, best_d = j, dist
        out.append(best)
    return out


def download(dest_dir: Path) -> Path:
    """노드링크 자료실 페이지와 같은 순서로 요청한다: 페이지(CSRF 토큰·세션) → 목록 POST → 최신 zip GET."""
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    opener.addheaders = [("User-Agent", "Mozilla/5.0"), ("Referer", f"{ITS}/nodelink/nodelinkRef")]
    html = opener.open(f"{ITS}/nodelink/nodelinkRef", timeout=60).read().decode("utf-8", "replace")
    token = re.search(r'name="_csrf" content="([^"]+)"', html).group(1)
    header = re.search(r'name="_csrf_header" content="([^"]+)"', html).group(1)

    from datetime import date, timedelta
    end = date.today()
    body = {"body": {"data": {"searchType": -1, "workingDirectory": "/nodeLink",
                              "startDate": (end - timedelta(days=364)).isoformat(), "endDate": end.isoformat()}}}
    req = urllib.request.Request(f"{ITS}/opendata/getNodeLinkDataFileList", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json", header: token}, method="POST")
    listing = json.loads(opener.open(req, timeout=60).read())
    files = listing["body"]["data"]["fileVOList"]
    if not files:
        raise SystemExit(f"파일 목록이 비었다: {listing.get('header')}")
    latest = max(files, key=lambda f: f["regDt"])
    dest_dir.mkdir(parents=True, exist_ok=True)
    (dest_dir / f"its_filelist_{end.isoformat()}.json").write_text(json.dumps(listing, ensure_ascii=False, indent=1))
    name = f"{latest['nodeOrginlFileNm']}.{latest['nodeFileExtsnNm']}"
    target = dest_dir / name
    if not target.exists():
        url = f"{ITS}/opendata/nodelinkFileSDownload/{latest['atchFileId']}/{latest['nodeFileSeq']}"
        print(f"download {url} → {target} ({latest['nodeFileMg']} MB)", file=sys.stderr)
        with opener.open(url, timeout=1800) as r, open(target, "wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
    return target


def extract(zip_path: Path, dest: Path) -> Path:
    dest.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as z:
        for n in z.namelist():
            if n.startswith("MOCT_LINK.") and not (dest / n).exists():
                z.extract(n, dest)
    return dest / "MOCT_LINK"


def boundary_wgs84(pbf: Path, rel_id: int, cache: Path):
    if cache.exists():
        return shape(json.loads(cache.read_text())["features"][0]["geometry"])
    import osmium
    from shapely import wkb
    wkbf = osmium.geom.WKBFactory()
    fp = osmium.FileProcessor(str(pbf)).with_areas(osmium.filter.KeyFilter("boundary"))
    for obj in fp:
        if isinstance(obj, osmium.osm.Area) and obj.from_way() is False and obj.orig_id() == rel_id:
            geom = wkb.loads(wkbf.create_multipolygon(obj), hex=True)
            cache.write_text(json.dumps({"type": "FeatureCollection", "features": [{
                "type": "Feature", "properties": {"osmRelation": rel_id, "source": pbf.name},
                "geometry": mapping(geom)}]}))
            return geom
    raise SystemExit(f"relation {rel_id} boundary not found in {pbf}")


def read_links(shp_base: Path, area_5186):
    r = shapefile.Reader(str(shp_base), encoding="cp949")
    minx, miny, maxx, maxy = area_5186.bounds
    names = [f[0] for f in r.fields[1:]]
    links = []
    for sr in r.iterShapeRecords():
        bx = sr.shape.bbox
        if bx[2] < minx or bx[0] > maxx or bx[3] < miny or bx[1] > maxy:
            continue
        line = LineString(sr.shape.points)
        if not line.intersects(area_5186):
            continue
        links.append((dict(zip(names, sr.record)), line))
    return links


def normalize(rec: dict, line, area_5186, opposite: dict | None) -> dict:
    lanes = int(rec["LANES"] or 0)
    spd = int(rec["MAX_SPD"] or 0)
    return {
        "linkId": rec["LINK_ID"],
        "fromNode": rec["F_NODE"],
        "toNode": rec["T_NODE"],
        "roadName": (rec["ROAD_NAME"] or "").strip() or None,
        "roadNo": (rec["ROAD_NO"] or "").strip() or None,
        "roadRank": rec["ROAD_RANK"],
        "roadRankName": ROAD_RANK.get(rec["ROAD_RANK"], "미정의"),
        "roadType": ROAD_TYPE.get(rec["ROAD_TYPE"], rec["ROAD_TYPE"]),
        "lanes": lanes or None,  # 이 링크 진행 방향의 차로 수
        "lanesTotal": (lanes + int(opposite["LANES"] or 0)) if opposite else (lanes or None),
        "maxSpeedKmh": spd or None,
        # 반대 방향 짝이 있으면 양방향. 짝이 없으면 일방통행 후보, 도로명이 없으면 판별 안 함
        "oneway": False if opposite else (True if (rec["ROAD_NAME"] or "").strip() else None),
        "oppositeLinkId": opposite["LINK_ID"] if opposite else None,
        "isRamp": rec["CONNECT"] == "1",
        "inUse": rec["ROAD_USE"] == "0",
        "lengthM": round(float(rec["LENGTH"] or line.length), 1),
        "insideLengthM": round(line.intersection(area_5186).length, 1),
        "updated": rec["UPDATEDATE"],
    }


def coverage(features) -> dict:
    """등급별 링크 길이와 채움 비율. 경계 안쪽 길이만 센다. roadKm은 양방향 짝을 반씩 센 도로 연장 근사."""
    by = defaultdict(lambda: defaultdict(float))
    for f in features:
        p = f["properties"]
        km = p["insideLengthM"] / 1000
        for key in (p["roadRankName"], "전체"):
            b = by[key]
            b["linkKm"] += km
            b["links"] += 1
            if p["lanes"]:
                b["lanesKm"] += km
            if p["maxSpeedKmh"]:
                b["speedKm"] += km
            b["roadKm"] += km / 2 if p["oppositeLinkId"] else km
    out = {}
    for k, b in by.items():
        out[k] = {"roadKm": round(b["roadKm"], 1), "linkKm": round(b["linkKm"], 1), "links": int(b["links"]),
                  "lanesPct": round(100 * b["lanesKm"] / b["linkKm"], 1) if b["linkKm"] else 0,
                  "maxSpeedPct": round(100 * b["speedKm"] / b["linkKm"], 1) if b["linkKm"] else 0}
    return out


def distributions(features) -> dict:
    lanes = defaultdict(lambda: defaultdict(float))
    speed = defaultdict(lambda: defaultdict(float))
    for f in features:
        p = f["properties"]
        km = p["insideLengthM"] / 1000
        lanes[p["roadRankName"]][str(p["lanes"])] += km
        speed[p["roadRankName"]][str(p["maxSpeedKmh"])] += km
    rnd = lambda d: {r: {k: round(v, 1) for k, v in sorted(m.items())} for r, m in d.items()}
    return {"lanesKmByRank": rnd(lanes), "maxSpeedKmByRank": rnd(speed)}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--download", action="store_true", help="its.go.kr에서 최신 zip을 받는다")
    ap.add_argument("--zip", type=Path, help="이미 받은 [YYYY-MM-DD]NODELINKDATA.zip")
    ap.add_argument("--pbf", type=Path, default=PBF)
    ap.add_argument("--relation", type=int, default=REL_ID)
    args = ap.parse_args()

    raw = OUT / "raw"
    zip_path = download(raw) if args.download else args.zip
    if not zip_path or not zip_path.exists():
        raise SystemExit("--download 또는 존재하는 --zip 이 필요하다")
    shp = extract(zip_path, raw / "extracted")

    area = boundary_wgs84(args.pbf, args.relation, OUT / "geumjeong_boundary.geojson")
    area_5186 = reproject(area, TO_5186)
    links = read_links(shp, area_5186)

    features = []
    for (rec, line), j in zip(links, opposite_links(links)):
        props = normalize(rec, line, area_5186, links[j][0] if j is not None else None)
        geom = reproject(line, TO_WGS84)
        features.append({"type": "Feature", "properties": props,
                         "geometry": {"type": "LineString",
                                      "coordinates": [[round(x, 7), round(y, 7)] for x, y in geom.coords]}})
    features.sort(key=lambda f: f["properties"]["linkId"])
    (OUT / "geumjeong_links.geojson").write_text(json.dumps(
        {"type": "FeatureCollection",
         "source": {"dataset": "전국 표준노드링크", "file": zip_path.name, "relation": args.relation},
         "features": features}, ensure_ascii=False))

    stats = {"source": zip_path.name, "relation": args.relation, "coverage": coverage(features),
             **distributions(features)}
    (OUT / "coverage_geumjeong.json").write_text(json.dumps(stats, ensure_ascii=False, indent=1))
    print(f"{len(features)} links → {OUT / 'geumjeong_links.geojson'}")
    print(f"{'rank':<14}{'road km':>9}{'link km':>9}{'links':>7}{'lanes':>8}{'maxspd':>8}")
    for k, c in sorted(stats["coverage"].items(), key=lambda kv: -kv[1]["roadKm"]):
        print(f"{k:<14}{c['roadKm']:>9}{c['linkKm']:>9}{c['links']:>7}{c['lanesPct']:>7}%{c['maxSpeedPct']:>7}%")


if __name__ == "__main__":
    main()
