"""부산 신호등·신호교차로 공공데이터를 내려받아 정규화하고 OSM 신호등과 비교한다.

출력: data/public/signals/ (raw/ 원본 불변 보관, busan_signals.json, geumjeong_signals.json,
      busan_intersection_controllers_latlon.json, geumjeong_boundary.geojson, coverage.json).
      출처와 한계는 같은 폴더 PROVENANCE.md.
사용: uv run --no-project --python 3.12 --with pyshp --with shapely --with osmium \
        python -I scripts/collect_signals.py [download|api|boundary|build|all]

api: 부산광역시_신호등 현황 OpenAPI(data.go.kr 3079345) 전체 페이지를 raw/api_<YYYYMMDD>/ 에 저장한다.
     서비스키는 환경변수 또는 course-engine/.env 의 TARAANG_DATA_GO_KR_KEY(디코딩 형태). 키는 출력·저장하지 않는다.
build: 가장 최근 raw/api_* 가 있으면 같은 관리번호의 2020 SHP 행을 API 행으로 대체하고 busan_signals_api.json도 쓴다.

내려받기는 공공데이터포털 파일데이터의 '다운로드' 버튼과 같은 경로(로그인 불필요)만 쓴다.
포털이 보안문자(다운로드 횟수 제한)를 요구하면 멈추고 사람에게 넘긴다. 우회하지 않는다.
raw/ 에 이미 있는 파일은 다시 받지 않는다(원본 불변). 새 판을 받으려면 manifest.json 항목을 지우고 다시 실행.
"""

import csv
import hashlib
import io
import json
import math
import statistics
import sys
import time
import urllib.parse
import urllib.request
import zipfile
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "data" / "public" / "signals"
RAW = OUT / "raw"
PBF = ROOT / "data" / "south-korea-latest.osm.pbf"
OSM_SIGNALS = ROOT / "data" / "signals_busan.json"

PORTAL = "https://www.data.go.kr"
UA = "taraang-course-engine/0.1 (public data download)"

# (파일명, publicDataPk, publicDataDetailPk) — 각 데이터셋 상세 페이지 다운로드 버튼의 인자
DATASETS = [
    ("busan_traffic_lights_shp", "15084253", "uddi:463f1217-0dfc-47ac-bb77-9a32aae86e24"),
    ("busan_signal_crosswalks_shp", "15084252", "uddi:3ca68cfc-f1d9-43d6-bb86-d1227f45c78b"),
    ("busan_signal_crosswalks_csv", "3072152", "uddi:5dcaa99c-2678-48e6-9602-b1b1c055ba6f_201911131015"),
    ("busan_signal_controllers_csv", "15083380", "uddi:3dae2715-511b-4b2e-80e2-e96add2557ad"),
    ("busan_intersections_csv", "15084050", "uddi:955a0353-005f-495c-8efa-ff20157cc700"),
    ("busan_safety_signs_csv", "15084056", "uddi:888fb8b0-3b3c-45f4-abe7-c9b099e133b5"),
    ("busan_road_arrows_csv", "15084053", "uddi:5c71e882-e22d-41bf-90f6-f483f1cc3a67"),
]

BUSAN_BBOX = (34.85, 128.75, 35.40, 129.35)  # lat_min, lon_min, lat_max, lon_max
GEUMJEONG_REL = 4064527
CONTROLLER_KIND = {"교차로": "intersection", "단일로": "midblock", "가변차로": "reversible_lane"}

API_ENDPOINT = "https://apis.data.go.kr/6260000/BusanTrafficLightInfoService/getWarningLightInfo"
API_KEY_ENV = "TARAANG_DATA_GO_KR_KEY"
API_ROWS = 100


def _post(path: str, data: dict) -> bytes:
    req = urllib.request.Request(
        PORTAL + path,
        data=urllib.parse.urlencode(data).encode(),
        headers={"User-Agent": UA, "Content-Type": "application/x-www-form-urlencoded"},
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def _get(url: str) -> tuple[bytes, str]:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=300) as r:
        return r.read(), r.headers.get("Content-Disposition", "")


def download() -> None:
    RAW.mkdir(parents=True, exist_ok=True)
    manifest_path = RAW / "manifest.json"
    manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else {}
    for name, pk, detail_pk in DATASETS:
        if name in manifest and (RAW / manifest[name]["file"]).exists():
            print(f"skip {name} (already downloaded)")
            continue
        meta = json.loads(_post("/tcs/dss/selectFileDataDownload.do", {
            "publicDataDetailPk": detail_pk, "publicDataPk": pk, "atchFileId": "",
            "fileDetailSn": "1", "publicDataTyCode": "PR0051",
        }))
        if not meta.get("status"):
            print(f"FAIL {name}: {meta.get('error')}")
            continue
        atch, sn = meta["atchFileId"], meta["fileDetailSn"]
        info = meta["dataSetFileDetailInfo"]
        limit = json.loads(_post("/cmm/cmm/check-limit.json", {"atchFileId": atch, "fileDetailSn": sn}))
        if limit.get("needCaptcha"):
            sys.exit(f"STOP: 포털이 보안문자를 요구함({name}). 브라우저에서 {PORTAL}/data/{pk}/fileData.do 직접 다운로드")
        body, disp = _get(f"{PORTAL}/cmm/cmm/fileDownload.do?atchFileId={atch}&fileDetailSn={sn}")
        ext = Path(urllib.parse.unquote(disp.split("filename=")[-1].strip('"; '))).suffix or ".bin"
        fname = f"{name}{ext.lower()}"
        (RAW / fname).write_bytes(body)
        manifest[name] = {
            "file": fname,
            "publicDataPk": pk,
            "page": f"{PORTAL}/data/{pk}/fileData.do",
            "title": info.get("dataNm") or info.get("publicDataSj"),
            "content_disposition": urllib.parse.unquote(disp),
            "sha256": hashlib.sha256(body).hexdigest(),
            "bytes": len(body),
            "retrieved": time.strftime("%Y-%m-%d"),
        }
        print(f"ok {name} -> {fname} ({len(body):,} B)")
        time.sleep(1.0)
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2))


def _service_key() -> str:
    """공공데이터포털 서비스키(디코딩 형태). 환경변수 > course-engine/.env 순. 키는 출력·저장하지 않는다."""
    import os

    key = os.environ.get(API_KEY_ENV, "")
    env = ROOT / ".env"
    if not key and env.exists():
        for line in env.read_text().splitlines():
            if line.startswith(API_KEY_ENV + "="):
                key = line.split("=", 1)[1].strip().strip("'\"")
    if not key:
        sys.exit(f"{API_KEY_ENV} 없음(.env 또는 환경변수)")
    return key


def _api_page(key: str, page: int) -> bytes:
    """한 페이지 원문. 5xx·타임아웃은 지수 백오프로 최대 3번. 오류 메시지에 URL(키 포함)을 넣지 않는다."""
    import urllib.error

    q = urllib.parse.urlencode({"serviceKey": key, "pageNo": page, "numOfRows": API_ROWS, "resultType": "json"})
    req = urllib.request.Request(f"{API_ENDPOINT}?{q}", headers={"User-Agent": UA})
    err = ""
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            err = f"HTTP {e.code}"
            if e.code < 500:
                break
        except (urllib.error.URLError, TimeoutError) as e:
            err = type(getattr(e, "reason", e)).__name__
        time.sleep(2 ** (attempt + 1))
    sys.exit(f"API page {page} 실패: {err}")


def api() -> None:
    """부산광역시_신호등 현황 OpenAPI 전체 페이지를 raw/api_<YYYYMMDD>/ 에 원문 그대로 저장한다.

    같은 날짜 폴더에 manifest.json이 있으면 다시 받지 않는다(원본 불변).
    """
    day = time.strftime("%Y%m%d")
    out = RAW / f"api_{day}"
    if (out / "manifest.json").exists():
        print(f"skip api ({out.name} already downloaded)")
        return
    key = _service_key()
    secrets = {key.encode(), urllib.parse.quote(key, safe="").encode()}
    out.mkdir(parents=True, exist_ok=True)
    pages, total, page = [], None, 1
    while total is None or (page - 1) * API_ROWS < total:
        body = _api_page(key, page)
        if any(s in body for s in secrets):
            sys.exit(f"API page {page}: 응답에 서비스키가 들어 있어 저장하지 않음")
        try:
            doc = json.loads(body)
            code = doc["response"]["header"]["resultCode"]
        except (ValueError, KeyError, TypeError):
            sys.exit(f"API page {page}: JSON 아님(서비스키 오류 응답일 수 있음, {len(body)} B)")
        if code != "00":
            sys.exit(f"API page {page}: resultCode {code} {doc['response']['header'].get('resultMsg')}")
        total = int(doc["response"]["body"]["totalCount"])
        fname = f"page_{page:03d}.json"
        (out / fname).write_bytes(body)
        pages.append({"file": fname, "sha256": hashlib.sha256(body).hexdigest(), "bytes": len(body)})
        print(f"ok api page {page} ({len(body):,} B, total {total})")
        page += 1
        time.sleep(0.5)
    (out / "manifest.json").write_text(json.dumps({
        "dataset": "부산광역시_신호등 현황 OpenAPI",
        "publicDataPk": "3079345",
        "page": f"{PORTAL}/data/3079345/openapi.do",
        "endpoint": API_ENDPOINT,
        "params": {"numOfRows": API_ROWS, "resultType": "json"},
        "retrieved": time.strftime("%Y-%m-%d"),
        "totalCount": total,
        "pages": pages,
    }, ensure_ascii=False, indent=2))


def _busan_addr(s: str) -> str:
    """API 주소는 '금정구 ...'처럼 시 이름이 없다. SHP·횡단보도 행과 같게 '부산광역시 '를 붙인다."""
    s = s.strip()
    return s if not s or s.startswith("부산") else "부산광역시 " + s


def _latest_api_dir() -> Path | None:
    dirs = sorted(d for d in RAW.glob("api_*") if (d / "manifest.json").exists())
    return dirs[-1] if dirs else None


def _api_records(api_dir: Path, in_busan) -> tuple[list[dict], dict]:
    """API 원문 페이지를 busan_signals.json 스키마로 정규화한다. 검증 실패 행은 버리고 사유별로 센다."""
    manifest = json.loads((api_dir / "manifest.json").read_text())
    rows = []
    for p in manifest["pages"]:
        body = (api_dir / p["file"]).read_bytes()
        if hashlib.sha256(body).hexdigest() != p["sha256"]:
            sys.exit(f"{api_dir.name}/{p['file']} sha256 불일치(원문이 바뀜)")
        items = json.loads(body)["response"]["body"]["items"]
        item = items.get("item", []) if isinstance(items, dict) else []
        rows += item if isinstance(item, list) else [item]
    # 관리번호가 빈 행(약 15%)도 실제 제어기라서 버리지 않고, 내용 해시로 안정적인 id를 준다.
    # 같은 관리번호가 다른 위치에 두 번 나오면(번호 재사용) 둘 다 두고 뒤의 것에 ~2를 붙인다.
    # 서울·대전 등 부산 밖 좌표(같은 이름 구의 지오코딩 오류로 보임)와 같은 번호·같은 좌표 행은 버린다.
    dropped = {"no_coord": 0, "out_of_bbox": 0, "duplicate": 0}
    counts = {"rows_without_mgrnu": 0, "mgrnu_reused_other_location": 0}
    recs, seen = [], {}
    for r in rows:
        mid = str(r.get("mgrnu") or "").strip()
        try:
            lat, lon = float(r["lat"]), float(r["lng"])
        except (KeyError, TypeError, ValueError):
            dropped["no_coord"] += 1
            continue
        if not (math.isfinite(lat) and math.isfinite(lon) and in_busan(lat, lon)):
            dropped["out_of_bbox"] += 1
            continue
        if not mid:
            counts["rows_without_mgrnu"] += 1
            sig = "|".join(str(r.get(k) or "").strip() for k in ("sigungu", "ins_place", "address", "lat", "lng"))
            mid = "noid-" + hashlib.sha1(sig.encode()).hexdigest()[:10]
        xy = (round(lat, 6), round(lon, 6))
        if xy in seen.get(mid, []):
            dropped["duplicate"] += 1
            continue
        rid = mid if mid not in seen else f"{mid}~{len(seen[mid]) + 1}"
        counts["mgrnu_reused_other_location"] += mid in seen
        seen.setdefault(mid, []).append(xy)
        recs.append({
            "lat": round(lat, 7), "lon": round(lon, 7),
            "source": "busan_api", "id": f"busan_api:{rid}", "mgrnu": None if mid.startswith("noid-") else mid,
            "kind": CONTROLLER_KIND.get(str(r.get("road_type") or "").strip(), "unknown"),
            # gubun(제어방식)·ins_date는 2026-10-22 수집분 전 행이 공백이라 싣지 않는다
            "address": _busan_addr(str(r.get("address") or "")), "place": str(r.get("ins_place") or "").strip() or None,
            "sigungu": str(r.get("sigungu") or "").strip() or None,
            "confirmDate": str(r.get("confirm_date") or "").strip() or None,
        })
    stats = {"dir": api_dir.name, "retrieved": manifest["retrieved"], "totalCount": manifest["totalCount"],
             "rows": len(rows), "kept": len(recs), "dropped": dropped} | counts
    return recs, stats


MOVED_M = 15  # 같은 관리번호인데 이보다 멀면 '이동'
SAME_SPOT_M = 30  # 관리번호 없이 이 거리 안 같은 kind면 같은 제어기로 본다


def _compare_api_shp(api_recs: list[dict], shp_recs: list[dict]) -> tuple[list[dict], dict, list[dict]]:
    """API(2023)와 SHP(2020)를 관리번호·거리로 대조한다.

    API 행에 vs2020(same|moved|new)·dist2020M을 붙이고, 남길 SHP 행(API에 번호가 없지만 현행으로 보이는 것)을 돌려준다.
    """
    shp_by = {r["id"].split(":", 1)[1]: r for r in shp_recs}
    api_by: dict[str, list[dict]] = {}
    for r in api_recs:
        if r["mgrnu"]:
            api_by.setdefault(r["mgrnu"], []).append(r)

    def near_same_kind(rows: list[dict]):
        cells: dict[tuple[int, int], list[dict]] = {}
        for r in rows:
            cells.setdefault(Grid.key(r["lat"], r["lon"]), []).append(r)

        def hit(r: dict) -> bool:
            ky, kx = Grid.key(r["lat"], r["lon"])
            return any(o["kind"] == r["kind"] and _dist_m(r["lat"], r["lon"], o["lat"], o["lon"]) <= SAME_SPOT_M
                       for dy in (-1, 0, 1) for dx in (-1, 0, 1) for o in cells.get((ky + dy, kx + dx), ()))
        return hit

    dists = []
    for mid, rows in api_by.items():
        s = shp_by.get(mid)
        for r in rows:
            r["vs2020"], r["dist2020M"] = "new", None
        if not s:
            continue
        best = min(rows, key=lambda r: _dist_m(r["lat"], r["lon"], s["lat"], s["lon"]))
        d = _dist_m(best["lat"], best["lon"], s["lat"], s["lon"])
        best["vs2020"], best["dist2020M"] = ("moved" if d > MOVED_M else "same"), round(d, 1)
        dists.append(d)
    near_shp = near_same_kind(shp_recs)
    for r in api_recs:
        if not r["mgrnu"]:
            r["vs2020"], r["dist2020M"] = "new", None
    new = [r for r in api_recs if r["vs2020"] == "new"]

    near_api = near_same_kind(api_recs)
    removed = [s for mid, s in shp_by.items() if mid not in api_by]
    shp_kept = [s for s in removed if s["in_2026_controller_list"] and not near_api(s)]
    dists.sort()
    cmp = {
        "matched_by_mgrnu": len(dists),
        "same_within_15m": sum(d <= MOVED_M for d in dists),
        "moved_over_15m": sum(d > MOVED_M for d in dists),
        "moved_15_100m": sum(MOVED_M < d <= 100 for d in dists),
        "moved_100_1000m": sum(100 < d <= 1000 for d in dists),
        "moved_over_1km": sum(d > 1000 for d in dists),
        "median_move_m_when_moved": round(statistics.median([d for d in dists if d > MOVED_M]), 1)
        if any(d > MOVED_M for d in dists) else None,
        "new_in_api": len(new),
        "new_with_mgrnu": sum(bool(r["mgrnu"]) for r in new),
        "new_without_mgrnu": sum(not r["mgrnu"] for r in new),
        "new_within_30m_of_same_kind_2020": sum(near_shp(r) for r in new),
        "removed_2020_mgrnu_not_in_api": len(removed),
        "removed_but_api_point_within_30m_same_kind": sum(near_api(s) for s in removed),
        "removed_but_in_2026_controller_list": sum(s["in_2026_controller_list"] for s in removed),
        "removed_2020_kept_in_output": len(shp_kept),
        "removed_ids": sorted(s["id"] for s in removed),
    }
    return api_recs, cmp, shp_kept


def boundary() -> None:
    """OSM relation(금정구)의 outer 경계를 한국 PBF에서 뽑아 GeoJSON으로 저장한다."""
    import osmium
    from shapely.geometry import LineString, mapping
    from shapely.ops import linemerge, polygonize, unary_union

    class Rel(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.ways: set[int] = set()
            self.tags: dict = {}

        def relation(self, r):
            if r.id == GEUMJEONG_REL:
                self.ways = {m.ref for m in r.members if m.type == "w" and m.role in ("outer", "")}
                self.tags = dict(r.tags)

    rel = Rel()
    rel.apply_file(str(PBF))
    if not rel.ways:
        sys.exit(f"relation {GEUMJEONG_REL} not found in {PBF}")

    class Ways(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.lines: list = []

        def way(self, w):
            if w.id in rel.ways:
                self.lines.append(LineString([(n.lon, n.lat) for n in w.nodes]))

    ways = Ways()
    ways.apply_file(str(PBF), locations=True)
    polys = list(polygonize(linemerge(ways.lines)))
    if not polys:
        sys.exit("boundary ways did not close into a polygon")
    geom = unary_union(polys)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "geumjeong_boundary.geojson").write_text(json.dumps({
        "type": "Feature",
        "properties": {"source": f"OpenStreetMap relation {GEUMJEONG_REL} ({PBF.name})",
                       "license": "ODbL 1.0, © OpenStreetMap contributors",
                       "name": rel.tags.get("name"), "ways": len(ways.lines), "of_ways": len(rel.ways)},
        "geometry": mapping(geom),
    }, ensure_ascii=False))
    print(f"boundary: {len(ways.lines)}/{len(rel.ways)} ways, area≈{geom.area * 111e3 * 91e3 / 1e6:.1f} km²")


def _read_csv(path: Path) -> list[dict]:
    body = path.read_bytes()
    for enc in ("utf-8-sig", "cp949"):
        try:
            return list(csv.DictReader(io.StringIO(body.decode(enc))))
        except UnicodeDecodeError:
            continue
    raise ValueError(f"unknown encoding: {path}")


def _read_shp(zip_path: Path) -> list[dict]:
    import shapefile

    with zipfile.ZipFile(zip_path) as z:
        stem = next(n[:-4] for n in z.namelist() if n.endswith(".shp"))
        prj = z.read(stem + ".prj").decode()
        if "WGS 84" not in prj:  # 현재 원본은 EPSG:4326. 바뀌면 pyproj 변환을 넣어야 한다
            raise ValueError(f"unexpected CRS in {zip_path.name}: {prj[:80]}")
        r = shapefile.Reader(shp=io.BytesIO(z.read(stem + ".shp")), shx=io.BytesIO(z.read(stem + ".shx")),
                             dbf=io.BytesIO(z.read(stem + ".dbf")), encoding="cp949")
        return [rec.as_dict() | {"_x": s.points[0][0], "_y": s.points[0][1]}
                for rec, s in zip(r.records(), r.shapes()) if s.points]


def _dist_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    x = math.radians(lon2 - lon1) * math.cos(math.radians((lat1 + lat2) / 2))
    return 6371000.0 * math.hypot(x, math.radians(lat2 - lat1))


class Grid:
    """약 0.001° 격자. 반경 100m 이하 최근접 거리 조회용(그보다 멀면 inf일 수 있음)."""

    def __init__(self, pts):
        self.cells: dict[tuple[int, int], list[tuple[float, float]]] = {}
        for p in pts:
            self.cells.setdefault(self.key(*p), []).append(tuple(p))

    @staticmethod
    def key(lat: float, lon: float) -> tuple[int, int]:
        return math.floor(lat * 1000), math.floor(lon * 1000)

    def nearest(self, lat: float, lon: float) -> float:
        ky, kx = self.key(lat, lon)
        best = math.inf
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                for p in self.cells.get((ky + dy, kx + dx), ()):
                    best = min(best, _dist_m(lat, lon, *p))
        return best


def _cluster_count(pts, radius_m: float) -> int:
    """반경 안 점들을 한 덩어리로 묶은 개수. 교차로 하나에 점이 여러 개 찍힌 경우의 대략적 교차로 수."""
    parent = list(range(len(pts)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    grid: dict[tuple[int, int], list[int]] = {}
    for i, p in enumerate(pts):
        grid.setdefault(Grid.key(*p), []).append(i)
    for i, p in enumerate(pts):
        ky, kx = Grid.key(*p)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                for j in grid.get((ky + dy, kx + dx), ()):
                    if j > i and _dist_m(*p, *pts[j]) <= radius_m:
                        parent[find(i)] = find(j)
    return len({find(i) for i in range(len(pts))})


def build() -> None:
    from shapely.geometry import Point, shape
    from shapely.prepared import prep

    manifest = json.loads((RAW / "manifest.json").read_text())

    def raw(name: str) -> Path:
        return RAW / manifest[name]["file"]

    def in_busan(lat: float, lon: float) -> bool:
        return BUSAN_BBOX[0] <= lat <= BUSAN_BBOX[2] and BUSAN_BBOX[1] <= lon <= BUSAN_BBOX[3]

    shp_recs: list[dict] = []
    dropped = {"out_of_bbox": 0, "duplicate": 0, "no_coord": 0}

    # 1) 신호제어기 위치(SHP, 2020-12-31 확인분). 2026-02-01 제어기 목록과 관리번호+구로 대조해 현행 여부를 붙인다.
    active = {r["관리번호"].strip(): r for r in _read_csv(raw("busan_signal_controllers_csv"))}
    seen: set[str] = set()
    for r in _read_shp(raw("busan_traffic_lights_shp")):
        lat, lon = float(r["lat"] or r["_y"]), float(r["lng"] or r["_x"])
        mid = str(r["mgrnu"]).strip()
        if not in_busan(lat, lon):
            dropped["out_of_bbox"] += 1
            continue
        if mid in seen:
            dropped["duplicate"] += 1
            continue
        seen.add(mid)
        now = active.get(mid)
        shp_recs.append({
            "lat": round(lat, 7), "lon": round(lon, 7),
            "source": "busan_signal_lights_2020", "id": f"busan_ctl:{mid}",
            "kind": CONTROLLER_KIND.get(r["road_type"].strip(), "unknown"),
            "address": r["address"].strip(), "place": r["ins_place"].strip() or None,
            "control": r["name"].replace(" ", "").strip() or None,
            "in_2026_controller_list": bool(now and now["구군별"].strip() == r["sigungu"].strip()),
        })

    # 1b) 신호등 현황 OpenAPI(2023 확인분)가 있으면 같은 관리번호의 SHP 행을 대체한다.
    #     API에 번호가 없는 SHP 행은 2026 제어기 목록에도 있고, 30m 안에 같은 kind의 API 점(번호 빈 행일 수 있음)이
    #     없을 때만 남긴다. 2023 API와 2026 목록 둘 다에 없으면 철거로 본다.
    api_dir = _latest_api_dir()
    api_cmp = None
    if api_dir:
        api_recs, api_stats = _api_records(api_dir, in_busan)
        for r in api_recs:
            now = active.get(r["mgrnu"] or "")
            r["in_2026_controller_list"] = bool(now and now["구군별"].strip() == (r["sigungu"] or ""))
        api_recs, api_cmp, shp_kept = _compare_api_shp(api_recs, shp_recs)
        api_cmp = {"api": api_stats} | api_cmp
        recs = api_recs + shp_kept
    else:
        recs = shp_recs

    # 2) 신호등 있는 횡단보도(CSV, 2025-04-23 기준). 보행 신호라 좌회전 보호 판정에 직접 쓰지 않는다.
    seen_xy: set[tuple[float, float]] = set()
    for r in _read_csv(raw("busan_signal_crosswalks_csv")):
        try:
            lat, lon = float(r["위도"]), float(r["경도"])
        except ValueError:
            dropped["no_coord"] += 1
            continue
        if not in_busan(lat, lon):
            dropped["out_of_bbox"] += 1
            continue
        xy = (round(lat, 6), round(lon, 6))  # 0.1m 안 동일 좌표는 중복
        if xy in seen_xy:
            dropped["duplicate"] += 1
            continue
        seen_xy.add(xy)
        parts = (r["행정구역(구)"], r["행정구역(동)"], r["행정구역(리)"].strip("-"), r["지번"])
        recs.append({
            "lat": round(lat, 7), "lon": round(lon, 7),
            "source": "busan_signal_crosswalks_20250423", "id": f"busan_xwalk:{r['순번'].strip()}",
            "kind": "pedestrian_crossing",
            "address": "부산광역시 " + " ".join(x.strip() for x in parts if x and x.strip()),
            "place": r["교차로"].strip() or None,
        })

    gj = prep(shape(json.loads((OUT / "geumjeong_boundary.geojson").read_text())["geometry"]))
    for r in recs:
        r["in_geumjeong"] = gj.contains(Point(r["lon"], r["lat"]))
    gj_recs = [r for r in recs if r["in_geumjeong"]]
    if api_cmp:
        api_recs = [r for r in recs if r["source"] == "busan_api"]
        (OUT / "busan_signals_api.json").write_text(json.dumps(api_recs, ensure_ascii=False, indent=0))
        removed = set(api_cmp["removed_ids"])
        gj_api = [r for r in api_recs if r["in_geumjeong"]]
        api_cmp["geumjeong"] = {
            "api_controllers": len(gj_api),
            "api_by_kind": dict(sorted(Counter(r["kind"] for r in gj_api).items())),
            "api_by_vs2020": dict(sorted(Counter(r["vs2020"] for r in gj_api).items())),
            "removed_2020": sum(gj.contains(Point(s["lon"], s["lat"])) for s in shp_recs if s["id"] in removed),
            "output_intersection_controllers": sum(r["kind"] == "intersection" for r in gj_recs),
            "output_intersection_controllers_2020_only": sum(r["kind"] == "intersection" for r in shp_recs
                                                             if gj.contains(Point(r["lon"], r["lat"]))),
        }
    (OUT / "busan_signals.json").write_text(json.dumps(recs, ensure_ascii=False, indent=0))
    (OUT / "geumjeong_signals.json").write_text(json.dumps(gj_recs, ensure_ascii=False, indent=0))
    # 엔진 TARAANG_SIGNALS_FILE과 같은 [lat, lon] 형식: 교차로 차량 신호제어기만
    veh = [[r["lat"], r["lon"]] for r in recs if r["kind"] == "intersection"]
    (OUT / "busan_intersection_controllers_latlon.json").write_text(json.dumps(veh))

    # 3) OSM 신호등과 비교
    osm = [tuple(p) for p in json.loads(OSM_SIGNALS.read_text())]
    osm_gj = [p for p in osm if gj.contains(Point(p[1], p[0]))]
    osm_grid = Grid(osm)

    def public_side(rows: list[dict]) -> dict:
        d = [osm_grid.nearest(r["lat"], r["lon"]) for r in rows]
        out = {"n": len(rows)}
        for t in (30, 50, 100):
            far = sum(x > t for x in d)
            out[f"farther_than_{t}m_from_osm"] = far
            out[f"farther_than_{t}m_share"] = round(far / len(rows), 3) if rows else None
        near = sorted(x for x in d if x <= 100)
        out["median_dist_m_when_osm_within_100m"] = round(near[len(near) // 2], 1) if near else None
        return out

    def osm_side(osm_pts: list, rows: list[dict]) -> dict:
        g = Grid([(r["lat"], r["lon"]) for r in rows])
        return {f"osm_with_public_within_{t}m": sum(g.nearest(*p) <= t for p in osm_pts) for t in (30, 50, 100)}

    def area(rows: list[dict], osm_pts: list) -> dict:
        inter = [r for r in rows if r["kind"] == "intersection"]
        return {
            "osm_signal_nodes": len(osm_pts),
            "osm_signal_clusters_30m": _cluster_count(osm_pts, 30),
            "public_by_kind": {k: public_side([r for r in rows if r["kind"] == k])
                               for k in sorted({r["kind"] for r in rows})},
            "public_intersection_controllers_in_2026_list": sum(r["in_2026_controller_list"] for r in inter),
            "osm_vs_public_intersection_controllers": osm_side(osm_pts, inter),
            "osm_vs_public_any": osm_side(osm_pts, rows),
        }

    ids_2020 = {r["id"].split(":")[1] for r in shp_recs}
    report = {
        "generated": time.strftime("%Y-%m-%d"),
        "osm_signals_file": OSM_SIGNALS.name,
        "dropped": dropped,
        "busan": area(recs, osm),
        "geumjeong": area(gj_recs, osm_gj) | {
            "controllers_2026_list_rows": sum(r["구군별"].strip() == "금정구" for r in active.values())},
        "controllers_2026": {
            "rows": len(active),
            "with_2020_coordinates": len(set(active) & ids_2020),
            "without_coordinates": len(set(active) - ids_2020),
            "only_in_2020": len(ids_2020 - set(active)),
        },
    }
    if api_cmp:
        api_ids = {r["mgrnu"] for r in recs if r["source"] == "busan_api" and r["mgrnu"]}
        report["controllers_2026"] |= {"in_api": len(set(active) & api_ids),
                                       "in_neither_2020_nor_api": len(set(active) - ids_2020 - api_ids)}
        report["output_controllers_by_source"] = dict(Counter(r["source"] for r in recs
                                                              if r["kind"] != "pedestrian_crossing"))
        report["api_vs_2020"] = api_cmp
    (OUT / "coverage.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    step = sys.argv[1] if len(sys.argv) > 1 else "all"
    if step in ("download", "all"):
        download()
    if step in ("api", "all"):
        api()
    if step == "boundary" or (step == "all" and not (OUT / "geumjeong_boundary.geojson").exists()):
        boundary()
    if step in ("build", "all"):
        build()
