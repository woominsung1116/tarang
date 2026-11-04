# /// script
# requires-python = ">=3.12"
# dependencies = ["osmium>=4", "shapely>=2", "pyproj>=3.6"]
# ///
"""어린이·노인 보호구역, 교통사고 다발지역 공공데이터를 내려받아 부산/금정구 GeoJSON과
GraphHopper custom model `areas`로 만든다.

출처: 공공데이터포털 표준데이터 3종(로그인·서비스키 불필요, 이용허락범위 "제한 없음").
포털 상세 페이지의 "파일 다운로드" 버튼이 부르는 공개 엔드포인트
(/download/columList.json, /download/standard.json)를 그대로 쓴다.

출력: data/public/zones/ (PROVENANCE.md 참고)
사용: uv run --python 3.12 scripts/collect_zones.py [download|boundary|build|all]
  download  원문을 raw/<slug>/<YYYYMMDD>/에 저장. 같은 날짜 폴더가 있으면 다시 받지 않는다(원문 불변).
  boundary  OSM(.osm.pbf 또는 .osm)에서 금정구(relation 4064527) 경계를 뽑아 캐시
  build     최신 원문 → 정규화 GeoJSON + graphhopper_areas_geumjeong.json
환경변수: ZONES_OUT(출력 폴더), ZONES_OSM(경계를 읽을 OSM 파일), ZONES_BUFFER_M(시설 좌표 원 반경, 기본 300)
"""

import gzip
import hashlib
import json
import os
import re
import sys
import urllib.parse
import urllib.request
from datetime import date, datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = Path(os.environ.get("ZONES_OUT", ROOT / "data" / "public" / "zones"))
RAW = OUT / "raw"
OSM_FILE = Path(os.environ.get("ZONES_OSM", ROOT / "data" / "south-korea-latest.osm.pbf"))

PORTAL = "https://www.data.go.kr"
UA = {"User-Agent": "taraang-course-engine/0.1 (public data collection)", "Accept": "application/json"}

DATASETS = {
    "child_zones": {"pk": "15012891", "name": "전국어린이보호구역표준데이터"},
    "accident_hotspots": {"pk": "15029185", "name": "전국교통사고다발지역표준데이터"},
    "elderly_zones": {"pk": "15034532", "name": "전국노인장애인보호구역표준데이터"},
}

GEUMJEONG_REL = 4064527
BUSAN_NAME = "부산광역시"
BUSAN_BBOX = (128.70, 34.85, 129.40, 35.45)  # lon/lat — 주소상 부산인데 좌표가 밖이면 버린다
# 시행규칙상 보호구역은 시설 주 출입문 중심 반경 300m 이내 도로(필요 시 500m).
# 표준데이터는 시설 좌표만 주므로 300m 원을 보호구역 범위의 근사로 쓴다.
ZONE_BUFFER_M = int(os.environ.get("ZONES_BUFFER_M", 300))
CHILD_DEFAULT_KMH = 30  # 도로교통법 제12조: 30km/h 이내로 제한 가능. 표준데이터에 속도 필드 없음
GH_SIMPLIFY_M = 15
# 다발지역은 2012년부터 누적. 오래된 해는 도로·신호가 바뀌었을 수 있어 GraphHopper 회피 영역에는 최근 5년만 쓴다
HOTSPOT_MIN_YEAR = 2020
GH_MAX_BYTES = 200_000


# ---------- download ----------

def _get(url: str) -> bytes:
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=180) as r:
        return r.read()


def download(today: str) -> None:
    for slug, ds in DATASETS.items():
        d = RAW / slug / today
        if (d / "manifest.json").exists():
            print(f"{slug}: {d} 이미 있음 — 원문 불변, 건너뜀")
            continue
        d.mkdir(parents=True, exist_ok=True)
        col_raw = _get(f"{PORTAL}/download/columList.json?pk={ds['pk']}&ext=JSON")
        (d / "columns.json").write_bytes(col_raw)
        header = json.loads(col_raw)
        tv = header["tableVO"]
        total, per = int(header["totalCount"]), 10000
        pages, rows, blobs = [], [], []
        for page in range(1, -(-total // per) + 1):
            q = urllib.parse.urlencode(
                {"colNmList": tv["colNmList"], "totalCount": total, "svcTableNm": tv["svcTableNm"],
                 "perPage": per, "page": page}, doseq=True)
            body = _get(f"{PORTAL}/download/standard.json?publicDataPk={ds['pk']}&{q}")
            name = f"page_{page:03d}.json.gz"
            (d / name).write_bytes(gzip.compress(body, mtime=0))
            rows += json.loads(body)
            blobs.append(body)
            pages.append({"file": name, "sha256": hashlib.sha256(body).hexdigest(), "bytes": len(body)})
        if len(rows) != total:
            raise SystemExit(f"{slug}: 행 수 불일치 {len(rows)} != {total}")
        canonical = json.dumps(sorted(json.dumps(r, sort_keys=True, ensure_ascii=False) for r in rows),
                               ensure_ascii=False).encode()
        manifest = {
            "dataset": ds["name"], "publicDataPk": ds["pk"],
            "page": f"{PORTAL}/data/{ds['pk']}/standard.do",
            "endpoint": f"{PORTAL}/download/standard.json?publicDataPk={ds['pk']}",
            "svcTableNm": tv["svcTableNm"],
            "retrievedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "rows": total, "pages": pages,
            "contentHash": hashlib.sha256(b"".join(blobs)).hexdigest(),  # 원문 바이트
            "canonicalHash": hashlib.sha256(canonical).hexdigest(),  # 행 순서 무관 내용
        }
        (d / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
        print(f"{slug}: {total}행 → {d}")


def load_rows(slug: str) -> tuple[list[dict], dict]:
    dirs = sorted(p for p in (RAW / slug).iterdir() if (p / "manifest.json").exists())
    if not dirs:
        raise SystemExit(f"{slug}: 원문 없음 — 먼저 download")
    manifest = json.loads((dirs[-1] / "manifest.json").read_text())
    rows = []
    for p in manifest["pages"]:
        rows += json.loads(gzip.decompress((dirs[-1] / p["file"]).read_bytes()))
    return rows, manifest


# ---------- boundary ----------

def boundary() -> None:
    import osmium
    from shapely import wkb
    from shapely.geometry import mapping

    wkbf = osmium.geom.WKBFactory()
    fp = osmium.FileProcessor(str(OSM_FILE)).with_areas(osmium.filter.KeyFilter("boundary"))
    for obj in fp:
        if isinstance(obj, osmium.osm.Area) and not obj.from_way() and obj.orig_id() == GEUMJEONG_REL:
            feat = {"type": "Feature", "id": f"osm_relation_{GEUMJEONG_REL}",
                    "properties": {"name": obj.tags.get("name"), "osmRelation": GEUMJEONG_REL,
                                   "source": f"OpenStreetMap contributors (ODbL), {OSM_FILE.name}"},
                    "geometry": mapping(wkb.loads(wkbf.create_multipolygon(obj), hex=True))}
            _write(OUT / "boundary_geumjeong.geojson", {"type": "FeatureCollection", "features": [feat]})
            print(f"boundary_geumjeong: relation {GEUMJEONG_REL} ({feat['properties']['name']})")
            return
    raise SystemExit(f"relation {GEUMJEONG_REL} 경계를 찾지 못함: {OSM_FILE}")


# ---------- build ----------

def _num(v):
    try:
        return float(str(v).replace(",", "").strip())
    except ValueError:
        return None


def _int(v):
    n = _num(v)
    return int(n) if n is not None else None


def _sid(*parts) -> str:
    return hashlib.sha1("|".join(map(str, parts)).encode()).hexdigest()[:12]


def _round(geom, nd=6):
    import numpy as np
    import shapely
    return shapely.transform(geom, lambda c: np.round(c, nd))


def _parse_polygon(text: str):
    """다발지역 폴리곤 필드: '{ type: Polygon, coordinates: [...] }' (키 따옴표 없음, EPSG:5179)."""
    from shapely.geometry import shape
    m = re.search(r"type\s*:\s*\"?(\w+)\"?\s*,\s*coordinates\s*:\s*(\[.*\])", (text or "").strip(), re.S)
    return shape({"type": m.group(1), "coordinates": json.loads(m.group(2))}) if m else None


def _in_busan_text(*texts) -> bool:
    return any(str(t or "").strip().startswith(BUSAN_NAME) for t in texts)


def _in_busan_bbox(lon, lat) -> bool:
    return lon is not None and lat is not None and BUSAN_BBOX[0] <= lon <= BUSAN_BBOX[2] and BUSAN_BBOX[1] <= lat <= BUSAN_BBOX[3]


def build() -> None:
    from pyproj import Transformer
    from shapely.geometry import Point, mapping, shape
    from shapely.ops import unary_union
    from shapely.prepared import prep

    import numpy as np
    import shapely

    def _tf(src, dst):
        t = Transformer.from_crs(src, dst, always_xy=True)
        return lambda g: shapely.transform(g, lambda c: np.column_stack(t.transform(c[:, 0], c[:, 1])))

    fwd, inv = _tf(4326, 5179), _tf(5179, 4326)
    gj = shape(json.loads((OUT / "boundary_geumjeong.geojson").read_text())["features"][0]["geometry"])
    gj_p = prep(gj)
    report = {}

    def zone_feature(kind, r, lat, lon, man, extra):
        poly = inv(fwd(Point(lon, lat)).buffer(ZONE_BUFFER_M, quad_segs=8))
        props = {"kind": kind, "name": r.get("TRGET_FCLTY_NM"), "address": r.get("RDNMADR") or r.get("LNMADR"),
                 "lat": lat, "lon": lon, **extra, "hours": None,
                 "roadWidth": r.get("PRTCAREA_RW") or None, "managedBy": r.get("INSTITUTION_NM"),
                 "police": r.get("CMPTNC_POLCSTTN_NM"), "referenceDate": r.get("REFERENCE_DATE"),
                 "geometrySource": "buffer", "bufferM": ZONE_BUFFER_M,
                 "source": f"{man['dataset']} ({man['page']})"}
        return {"type": "Feature", "id": _sid(kind, r.get("INSTT_CODE"), r.get("TRGET_FCLTY_NM"), lat, lon),
                "properties": props, "geometry": mapping(_round(poly))}

    # --- 어린이보호구역: 시설 좌표 + 300m 원 ---
    rows, man = load_rows("child_zones")
    feats, dropped = [], {"notDesignated": 0, "badCoord": 0}
    for r in rows:
        if not _in_busan_text(r.get("INSTT_NM"), r.get("RDNMADR"), r.get("LNMADR")):
            continue
        if (r.get("CHLD_PRTN_ZON_YN") or "").strip().upper() == "N":
            dropped["notDesignated"] += 1
            continue
        lat, lon = _num(r.get("LATITUDE")), _num(r.get("LONGITUDE"))
        if not _in_busan_bbox(lon, lat):
            dropped["badCoord"] += 1
            continue
        feats.append(zone_feature("child_protection_zone", r, lat, lon, man, {
            "facilityType": r.get("FCLTY_KND"),
            "speedLimitKmh": CHILD_DEFAULT_KMH, "speedLimitSource": "assumed_legal_default",
            "cctvCount": _int(r.get("CCTV_NUMBER"))}))
    report["school_zones"] = _emit("school_zones", feats, gj_p, dropped)

    # --- 노인·장애인보호구역: 시설 좌표 + 300m 원, 제한속도는 원문 값 ---
    rows, man = load_rows("elderly_zones")
    feats, dropped = [], {"badCoord": 0}
    for r in rows:
        if not _in_busan_text(r.get("CTPRVN_NM"), r.get("INSTT_NM"), r.get("RDNMADR"), r.get("LNMADR")):
            continue
        lat, lon = _num(r.get("LATITUDE")), _num(r.get("LONGITUDE"))
        if not _in_busan_bbox(lon, lat):
            dropped["badCoord"] += 1
            continue
        kmh = _int(r.get("LMTT_VE"))
        feats.append(zone_feature("elderly_disabled_protection_zone", r, lat, lon, man, {
            "facilityType": r.get("LC_TYPE"),
            "speedLimitKmh": kmh, "speedLimitSource": "dataset" if kmh else None}))
    report["elderly_zones"] = _emit("elderly_zones", feats, gj_p, dropped)

    # --- 교통사고 다발지역: 원문 폴리곤(EPSG:5179), 없으면 점 + 300m 원 ---
    rows, man = load_rows("accident_hotspots")
    feats, dropped = [], {"bufferFallback": 0, "pointOutsidePolygon": 0, "badCoord": 0}
    for r in rows:
        if not _in_busan_text(r.get("CTPRVN_SIGNGU_NM"), r.get("ACDNT_AREA_LC_NM")):
            continue
        lat, lon = _num(r.get("LATITUDE")), _num(r.get("LONGITUDE"))
        if not _in_busan_bbox(lon, lat):
            dropped["badCoord"] += 1
            continue
        pt = fwd(Point(lon, lat))
        poly, src = _parse_polygon(r.get("ACDNT_MLTTD_PYN_INFO")), "dataset_polygon"
        if poly is None or poly.is_empty:
            poly, src = pt.buffer(ZONE_BUFFER_M, quad_segs=8), "buffer"
            dropped["bufferFallback"] += 1
        elif poly.distance(pt) > 50:
            dropped["pointOutsidePolygon"] += 1  # 좌표계 가정(EPSG:5179) 점검용
        feats.append({"type": "Feature",
                      "id": f"{r.get('ACDNT_AREA_MANAGE_NO')}_{_sid(r.get('ACDNT_YEAR'), r.get('ACDNT_TYPE_SE'), r.get('LC_CODE'))}",
                      "properties": {
                          "kind": "accident_hotspot", "manageNo": r.get("ACDNT_AREA_MANAGE_NO"),
                          "year": _int(r.get("ACDNT_YEAR")), "type": r.get("ACDNT_TYPE_SE"),
                          "district": r.get("CTPRVN_SIGNGU_NM"), "location": r.get("ACDNT_AREA_LC_NM"),
                          "accidentCount": _int(r.get("OCCRRNC_CO")), "casualties": _int(r.get("CASLT_CO")),
                          "deaths": _int(r.get("DEATH_CO")), "seriousInjuries": _int(r.get("SWPSN_CO")),
                          "minorInjuries": _int(r.get("SINJPSN_CO")), "reportedInjuries": _int(r.get("INJPSN_CO")),
                          "lat": lat, "lon": lon, "referenceDate": r.get("REFERENCE_DATE"),
                          "geometrySource": src, "areaM2": round(poly.area),
                          "source": f"{man['dataset']} ({man['page']})"},
                      "geometry": mapping(_round(inv(poly)))})
    report["accident_hotspots"] = _emit("accident_hotspots", feats, gj_p, dropped)

    # --- GraphHopper custom model areas: 종류별로 합쳐 id 하나씩 ---
    areas = []
    for area_id in ("school_zones", "accident_hotspots", "elderly_zones"):
        fs = json.loads((OUT / f"geumjeong_{area_id}.geojson").read_text())["features"]
        if area_id == "accident_hotspots":
            fs = [f for f in fs if (f["properties"]["year"] or 0) >= HOTSPOT_MIN_YEAR]
        if not fs:
            continue
        merged = unary_union([fwd(shape(f["geometry"])) for f in fs])
        merged = merged.simplify(GH_SIMPLIFY_M, preserve_topology=True)
        areas.append({"type": "Feature", "id": area_id,
                      "properties": {"sourceFeatures": len(fs), "simplifyM": GH_SIMPLIFY_M},
                      "geometry": mapping(_round(inv(merged), 5))})
    assert all(re.fullmatch(r"[a-zA-Z0-9_]+", a["id"]) for a in areas)
    gh_path = OUT / "graphhopper_areas_geumjeong.json"
    _write(gh_path, {"type": "FeatureCollection", "features": areas}, compact=True)
    size = gh_path.stat().st_size
    report["graphhopper_areas"] = {"bytes": size, "ids": [a["id"] for a in areas]}
    if size > GH_MAX_BYTES:
        raise SystemExit(f"graphhopper areas {size}B > {GH_MAX_BYTES}B — GH_SIMPLIFY_M를 올릴 것")

    report["builtAt"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
    _write(OUT / "build_report.json", report)
    print(json.dumps(report, ensure_ascii=False, indent=1))


def _emit(name: str, feats: list[dict], gj_p, dropped: dict) -> dict:
    from shapely.geometry import shape
    # 원문에 같은 시설(기관코드·이름·좌표 동일)이 두 번 실린 행이 있다 → 하나만 남긴다
    uniq = {}
    for f in feats:
        uniq.setdefault(f["id"], f)
    dropped["duplicateRow"] = len(feats) - len(uniq)
    feats = sorted(uniq.values(), key=lambda f: f["id"])
    _write(OUT / f"busan_{name}.geojson", {"type": "FeatureCollection", "features": feats})
    # 금정구: 경계와 겹치는 피처를 자르지 않고 통째로 둔다(회피 영역이 경계에서 잘리지 않게)
    gj = [f for f in feats if gj_p.intersects(shape(f["geometry"]))]
    _write(OUT / f"geumjeong_{name}.geojson", {"type": "FeatureCollection", "features": gj})
    return {"busan": len(feats), "geumjeong": len(gj), "dropped": dropped}


def _write(path: Path, obj: dict, compact: bool = False) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if compact:
        path.write_text(json.dumps(obj, ensure_ascii=False, separators=(",", ":")))
    else:
        path.write_text(json.dumps(obj, ensure_ascii=False, indent=1))


def main() -> None:
    cmd = sys.argv[1] if len(sys.argv) > 1 else "all"
    if cmd in ("download", "all"):
        download(date.today().strftime("%Y%m%d"))
    if cmd == "boundary" or (cmd == "all" and not (OUT / "boundary_geumjeong.geojson").exists()):
        boundary()
    if cmd in ("build", "all"):
        build()


if __name__ == "__main__":
    main()
