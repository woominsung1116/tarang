"""신호 교차로 레이어: OSM 신호 노드 + 부산 공공 신호제어기를 도로 교차점에 붙여 합친다.

공공 데이터 좌표는 신호등이 아니라 보도 모서리의 제어기함이라 교차로 중심에서 중앙값 28–43m 떨어져 있다
(data/public/signals/PROVENANCE.md). 회전 지점 30m 조회에 쓰려면 가장 가까운 자동차 도로 교차점으로 옮겨야 한다.
보행 신호(횡단보도·단일로)는 "신호 있는 좌회전" 근거가 아니므로 넣지 않는다.
"""

import json
import math
from collections import defaultdict
from pathlib import Path
from typing import Any

from .geo import SignalIndex, haversine

DATA = Path(__file__).resolve().parent.parent / "data"
MERGED_FILE = DATA / "signals_busan_merged.json"
OSM_ONLY_FILE = DATA / "signals_busan.json"

# 차량 신호제어기로 쓰는 공공 데이터 종류. midblock(단일로, 대부분 보행 신호)·pedestrian_crossing·unknown은 뺀다
VEHICLE_KINDS = {"intersection", "reversible_lane"}
SNAP_RADIUS_M = 60
DEDUPE_RADIUS_M = 30

ATTRIBUTION_BUSAN = "신호 데이터: 부산광역시, 공공데이터포털(data.go.kr)"


def default_signals_file() -> Path:
    """합친 레이어가 있으면 그것, 없으면 OSM 신호만."""
    return MERGED_FILE if MERGED_FILE.exists() else OSM_ONLY_FILE


class GridIndex:
    """위경도 격자(약 110m)로 반경 조회. 부산 교차점 수만 개를 controller 2천 개가 찾는 정도의 규모."""

    CELL_DEG = 0.001

    def __init__(self, points: list[tuple[float, float]]):
        self.points = points
        self.cells: dict[tuple[int, int], list[int]] = defaultdict(list)
        for i, (lat, lon) in enumerate(points):
            self.cells[self._cell(lat, lon)].append(i)

    def _cell(self, lat: float, lon: float) -> tuple[int, int]:
        return math.floor(lat / self.CELL_DEG), math.floor(lon / self.CELL_DEG)

    def nearest(self, lat: float, lon: float, radius_m: float) -> tuple[int, float] | None:
        """반경 안 가장 가까운 점의 (index, 거리). radius_m은 셀 크기의 몇 배까지 본다."""
        span = math.ceil(radius_m / (self.CELL_DEG * 90_000))
        ci, cj = self._cell(lat, lon)
        best = None
        for di in range(-span, span + 1):
            for dj in range(-span, span + 1):
                for i in self.cells.get((ci + di, cj + dj), ()):
                    d = haversine(lat, lon, *self.points[i])
                    if d <= radius_m and (best is None or d < best[1]):
                        best = (i, d)
        return best


def snap_controllers(
    controllers: list[dict[str, Any]], junctions: list[tuple[float, float]], radius_m: float = SNAP_RADIUS_M
) -> tuple[list[dict[str, Any]], dict[str, int]]:
    """차량 신호제어기를 가장 가까운 교차점에 붙인다. 반경 안에 교차점이 없으면 버린다(어디인지 모르는 신호는 근거가 아니다).

    반환: (붙인 점 목록, 집계). 같은 교차점에 붙은 제어기는 한 점으로 합친다.
    """
    index = GridIndex(junctions)
    by_junction: dict[int, dict[str, Any]] = {}
    stats = {"input": len(controllers), "excluded_kind": 0, "no_junction_within_radius": 0, "snapped": 0}
    for c in controllers:
        if c.get("kind") not in VEHICLE_KINDS:
            stats["excluded_kind"] += 1
            continue
        hit = index.nearest(c["lat"], c["lon"], radius_m)
        if hit is None:
            stats["no_junction_within_radius"] += 1
            continue
        stats["snapped"] += 1
        i, d = hit
        point = by_junction.setdefault(
            i,
            {"lat": junctions[i][0], "lon": junctions[i][1], "sources": ["busan_public"], "ids": [], "snapDistanceM": round(d, 1)},
        )
        point["ids"].append(c["id"])
        point["snapDistanceM"] = min(point["snapDistanceM"], round(d, 1))
    return list(by_junction.values()), stats


def merge(osm: list[list[float]], snapped: list[dict[str, Any]], dedupe_m: float = DEDUPE_RADIUS_M) -> list[dict[str, Any]]:
    """OSM 신호 노드와 붙인 공공 신호를 합친다. dedupe_m 안에 겹치면 한 점(OSM 좌표)으로 두고 출처를 모두 남긴다."""
    points = [{"lat": lat, "lon": lon, "sources": ["osm"], "ids": [], "snapDistanceM": None} for lat, lon in osm]
    index = GridIndex([(p["lat"], p["lon"]) for p in points])
    for s in snapped:
        hit = index.nearest(s["lat"], s["lon"], dedupe_m)
        if hit is None:
            points.append(dict(s))
            continue
        p = points[hit[0]]
        if "busan_public" not in p["sources"]:
            p["sources"].append("busan_public")
        p["ids"] += s["ids"]
        p["snapDistanceM"] = s["snapDistanceM"] if p["snapDistanceM"] is None else min(p["snapDistanceM"], s["snapDistanceM"])
    return points


def load_signal_index(path: Path) -> SignalIndex:
    """[lat, lon] 목록(OSM만) 또는 합친 레이어 {"points": [...], "attribution": [...]} 둘 다 읽는다."""
    raw = json.loads(path.read_text())
    if isinstance(raw, list):
        return SignalIndex(raw)
    return SignalIndex([[p["lat"], p["lon"]] for p in raw["points"]], list(raw.get("attribution", [])))
