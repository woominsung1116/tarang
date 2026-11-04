"""번들 코스의 경유지 2개로 내비(GraphHopper 기본 차량 경로로 근사)가 코스대로 도는지(일치도 ≥ 0.95). 경로 확인은 GraphHopper가 떠 있을 때만."""

import asyncio
import sys
from pathlib import Path

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import check_waypoints  # noqa: E402


def graphhopper_up() -> bool:
    try:
        return httpx.get("http://localhost:8989/health", timeout=2).status_code == 200
    except httpx.HTTPError:
        return False


@pytest.mark.skipif(not graphhopper_up(), reason="GraphHopper(localhost:8989)가 떠 있지 않음")
def test_demo_courses_are_followed_by_two_waypoint_navigation():
    result = asyncio.run(check_waypoints.check(check_waypoints.DEMO))
    assert {cid.split("-")[0] for cid in result} == {"L1", "L2", "L3"}  # 단계마다 1개 이상
    low = {cid: round(cov, 2) for cid, cov in result.items() if cov < check_waypoints.MIN_COVERAGE}
    assert not low, low


def test_demo_courses_have_exactly_two_waypoints_and_match():
    import json

    for c in json.loads(check_waypoints.DEMO.read_text())["courses"]:
        assert len(c["waypoints"]) == 2, c["id"]
        assert c["debug"]["navMatch"] >= check_waypoints.MIN_COVERAGE, c["id"]  # 번들에는 내비가 코스대로 도는 것만
