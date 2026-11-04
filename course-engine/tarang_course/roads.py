"""도로 속성(차로 수·제한속도) 레이어. 원문·방법·한계는 data/public/roadnet/PROVENANCE.md, 만들기는 scripts/build_road_layer.py.

GraphHopper 경로에는 OSM way id가 없다(그래프를 다시 만들어야 한다). 그래서 경로를 SAMPLE_M 간격으로 짚어
MATCH_M 안·진행 방향 ±MAX_HEADING_DIFF° 안의 가장 가까운 way 선분에 붙인다. 방향 조건이 없으면 큰길을 가로지를 때
그 큰길에 붙는다.

제한속도 출처: osm·nodelink는 관측값(VERIFIED), default_5030은 법·정책에서 어림한 값(ESTIMATED)이다.
단계 판정(빠른 길)에는 관측값만 쓴다. 지금은 금정구만 있다. 밖의 구간은 "모름"이다.
"""

import json
import math
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import shapely

from .geo import cumulative

ROAD_FILE = Path(__file__).resolve().parent.parent / "data" / "roads_geumjeong.json"
ATTRIBUTION = "차로 수·제한속도: 국토교통부 국가교통정보센터 표준노드링크(2026-10-14), 공공데이터포털(data.go.kr)"

SAMPLE_M = 10
MATCH_M = 15  # 노드링크 매칭(enrich_osm_with_nodelink.py)과 같은 반경
MAX_HEADING_DIFF = 30
VERIFIED_SOURCES = {"osm", "nodelink"}
ESTIMATED_SOURCES = {"default_5030"}

FAST_KMH = 50  # 이보다 빠른 제한속도(관측값)는 1단계에서 피한다. 도시부 일반도로 상한(도로교통법 시행규칙 제19조)
WIDE_LANES = 6  # 왕복 기준 차로 수가 이 이상이면 큰길(편도 3차로 이상). 1단계 조건
MULTI_LANES = 4  # 편도 2차로. 보통 시내 주행이라 조건·경고에는 쓰지 않고 debug에만 센다
MIN_STRETCH_M = 30  # 이보다 짧게 스치는 구간(교차로 안 등)은 세지 않는다
MERGE_GAP_M = 20  # 이보다 짧게 끊긴 같은 종류 구간은 하나로 본다
# 출발·도착 도로는 사용자가 고른 곳이라 어느 코스나 같다. 앞뒤 이 거리는 구간 수에 넣지 않는다(속도·비율에는 넣는다)
ENDPOINT_EXEMPT_M = 150

FAST_ROAD = "FAST_ROAD"
WIDE_ROAD = "WIDE_ROAD"
MULTI_LANE_ROAD = "MULTI_LANE_ROAD"

M_PER_DEG_LAT = 111_320.0


@dataclass(frozen=True)
class RoadSummary:
    """코스 하나의 도로 속성 요약. 길이 비율은 경로 전체 길이 대비."""

    max_speed_kmh: int | None  # 붙은 구간 중 가장 높은 제한속도(관측·어림 모두)
    max_speed_confidence: str | None  # VERIFIED | ESTIMATED
    matched_share: float  # 도로 레이어에 붙은 길이 비율
    speed_verified_share: float  # 관측 제한속도가 있는 길이 비율
    speed_estimated_share: float  # 5030 어림값만 있는 길이 비율
    stretches: dict[str, int]  # FAST_ROAD/WIDE_ROAD/MULTI_LANE_ROAD 구간 수

    def as_debug(self) -> dict:
        return {
            "maxSpeedKmh": self.max_speed_kmh,
            "maxSpeedConfidence": self.max_speed_confidence,
            "matchedShare": self.matched_share,
            "speedVerifiedShare": self.speed_verified_share,
            "speedEstimatedShare": self.speed_estimated_share,
            "stretches": self.stretches,
        }


class RoadLayer:
    def __init__(self, ways: list[dict], attribution: list[str] | None = None):
        self.attribution = [ATTRIBUTION] if attribution is None else attribution
        self.ways = ways
        if not ways:
            self._tree = None
            return
        lats = [p[0] for w in ways for p in w["coords"]]
        lons = [p[1] for w in ways for p in w["coords"]]
        self.lat0, self.lon0 = (min(lats) + max(lats)) / 2, (min(lons) + max(lons)) / 2
        self.kx = M_PER_DEG_LAT * math.cos(math.radians(self.lat0))
        pad = MATCH_M / M_PER_DEG_LAT
        self.bbox = (min(lats) - pad, min(lons) - pad * 1.3, max(lats) + pad, max(lons) + pad * 1.3)
        seg_way, starts, ends = [], [], []
        for i, w in enumerate(ways):
            xy = [self._xy(lat, lon) for lat, lon in w["coords"]]
            for a, b in zip(xy, xy[1:]):
                if a != b:
                    seg_way.append(i)
                    starts.append(a)
                    ends.append(b)
        self._seg_way = np.array(seg_way)
        a, b = np.array(starts), np.array(ends)
        self._seg_heading = np.degrees(np.arctan2(b[:, 0] - a[:, 0], b[:, 1] - a[:, 1]))  # 북쪽 기준
        self._tree = shapely.STRtree(shapely.linestrings(np.stack([a, b], axis=1)))
        self._speed = np.array([w["maxSpeedKmh"] if w["maxSpeedKmh"] is not None else -1 for w in ways])
        self._verified = np.array([w["speedSource"] in VERIFIED_SOURCES for w in ways])
        self._estimated = np.array([w["speedSource"] in ESTIMATED_SOURCES for w in ways])
        lanes = np.array([w["lanes"] if w["lanes"] is not None else -1 for w in ways])
        oneway = np.array([bool(w["oneway"]) for w in ways])
        # 왕복 기준 차로 수: 일방통행 way의 차로 수는 한 방향이라 두 배로 본다
        two_way_equiv = np.where(oneway, lanes * 2, lanes)
        self._wide = (lanes > 0) & (two_way_equiv >= WIDE_LANES)
        self._multi = (lanes > 0) & (two_way_equiv >= MULTI_LANES)
        self._fast = self._verified & (self._speed > FAST_KMH)

    def __len__(self) -> int:
        return len(self.ways)

    def _xy(self, lat, lon):
        return (lon - self.lon0) * self.kx, (lat - self.lat0) * M_PER_DEG_LAT

    def match(self, path: list[list[float]]) -> np.ndarray:
        """경로를 SAMPLE_M 간격으로 짚은 점마다 붙은 way 인덱스(없으면 -1)."""
        if self._tree is None or len(path) < 2:
            return np.full(0, -1)
        pts = np.asarray(path, dtype=float)
        cum = np.asarray(cumulative(path))
        s = np.arange(SAMPLE_M / 2, cum[-1], SAMPLE_M)
        if len(s) == 0:
            return np.full(0, -1)
        seg = np.clip(np.searchsorted(cum, s, side="left") - 1, 0, len(path) - 2)
        length = cum[seg + 1] - cum[seg]
        f = np.divide(s - cum[seg], length, out=np.zeros_like(s), where=length > 0)
        lat = pts[seg, 0] + (pts[seg + 1, 0] - pts[seg, 0]) * f
        lon = pts[seg, 1] + (pts[seg + 1, 1] - pts[seg, 1]) * f
        out = np.full(len(s), -1)
        lat_lo, lon_lo, lat_hi, lon_hi = self.bbox
        inside = (lat >= lat_lo) & (lat <= lat_hi) & (lon >= lon_lo) & (lon <= lon_hi)
        if not inside.any():
            return out
        x, y = self._xy(lat, lon)
        dx = (pts[seg + 1, 1] - pts[seg, 1]) * self.kx
        dy = (pts[seg + 1, 0] - pts[seg, 0]) * M_PER_DEG_LAT
        heading = np.degrees(np.arctan2(dx, dy))
        idx = np.flatnonzero(inside)
        points = shapely.points(x[idx], y[idx])
        pi, si = self._tree.query(points, predicate="dwithin", distance=MATCH_M)
        if len(pi) == 0:
            return out
        diff = np.abs((heading[idx][pi] - self._seg_heading[si] + 180) % 360 - 180)
        diff = np.minimum(diff, 180 - diff)  # way 선분 방향과 같거나 반대(양방향 통행)
        ok = diff <= MAX_HEADING_DIFF
        pi, si = pi[ok], si[ok]
        if len(pi) == 0:
            return out
        dist = shapely.distance(points[pi], self._tree.geometries[si])
        order = np.lexsort((dist, pi))  # 점마다 가장 가까운 선분이 먼저
        first = np.ones(len(order), dtype=bool)
        first[1:] = pi[order][1:] != pi[order][:-1]
        best = order[first]
        out[idx[pi[best]]] = self._seg_way[si[best]]
        return out

    def summarize(self, path: list[list[float]]) -> RoadSummary:
        ways = self.match(path)
        n = len(ways)
        matched = ways >= 0
        if n == 0 or not matched.any():
            return RoadSummary(None, None, 0.0, 0.0, 0.0, {FAST_ROAD: 0, WIDE_ROAD: 0, MULTI_LANE_ROAD: 0})
        w = ways[matched]
        # 출발·도착 근처 표본은 구간 수에서 뺀다
        counted = matched.copy()
        edge = ENDPOINT_EXEMPT_M // SAMPLE_M
        counted[:edge] = False
        counted[max(n - edge, 0) :] = False
        speeds = self._speed[w]
        known = speeds > 0
        max_speed = max_conf = None
        if known.any():
            max_speed = int(speeds[known].max())
            top = known & (speeds == max_speed)
            max_conf = "VERIFIED" if self._verified[w][top].any() else "ESTIMATED"
        return RoadSummary(
            max_speed_kmh=max_speed,
            max_speed_confidence=max_conf,
            matched_share=round(float(matched.mean()), 3),
            speed_verified_share=round(float((self._verified[w] & known).sum()) / n, 3),
            speed_estimated_share=round(float((self._estimated[w] & known).sum()) / n, 3),
            stretches={
                FAST_ROAD: _stretches(np.where(counted, self._fast[np.maximum(ways, 0)], False)),
                WIDE_ROAD: _stretches(np.where(counted, self._wide[np.maximum(ways, 0)], False)),
                MULTI_LANE_ROAD: _stretches(np.where(counted, self._multi[np.maximum(ways, 0)], False)),
            },
        )


def _stretches(flags: np.ndarray) -> int:
    """True가 이어진 구간 수. MERGE_GAP_M 이하로 끊긴 것은 잇고, MIN_STRETCH_M보다 짧은 것은 뺀다."""
    runs: list[list[int]] = []  # [시작, 끝) 샘플 인덱스
    for k in np.flatnonzero(flags):
        if runs and (k - runs[-1][1]) * SAMPLE_M <= MERGE_GAP_M:
            runs[-1][1] = k + 1
        else:
            runs.append([int(k), int(k) + 1])
    return sum(1 for a, b in runs if (b - a) * SAMPLE_M >= MIN_STRETCH_M)


def load_road_layer(path: Path = ROAD_FILE) -> RoadLayer:
    """파일이 없으면 FileNotFoundError."""
    raw = json.loads(path.read_text())
    return RoadLayer(raw["ways"], list(raw.get("attribution", [ATTRIBUTION])))
