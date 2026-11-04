"""거리 계산 유틸. 좌표는 모두 (lat, lon)."""

import math

EARTH_RADIUS_M = 6_371_000


def haversine(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    lat1, lon1, lat2, lon2 = map(math.radians, (lat1, lon1, lat2, lon2))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(h))


def bearing(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """북쪽 기준 시계 방향 0–360도."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    x = math.sin(dl) * math.cos(p2)
    y = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(x, y)) + 360) % 360


def cumulative(path: list[list[float]]) -> list[float]:
    """path 각 점까지의 누적 거리(m)."""
    out = [0.0]
    for (la1, lo1), (la2, lo2) in zip(path, path[1:]):
        out.append(out[-1] + haversine(la1, lo1, la2, lo2))
    return out


def point_at(path: list[list[float]], cum: list[float], s: float) -> tuple[float, float, int]:
    """누적 거리 s 지점의 보간 좌표와 그 점이 속한 구간 시작 인덱스."""
    s = min(max(s, 0.0), cum[-1])
    i = 0
    while i < len(cum) - 2 and cum[i + 1] < s:
        i += 1
    seg = cum[i + 1] - cum[i]
    f = 0.0 if seg == 0 else (s - cum[i]) / seg
    lat = path[i][0] + (path[i + 1][0] - path[i][0]) * f
    lon = path[i][1] + (path[i + 1][1] - path[i][1]) * f
    return lat, lon, i


def coverage(path: list[list[float]], other: list[list[float]], within_m: float = 25.0, step_m: float = 10.0) -> float:
    """path를 step_m 간격으로 짚은 점 중 other 선에서 within_m 안에 있는 비율(0–1). 수 km 범위 평면 근사."""
    import numpy as np
    import shapely

    if len(path) < 2 or len(other) < 2:
        return 0.0
    lat0 = path[0][0]
    kx, ky = 111_320.0 * math.cos(math.radians(lat0)), 111_320.0

    def xy(points):
        a = np.asarray(points, dtype=float)
        return np.column_stack(((a[:, 1] - path[0][1]) * kx, (a[:, 0] - lat0) * ky))

    line = shapely.LineString(xy(path))
    target = shapely.LineString(xy(other))
    samples = shapely.line_interpolate_point(line, np.arange(0.0, line.length + step_m / 2, step_m))
    return float((shapely.distance(samples, target) <= within_m).mean())


def path_length(path: list[list[float]]) -> float:
    return cumulative(path)[-1] if len(path) > 1 else 0.0


def route_match(course: list[list[float]], route: list[list[float]], within_m: float = 25.0) -> float:
    """내비 경로가 코스와 얼마나 같은지(0–1): 코스를 덮는 비율, 코스 밖으로 새지 않는 비율, 길이 비 중 가장 작은 값.

    한쪽만 보면 놓친다. 코스를 다 덮어도 중간에 다른 길로 돌아오면(뒤쪽 비율), 지름길로 일부를 건너뛰면(앞쪽 비율·길이 비)
    값이 떨어진다."""
    lc, lr = path_length(course), path_length(route)
    if lc == 0 or lr == 0:
        return 0.0
    return min(coverage(course, route, within_m), coverage(route, course, within_m), min(lc, lr) / max(lc, lr))


class SignalIndex:
    """신호 점 근접 조회. 점을 위경도 격자(약 110m)에 나눠 두고 반경이 걸치는 칸만 본다.

    합친 레이어가 2천 점 가까이라 선형 탐색이면 후보 24개 분석에 수백 ms가 든다.
    """

    CELL_DEG = 0.001

    def __init__(self, points: list[list[float]], attribution: list[str] | None = None):
        self.points = [(float(p[0]), float(p[1])) for p in points]
        self.attribution = attribution or []  # 응답 dataNotes에 붙일 출처 표기
        self._cells: dict[tuple[int, int], list[tuple[float, float]]] = {}
        for p in self.points:
            self._cells.setdefault(self._cell(*p), []).append(p)

    def _cell(self, lat: float, lon: float) -> tuple[int, int]:
        return math.floor(lat / self.CELL_DEG), math.floor(lon / self.CELL_DEG)

    def __len__(self) -> int:
        return len(self.points)

    def near(self, lat: float, lon: float, radius_m: float) -> bool:
        span_lat = math.ceil(radius_m / 111_000 / self.CELL_DEG)
        span_lon = math.ceil(radius_m / (111_000 * max(math.cos(math.radians(lat)), 0.01)) / self.CELL_DEG)
        ci, cj = self._cell(lat, lon)
        return any(
            haversine(lat, lon, plat, plon) <= radius_m
            for di in range(-span_lat, span_lat + 1)
            for dj in range(-span_lon, span_lon + 1)
            for plat, plon in self._cells.get((ci + di, cj + dj), ())
        )
