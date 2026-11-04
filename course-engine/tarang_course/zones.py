"""보호구역·사고다발지역 레이어. 원문과 한계는 data/public/zones/PROVENANCE.md.

두 곳에 쓴다.
1. 코스 생성: 요청 지역의 구역을 GraphHopper custom_model `areas`로 만들어 단계별로 피하거나 감점한다(levels.ZONE_PRIORITY).
2. 코스 분석: 경로가 어린이보호구역에 들어가는 지점(school_entries)을 SCHOOL_ZONE maneuver로 만든다.

어린이·노인보호구역 데이터는 시설 좌표만 있다(실제 구역은 시설 주변 도로 구간). 시설 중심 원으로 근사하는데,
300m 원을 막으면 부산대 앞 순환 코스가 만들어지지 않아(6/6 seed 실패) 기본 반경은 150m다.
사고다발지역은 원문 폴리곤(대부분 반경 약 200m)이고, 도로가 바뀌었을 수 있는 오래된 해는 빼고 2020년 이후만 쓴다.
"""

import json
import math
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any

import numpy as np
import shapely
from shapely.geometry import Point, mapping, shape
from shapely.ops import unary_union

from .geo import cumulative, haversine

ZONES_DIR = Path(__file__).resolve().parent.parent / "data" / "public" / "zones"
SCHOOL_FILE = "busan_school_zones.geojson"
ELDERLY_FILE = "busan_elderly_zones.geojson"
HOTSPOT_FILE = "busan_accident_hotspots.geojson"

SCHOOL_ZONES = "school_zones"
ELDERLY_ZONES = "elderly_zones"
ACCIDENT_HOTSPOTS = "accident_hotspots"

DEFAULT_BUFFER_M = 150
HOTSPOT_MIN_YEAR = 2020
SCHOOL_DEFAULT_KMH = 30  # 도로교통법 제12조. 원문에 제한속도 필드가 없다
SIMPLIFY_M = 10
CIRCLE_QUAD_SEGS = 4  # 원을 16각형으로. 150m 원에서 변 중앙이 원보다 약 3m 안쪽
# 출발지가 막힌 구역 안이면 GraphHopper가 출발 도로를 못 찾는다. 출발지에서 이 거리 안의 막는 구역은 뺀다
ORIGIN_CLEAR_M = 100
SAMPLE_M = 5  # 경로를 이 간격으로 짚어 보호구역 진입을 찾는다
REENTRY_GAP_M = 50  # 구역을 나와 이보다 짧게 달리고 다시 들어가면 같은 구역으로 본다

ATTRIBUTION = "보호구역·사고다발지역: 공공데이터포털(data.go.kr) 전국어린이보호구역·노인장애인보호구역·교통사고다발지역 표준데이터"

M_PER_DEG_LAT = 111_320.0


@dataclass(frozen=True)
class Facility:
    lat: float
    lon: float
    speed_limit_kmh: int


@dataclass(frozen=True)
class SchoolEntry:
    """경로가 어린이보호구역에 들어가는 지점."""

    path_index: int  # 진입 지점이 속한 path 구간 시작 인덱스
    distance_m: float
    lat: float
    lon: float
    speed_limit_kmh: int


class LocalFrame:
    """출발지 중심 평면(m). 수 km 범위라 등장방형 근사로 충분하고, 버퍼·단순화를 미터로 할 수 있다."""

    def __init__(self, lat: float, lon: float):
        self.lat0, self.lon0 = lat, lon
        self.ky = M_PER_DEG_LAT
        self.kx = M_PER_DEG_LAT * math.cos(math.radians(lat))

    def xy(self, lat, lon):
        """float 또는 numpy 배열."""
        return (lon - self.lon0) * self.kx, (lat - self.lat0) * self.ky

    def to_local(self, geom_lonlat):
        return shapely.transform(geom_lonlat, lambda c: (c - (self.lon0, self.lat0)) * (self.kx, self.ky))

    def to_lonlat(self, geom_xy):
        return shapely.transform(geom_xy, lambda c: c / (self.kx, self.ky) + (self.lon0, self.lat0))


class ZoneLayer:
    def __init__(
        self,
        school: list[Facility],
        elderly: list[Facility],
        hotspots: list[Any],
        buffer_m: float = DEFAULT_BUFFER_M,
        attribution: list[str] | None = None,
    ):
        self.school = school
        self._school_lat = np.array([f.lat for f in school])
        self._school_lon = np.array([f.lon for f in school])
        self._school_kmh = np.array([f.speed_limit_kmh for f in school])
        self.elderly = elderly
        self.hotspots = hotspots  # shapely Polygon (lon, lat)
        self.buffer_m = buffer_m
        self.attribution = [ATTRIBUTION] if attribution is None else attribution

    def __len__(self) -> int:
        return len(self.school) + len(self.elderly) + len(self.hotspots)

    def custom_model(self, base: dict[str, Any], factors: dict[str, str], lat: float, lon: float, radius_m: float) -> dict[str, Any]:
        """base custom_model에 구역 감점 규칙과 areas를 더한 새 dict. 반경 안에 구역이 없으면 base 그대로.

        factors: 구역 id → multiply_by("0"이면 막기). 막는 구역은 출발지 ORIGIN_CLEAR_M 안의 것을 뺀다.
        """
        if not factors:
            return base
        frame = LocalFrame(lat, lon)
        features, rules = [], []
        for area_id, factor in factors.items():
            geom = self._area(area_id, frame, radius_m, clear_origin=float(factor) == 0)
            if geom is None:
                continue
            features.append({"type": "Feature", "id": area_id, "properties": {}, "geometry": mapping(geom)})
            rules.append({"if": f"in_{area_id}", "multiply_by": factor})
        if not features:
            return base
        return {
            **base,
            "priority": list(base.get("priority", [])) + rules,
            "areas": {"type": "FeatureCollection", "features": features},
        }

    def _area(self, area_id: str, frame: LocalFrame, radius_m: float, clear_origin: bool):
        """반경 안 구역을 합치고 단순화한 (lon, lat) 도형. 없으면 None."""
        reach = radius_m + self.buffer_m
        if area_id == ACCIDENT_HOTSPOTS:
            parts = []
            for poly in self.hotspots:
                c = poly.centroid
                if haversine(frame.lat0, frame.lon0, c.y, c.x) <= reach + 500:
                    parts.append(frame.to_local(poly))
        else:
            facilities = self.school if area_id == SCHOOL_ZONES else self.elderly
            parts = [
                Point(frame.xy(f.lat, f.lon)).buffer(self.buffer_m, quad_segs=CIRCLE_QUAD_SEGS)
                for f in facilities
                if haversine(frame.lat0, frame.lon0, f.lat, f.lon) <= reach
            ]
        if clear_origin:
            origin = Point(0, 0)
            parts = [p for p in parts if p.distance(origin) > ORIGIN_CLEAR_M]
        if not parts:
            return None
        merged = unary_union(parts).simplify(SIMPLIFY_M, preserve_topology=True)
        if merged.is_empty:
            return None
        return shapely.set_precision(frame.to_lonlat(merged), 1e-5)  # 약 1m. 요청 본문을 줄인다

    def school_entries(self, path: list[list[float]]) -> list[SchoolEntry]:
        """경로가 어린이보호구역(시설 중심 buffer_m 원)에 들어가는 지점. 출발지가 구역 안이면 0m 지점.

        경로를 SAMPLE_M 간격으로 짚어 시설까지 거리를 한꺼번에(numpy) 잰다. 후보 24개 × 수백 점이라 점마다
        haversine을 부르면 요청 하나에 수 초가 걸린다.
        """
        if len(path) < 2 or not self.school:
            return []
        pts = np.asarray(path, dtype=float)
        pad = self.buffer_m / M_PER_DEG_LAT * 2
        lat_lo, lon_lo = pts.min(axis=0) - pad
        lat_hi, lon_hi = pts.max(axis=0) + pad
        near = (self._school_lat >= lat_lo) & (self._school_lat <= lat_hi) & (self._school_lon >= lon_lo) & (self._school_lon <= lon_hi)
        if not near.any():
            return []
        frame = LocalFrame(*path[0])
        cx, cy = frame.xy(self._school_lat[near], self._school_lon[near])
        kmh = self._school_kmh[near]
        cum = np.asarray(cumulative(path))
        s = np.append(np.arange(0.0, cum[-1], SAMPLE_M), cum[-1])
        seg = np.clip(np.searchsorted(cum, s, side="left") - 1, 0, len(path) - 2)
        length = cum[seg + 1] - cum[seg]
        f = np.divide(s - cum[seg], length, out=np.zeros_like(s), where=length > 0)
        lat = pts[seg, 0] + (pts[seg + 1, 0] - pts[seg, 0]) * f
        lon = pts[seg, 1] + (pts[seg + 1, 1] - pts[seg, 1]) * f
        x, y = frame.xy(lat, lon)
        inside = (x[:, None] - cx) ** 2 + (y[:, None] - cy) ** 2 <= self.buffer_m**2
        out: list[SchoolEntry] = []
        last_inside_at: float | None = None
        for k in np.flatnonzero(inside.any(axis=1)):
            limit = int(kmh[inside[k]].min())
            if last_inside_at is None or s[k] - last_inside_at > REENTRY_GAP_M:
                out.append(SchoolEntry(int(seg[k]), round(float(s[k]), 1), round(float(lat[k]), 6), round(float(lon[k]), 6), limit))
            elif limit < out[-1].speed_limit_kmh:
                # 이어진 구간 안에서 더 낮은 제한속도를 만나면 진입 지점에 그 값을 둔다(가장 엄격한 값으로 안내)
                out[-1] = replace(out[-1], speed_limit_kmh=limit)
            last_inside_at = s[k]
        return out


def _facilities(path: Path, default_kmh: int) -> list[Facility]:
    out = []
    for f in json.loads(path.read_text())["features"]:
        p = f["properties"]
        out.append(Facility(float(p["lat"]), float(p["lon"]), int(p.get("speedLimitKmh") or default_kmh)))
    return out


def load_zone_layer(directory: Path = ZONES_DIR, buffer_m: float = DEFAULT_BUFFER_M) -> ZoneLayer:
    """부산 GeoJSON 3종을 읽는다. 파일이 없으면 FileNotFoundError."""
    school = _facilities(directory / SCHOOL_FILE, SCHOOL_DEFAULT_KMH)
    elderly = _facilities(directory / ELDERLY_FILE, SCHOOL_DEFAULT_KMH)
    hotspots = [
        shape(f["geometry"])
        for f in json.loads((directory / HOTSPOT_FILE).read_text())["features"]
        if (f["properties"].get("year") or 0) >= HOTSPOT_MIN_YEAR
    ]
    return ZoneLayer(school, elderly, hotspots, buffer_m)
