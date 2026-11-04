import json
from pathlib import Path

import pytest

FIXTURES = Path(__file__).parent / "fixtures"

# 정북 방향 직선 위 점 간격(약 100m)
STEP_DEG = 0.0009


def make_gh_path(signs: list[int], distance: float = 2000.0, streets: list[str] | None = None) -> dict:
    """회전 sign 목록으로 GraphHopper path 응답을 만든다. 회전 i는 path 점 i+1에 있다.

    path: 출발점(35.0, 129.0)에서 북쪽으로 100m 간격, 마지막에 출발점으로 돌아온다.
    """
    n = len(signs)
    coords = [[129.0, 35.0 + STEP_DEG * k] for k in range(n + 2)] + [[129.0, 35.0]]
    streets = streets or [f"길{i}" for i in range(n)]
    instructions = [{"sign": 0, "street_name": "출발로", "interval": [0, 1], "distance": 100.0}]
    for i, sign in enumerate(signs):
        instructions.append({"sign": sign, "street_name": streets[i], "interval": [i + 1, i + 2], "distance": 100.0})
    instructions.append({"sign": 4, "street_name": "", "interval": [n + 2, n + 2], "distance": 0.0})
    return {"distance": distance, "points": {"type": "LineString", "coordinates": coords}, "instructions": instructions}


def turn_point(i: int) -> tuple[float, float]:
    """make_gh_path의 회전 i 좌표 (lat, lon)."""
    return 35.0 + STEP_DEG * (i + 1), 129.0


@pytest.fixture
def pnu_gh_response() -> dict:
    """부산대 앞 round_trip seed 3 실제 응답(2026-10-22, 필요한 필드만)."""
    return json.loads((FIXTURES / "gh_round_trip_pnu_s3.json").read_text())
