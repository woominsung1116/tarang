"""번들 코스의 경유지 2개로 내비가 코스를 따라 도는지 확인한다. GraphHopper가 떠 있어야 한다.

출발지 → 경유지1 → 경유지2 → 출발지를 GraphHopper 기본 차량 경로로 이어 보고(내비 경로 탐색의 근사),
코스와의 일치도(geo.route_match: 코스를 덮는 비율, 코스 밖으로 새지 않는 비율, 길이 비 중 최소)를 잰다.
기준은 엔진과 같은 engine.NAV_MIN_MATCH(0.95).

사용: uv run python scripts/check_waypoints.py [코스 파일]
"""

import asyncio
import json
import sys
from pathlib import Path

from tarang_course.engine import NAV_MIN_MATCH
from tarang_course.geo import route_match
from tarang_course.graphhopper import GraphHopperClient

ROOT = Path(__file__).resolve().parents[1]
DEMO = ROOT.parent / "tarang-android" / "app" / "src" / "main" / "assets" / "courses" / "demo_pnu.json"
MIN_COVERAGE = NAV_MIN_MATCH


async def course_coverage(client: GraphHopperClient, origin: tuple[float, float], course: dict) -> float:
    points = [origin, *(tuple(w) for w in course["waypoints"]), origin]
    route = await client.route(points)
    nav_path = [[lat, lon] for lon, lat, *_ in route["points"]["coordinates"]]
    return route_match(course["path"], nav_path)


async def check(path: Path, gh: str = "http://localhost:8989") -> dict[str, float]:
    data = json.loads(path.read_text())
    origin = (data["origin"]["lat"], data["origin"]["lon"])
    client = GraphHopperClient(gh, timeout_s=10)
    try:
        return {c["id"]: await course_coverage(client, origin, c) for c in data["courses"]}
    finally:
        await client.aclose()


def main() -> None:
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEMO
    result = asyncio.run(check(path))
    for cid, cov in result.items():
        print(f"{cid:<36} {cov:5.0%} {'ok' if cov >= MIN_COVERAGE else 'LOW'}")
    if any(cov < MIN_COVERAGE for cov in result.values()):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
