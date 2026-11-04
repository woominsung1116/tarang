"""GraphHopper /route 클라이언트. 실패를 두 종류로만 나눈다.

- UpstreamUnavailable: 연결 실패, 타임아웃, 5xx, 응답 형식 오류. 서버 쪽 문제.
- RouteNotFound: 4xx. 이 요청(출발지, seed)으로는 경로가 없다.
"""

import asyncio
from typing import Any

import httpx


class UpstreamUnavailable(Exception):
    pass


class RouteNotFound(Exception):
    pass


class GraphHopperClient:
    def __init__(
        self,
        base_url: str,
        timeout_s: float = 3.0,
        max_concurrency: int = 8,
        transport: httpx.AsyncBaseTransport | None = None,
    ):
        self._http = httpx.AsyncClient(base_url=base_url, timeout=timeout_s, transport=transport)
        # 요청 하나가 seed 여러 개를 동시에 보내므로 GraphHopper에 걸리는 동시 요청 수를 묶는다
        self._limit = asyncio.Semaphore(max_concurrency)

    async def aclose(self) -> None:
        await self._http.aclose()

    async def round_trip(
        self, lat: float, lon: float, distance_m: int, seed: int, custom_model: dict[str, Any]
    ) -> dict[str, Any]:
        """순환 경로 1개(GraphHopper path 객체)를 돌려준다."""
        return await self._route(
            {
                "points": [[lon, lat]],
                "algorithm": "round_trip",
                "round_trip.distance": distance_m,
                "round_trip.seed": seed,
                "custom_model": custom_model,
            }
        )

    async def route(self, points: list[tuple[float, float]], custom_model: dict[str, Any] | None = None) -> dict[str, Any]:
        """(lat, lon) 지점들을 차례로 지나는 경로 1개. 목표 경로 분해와 경유지 확인에 쓴다."""
        body: dict[str, Any] = {"points": [[lon, lat] for lat, lon in points]}
        if custom_model is not None:
            body["custom_model"] = custom_model
        return await self._route(body)

    async def _route(self, extra: dict[str, Any]) -> dict[str, Any]:
        body = {"profile": "car", "ch.disable": True, "points_encoded": False, "instructions": True, **extra}
        async with self._limit:
            try:
                resp = await self._http.post("/route", json=body)
            except httpx.HTTPError as e:
                raise UpstreamUnavailable(type(e).__name__) from e
        if resp.status_code >= 500:
            raise UpstreamUnavailable(f"status {resp.status_code}")
        if resp.status_code >= 400:
            raise RouteNotFound(_gh_message(resp))
        try:
            path = resp.json()["paths"][0]
            ok = valid_path(path)
        except (ValueError, KeyError, IndexError, TypeError):
            ok = False
        if not ok:
            raise UpstreamUnavailable("malformed response")
        return path

    async def healthy(self) -> bool:
        try:
            resp = await self._http.get("/health", timeout=2.0)
        except httpx.HTTPError:
            return False
        return resp.status_code == 200


def valid_path(path: Any) -> bool:
    """분석이 기대하는 모양인지 끝까지 본다. 여기서 걸러야 분석 중 예외를 우리 버그(500)로 볼 수 있다."""
    coords = path["points"]["coordinates"]
    if not isinstance(coords, list) or len(coords) < 2:
        return False
    if not all(isinstance(c, list) and len(c) >= 2 and all(isinstance(v, (int, float)) for v in c[:2]) for c in coords):
        return False
    if not isinstance(path.get("distance"), (int, float)) or not isinstance(path["instructions"], list):
        return False
    for ins in path["instructions"]:
        interval = ins.get("interval") if isinstance(ins, dict) else None
        if not (isinstance(ins, dict) and isinstance(ins.get("sign"), int) and isinstance(interval, list) and len(interval) == 2):
            return False
        if not all(isinstance(i, int) and 0 <= i < len(coords) for i in interval):
            return False
    return True


def _gh_message(resp: httpx.Response) -> str:
    try:
        return str(resp.json().get("message", ""))[:200]
    except ValueError:
        return f"status {resp.status_code}"
