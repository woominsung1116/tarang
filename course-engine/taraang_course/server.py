"""코스 서버. 계약: taraang-android/ARCHITECTURE.md 4.2.

실행: uv run python -m taraang_course.server   (설정은 TARAANG_* 환경변수, Settings 참고)
"""

import asyncio
import json
import logging
import os
import time
from collections import OrderedDict
from contextlib import asynccontextmanager
from dataclasses import MISSING, dataclass, field, fields
from pathlib import Path
from typing import Annotated, Any
from urllib.parse import urlsplit

from fastapi import FastAPI, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import engine
from .geo import SignalIndex, haversine
from .graphhopper import GraphHopperClient, UpstreamUnavailable
from .signal_layer import default_signals_file, load_signal_index
from .roads import ROAD_FILE, RoadLayer, load_road_layer
from .zones import DEFAULT_BUFFER_M, ZONES_DIR, ZoneLayer, load_zone_layer

log = logging.getLogger("taraang_course")

COURSE_PARAMS = {"lat", "lon", "level", "count", "focus"}
ROUTE_PARAMS = {"fromLat", "fromLon", "toLat", "toLon"}
# focus에 쓸 수 있는 기술(ARCHITECTURE.md 4.1 skill 값)
FOCUS_SKILLS = {
    "RIGHT_TURN", "LEFT_TURN_SIGNALIZED", "LEFT_TURN_UNKNOWN", "U_TURN", "ROUNDABOUT", "MERGE", "SCHOOL_ZONE",
    "UNDERGROUND_PARKING",
}
MAX_FOCUS_CHARS = 120  # 기술 8개를 모두 적어도 105자
MIN_ROUTE_M = 200  # 이보다 가까우면 분해할 경로가 없다
MAX_ROUTE_M = 40_000  # 직선거리. 부산 끝에서 끝 정도. GraphHopper non_ch.max_waypoint_distance(50km)보다 작게
MAX_QUERY_CHARS = 200  # 정상 요청은 60자 안팎
MAX_BODY_BYTES = 1024  # GET만 받으므로 본문이 필요 없다
ORIGIN_DECIMALS = 3  # 약 100m. 앱도 이 자리수로 보낸다(ARCHITECTURE.md 7장 19)


@dataclass(frozen=True)
class Settings:
    """서버 설정의 단일 출처. 환경변수 이름은 TARAANG_ + 필드명 대문자."""

    host: str = "127.0.0.1"  # 에뮬레이터는 10.0.2.2로 호스트 루프백에 닿는다. 외부 공개는 TLS 리버스 프록시(Caddy) 뒤에서만
    port: int = 8000
    gh_url: str = "http://localhost:8989"
    gh_timeout_s: float = 3.0
    gh_max_concurrency: int = 8
    deadline_s: float = 6.0  # 앱 타임아웃(8초)보다 짧게
    seeds: int = engine.DEFAULT_SEEDS
    cache_ttl_s: float = 600.0
    cache_max_entries: int = 256
    signals_file: Path = field(default_factory=default_signals_file)  # 합친 신호 레이어, 없으면 OSM 신호만
    zones_dir: Path = field(default_factory=lambda: ZONES_DIR)  # 보호구역·사고다발지역 GeoJSON, 없으면 구역 반영 안 함
    roads_file: Path = field(default_factory=lambda: ROAD_FILE)  # 차로 수·제한속도(금정구), 없으면 도로 조건을 보지 않음
    school_zone_buffer_m: float = float(DEFAULT_BUFFER_M)  # 시설 중심 원 반경. 300이면 부산대 앞 순환 코스가 막힌다
    # 동시에 만드는 서로 다른 (출발지, 단계) 수. 생성 1건이 GraphHopper 요청 24–48개라 넘치면 바로 503으로 돌려보낸다
    max_inflight: int = 16
    limit_concurrency: int = 64  # uvicorn 동시 연결 상한(넘으면 503)
    keep_alive_s: float = 5.0  # uvicorn keep-alive 유휴 연결 유지 시간
    # GraphHopper 탓 503은 키별로 잠깐 기억한다. 앱 재시도·여러 사용자가 죽은 경로 엔진에 요청을 쌓지 않게
    negative_ttl_s: float = 10.0

    def __post_init__(self):
        if not self.gh_url.startswith(("http://", "https://")):
            raise ValueError("TARAANG_GH_URL은 http:// 또는 https:// 로 시작해야 합니다")
        url = urlsplit(self.gh_url)
        if url.username is not None or url.password is not None or "@" in url.netloc:
            raise ValueError("TARAANG_GH_URL에 사용자 정보(user:pass@)를 넣지 마세요")
        if not url.hostname:
            raise ValueError("TARAANG_GH_URL에 호스트가 없습니다")
        positive = (
            "port", "gh_timeout_s", "gh_max_concurrency", "deadline_s", "seeds", "cache_ttl_s", "cache_max_entries",
            "max_inflight", "limit_concurrency", "keep_alive_s", "negative_ttl_s",
        )
        for name in positive:
            if getattr(self, name) <= 0:
                raise ValueError(f"TARAANG_{name.upper()}는 0보다 커야 합니다")
        if not 1 <= self.seeds <= 64:
            raise ValueError("TARAANG_SEEDS는 1–64")
        if not 30 <= self.school_zone_buffer_m <= 500:
            raise ValueError("TARAANG_SCHOOL_ZONE_BUFFER_M는 30–500")
        if self.gh_timeout_s >= self.deadline_s:
            raise ValueError("TARAANG_GH_TIMEOUT_S는 TARAANG_DEADLINE_S보다 짧아야 합니다")

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> "Settings":
        """잘못된 값이면 시작할 때 바로 실패한다(ValueError)."""
        env = os.environ if env is None else env
        values: dict[str, Any] = {}
        for f in fields(cls):
            raw = env.get(f"TARAANG_{f.name.upper()}")
            if raw is None:
                continue
            kind = Path if f.default is MISSING else type(f.default)
            try:
                values[f.name] = kind(raw)
            except ValueError as e:
                raise ValueError(f"TARAANG_{f.name.upper()} 값이 {kind.__name__}가 아닙니다: {raw!r}") from e
        return cls(**values)


class TTLCache:
    """요청 키 → 응답. 만료 시간 + 최대 개수(LRU)."""

    def __init__(self, ttl_s: float, max_entries: int, clock=time.monotonic):
        self.ttl_s = ttl_s
        self.max_entries = max_entries
        self.clock = clock
        self._data: OrderedDict[tuple, tuple[float, Any]] = OrderedDict()

    def get(self, key: tuple) -> Any | None:
        hit = self._data.get(key)
        if hit is None:
            return None
        expires, value = hit
        if expires < self.clock():
            del self._data[key]
            return None
        self._data.move_to_end(key)
        return value

    def put(self, key: tuple, value: Any) -> None:
        self._data[key] = (self.clock() + self.ttl_s, value)
        self._data.move_to_end(key)
        while len(self._data) > self.max_entries:
            self._data.popitem(last=False)


class JsonFormatter(logging.Formatter):
    """한 줄 JSON 로그. 위치는 호출하는 쪽에서 소수점 3자리로 줄여 넘긴다."""

    def format(self, record: logging.LogRecord) -> str:
        out = {"ts": round(record.created, 3), "severity": record.levelname, "event": record.getMessage()}
        out.update(getattr(record, "fields", {}))
        if record.exc_info:
            out["exc"] = self.formatException(record.exc_info)
        return json.dumps(out, ensure_ascii=False)


def setup_logging() -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger("taraang_course")
    root.handlers[:] = [handler]
    root.setLevel(logging.INFO)
    root.propagate = False
    # uvicorn 기본 접근 로그는 쿼리스트링(위치)을 그대로 남긴다. 아래 미들웨어 로그로 대신한다
    logging.getLogger("uvicorn.access").disabled = True


def error(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(status_code=status, content={"error": {"code": code, "message": message}})


def parse_focus(raw: str) -> frozenset[str] | None:
    """쉼표로 나눈 기술 이름. 모르는 이름이 있으면 None(400). 빈 값은 focus 없음."""
    names = [n.strip() for n in raw.split(",") if n.strip()]
    if any(n not in FOCUS_SKILLS for n in names):
        return None
    return frozenset(names)


def url_host(url: str) -> str:
    """로그용 host:port. 경로·쿼리·사용자 정보는 남기지 않는다."""
    parts = urlsplit(url)
    port = parts.port or (443 if parts.scheme == "https" else 80)
    return f"{parts.hostname}:{port}"


def load_signals(path: Path) -> SignalIndex:
    try:
        return load_signal_index(path)
    except FileNotFoundError:
        log.warning("signals_missing", extra={"fields": {"path": str(path)}})
        return SignalIndex([])


def load_zones(directory: Path, buffer_m: float) -> ZoneLayer | None:
    try:
        return load_zone_layer(directory, buffer_m)
    except FileNotFoundError:
        log.warning("zones_missing", extra={"fields": {"path": str(directory)}})
        return None


def load_roads(path: Path) -> RoadLayer | None:
    try:
        return load_road_layer(path)
    except FileNotFoundError:
        log.warning("roads_missing", extra={"fields": {"path": str(path)}})
        return None


def create_app(settings: Settings | None = None, client: GraphHopperClient | None = None) -> FastAPI:
    settings = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.client = client or GraphHopperClient(settings.gh_url, settings.gh_timeout_s, settings.gh_max_concurrency)
        app.state.signals = load_signals(settings.signals_file)
        app.state.zones = load_zones(settings.zones_dir, settings.school_zone_buffer_m)
        app.state.roads = load_roads(settings.roads_file)
        app.state.cache = TTLCache(settings.cache_ttl_s, settings.cache_max_entries)
        app.state.failures = TTLCache(settings.negative_ttl_s, settings.cache_max_entries)
        app.state.inflight = {}
        log.info(
            "startup",
            extra={
                "fields": {
                    "signals": len(app.state.signals),
                    "signals_file": settings.signals_file.name,
                    "zones": len(app.state.zones) if app.state.zones is not None else 0,
                    "school_zone_buffer_m": settings.school_zone_buffer_m,
                    "road_ways": len(app.state.roads) if app.state.roads is not None else 0,
                    "graphhopper": url_host(settings.gh_url),
                }
            },
        )
        yield
        await app.state.client.aclose()

    # 공개 API 문서 화면은 열지 않는다. 계약은 ARCHITECTURE.md가 단일 출처다
    app = FastAPI(title="타랑 코스 서버", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)

    @app.exception_handler(RequestValidationError)
    async def on_validation(_: Request, exc: RequestValidationError) -> JSONResponse:
        names = sorted({str(e["loc"][-1]) for e in exc.errors() if e.get("loc")})
        return error(400, "INVALID_ARGUMENT", f"잘못된 입력: {', '.join(names)}")

    @app.exception_handler(StarletteHTTPException)
    async def on_http(_: Request, exc: StarletteHTTPException) -> JSONResponse:
        return error(exc.status_code, "INVALID_ARGUMENT", "지원하지 않는 요청입니다")

    @app.exception_handler(Exception)
    async def on_unhandled(_: Request, exc: Exception) -> JSONResponse:
        log.error("unhandled", exc_info=exc)
        return error(500, "INTERNAL", "서버 오류")

    @app.middleware("http")
    async def guard_and_log(request: Request, call_next):
        started = time.perf_counter()
        length = request.headers.get("content-length") or "0"
        if len(request.url.query) > MAX_QUERY_CHARS or not length.isdigit() or int(length) > MAX_BODY_BYTES:
            response = error(400, "INVALID_ARGUMENT", "요청이 너무 큽니다")
        else:
            response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Cache-Control"] = "no-store"
        # 쿼리스트링(위치)은 남기지 않는다
        log.info(
            "request",
            extra={
                "fields": {
                    "method": request.method,
                    "path": request.url.path,
                    "status": response.status_code,
                    "ms": round((time.perf_counter() - started) * 1000, 1),
                }
            },
        )
        return response

    @app.get("/healthz")
    async def healthz(request: Request) -> dict[str, str]:
        ok = await request.app.state.client.healthy()
        return {"status": "ok", "graphhopper": "ok" if ok else "down"}

    @app.get("/v1/courses")
    async def courses(
        request: Request,
        lat: Annotated[float, Query(ge=33, le=39)],
        lon: Annotated[float, Query(ge=124, le=132)],
        level: Annotated[int, Query(ge=1, le=4)],
        count: Annotated[int, Query(ge=1, le=5)] = 3,
        focus: Annotated[str, Query(max_length=MAX_FOCUS_CHARS)] = "",
    ):
        if not _params_ok(request, COURSE_PARAMS):
            return error(400, "INVALID_ARGUMENT", "모르는 인자나 중복 인자가 있습니다")
        skills = parse_focus(focus)
        if skills is None:
            return error(400, "INVALID_ARGUMENT", "잘못된 입력: focus")
        # 생성·캐시·로그 모두 반올림한 출발지만 쓴다
        lat, lon = round(lat, ORIGIN_DECIMALS), round(lon, ORIGIN_DECIMALS)
        # count는 키에 넣지 않는다. 최대 개수(engine.MAX_COUNT)로 한 번 만들고 count만큼 잘라 준다
        key = ("courses", lat, lon, level, tuple(sorted(skills)))
        fields_ = {"lat": lat, "lon": lon, "level": level, "count": count, "focus": len(skills)}

        async def make() -> engine.Plan:
            state = request.app.state
            return await engine.plan(
                state.client, state.signals, lat, lon, level, engine.MAX_COUNT, settings.seeds,
                zones=state.zones, roads=state.roads, focus=skills,
            )

        result = await _serve(request.app.state, key, make, fields_)
        if isinstance(result, JSONResponse):
            return result
        return engine.render(result, count)

    @app.get("/v1/route-skills")
    async def route_skills(
        request: Request,
        fromLat: Annotated[float, Query(ge=33, le=39)],  # noqa: N803 (계약의 쿼리 이름)
        fromLon: Annotated[float, Query(ge=124, le=132)],  # noqa: N803
        toLat: Annotated[float, Query(ge=33, le=39)],  # noqa: N803
        toLon: Annotated[float, Query(ge=124, le=132)],  # noqa: N803
    ):
        if not _params_ok(request, ROUTE_PARAMS):
            return error(400, "INVALID_ARGUMENT", "모르는 인자나 중복 인자가 있습니다")
        origin = (round(fromLat, ORIGIN_DECIMALS), round(fromLon, ORIGIN_DECIMALS))
        destination = (round(toLat, ORIGIN_DECIMALS), round(toLon, ORIGIN_DECIMALS))
        gap = haversine(*origin, *destination)
        if gap < MIN_ROUTE_M:
            return error(400, "INVALID_ARGUMENT", "출발지와 도착지가 너무 가까워요")
        if gap > MAX_ROUTE_M:
            return error(400, "INVALID_ARGUMENT", "출발지와 도착지가 너무 멀어요(직선 40km 이내)")
        key = ("route", origin, destination)
        fields_ = {"from": list(origin), "to": list(destination)}

        async def make() -> dict:
            state = request.app.state
            return await engine.route_skills(state.client, state.signals, origin, destination, state.zones, state.roads)

        return await _serve(request.app.state, key, make, fields_)

    def _params_ok(request: Request, allowed: set[str]) -> bool:
        keys = [k for k, _ in request.query_params.multi_items()]
        return not (set(keys) - allowed) and len(keys) == len(set(keys))

    async def _serve(state, key: tuple, make, fields_: dict):
        """캐시 → 같은 키 합류 → 동시 생성 상한 → 생성. 오류는 4.2 오류 형식 응답으로 바꾼다."""
        cached = state.cache.get(key)
        if cached is not None:
            log.info("cache_hit", extra={"fields": {"kind": key[0], **fields_}})
            return cached
        if state.failures.get(key) is not None:
            log.info("negative_cache_hit", extra={"fields": {"kind": key[0], **fields_}})
            return error(503, "UPSTREAM_UNAVAILABLE", "경로 엔진에 연결할 수 없습니다")
        if key not in state.inflight and len(state.inflight) >= settings.max_inflight:
            log.warning("busy", extra={"fields": {"kind": key[0], **fields_, "inflight": len(state.inflight)}})
            return error(503, "UPSTREAM_UNAVAILABLE", "잠시 후 다시 시도해 주세요")
        try:
            return await asyncio.shield(_once(state, key, make))
        except (UpstreamUnavailable, TimeoutError) as e:
            log.warning("upstream_unavailable", extra={"fields": {"kind": key[0], **fields_, "reason": type(e).__name__}})
            state.failures.put(key, True)
            return error(503, "UPSTREAM_UNAVAILABLE", "경로 엔진에 연결할 수 없습니다")
        except engine.AnalysisFailed:
            # 우리 버그. 앱은 503만 다시 시도하므로 500으로 답해 재시도 폭주를 막는다(스택트레이스는 로그에만)
            log.error("analysis_failed", extra={"fields": {"kind": key[0], **fields_}})
            return error(500, "INTERNAL", "서버 오류")
        except engine.NoCourse:
            log.info("no_course", extra={"fields": {"kind": key[0], **fields_}})
            if key[0] == "route":
                return error(404, "NO_COURSE", "두 지점 사이 경로를 찾을 수 없습니다")
            return error(404, "NO_COURSE", "이 위치에서 만들 수 있는 코스가 없습니다")

    def _once(state, key: tuple, make) -> asyncio.Task:
        """같은 키의 동시 요청은 생성 작업 하나를 함께 기다린다. 앱 재시도가 GraphHopper 요청을 불리지 않게."""
        task = state.inflight.get(key)
        if task is None:
            task = asyncio.create_task(_make_and_cache(state, key, make))
            state.inflight[key] = task
            task.add_done_callback(lambda _: state.inflight.pop(key, None))
        return task

    async def _make_and_cache(state, key: tuple, make):
        result = await asyncio.wait_for(make(), timeout=settings.deadline_s)
        state.cache.put(key, result)
        return result

    return app


def main() -> None:
    import uvicorn

    settings = Settings.from_env()
    setup_logging()
    uvicorn.run(
        create_app(settings),
        host=settings.host,
        port=settings.port,
        access_log=False,
        limit_concurrency=settings.limit_concurrency,
        timeout_keep_alive=settings.keep_alive_s,
    )


setup_logging()
app = create_app()

if __name__ == "__main__":
    main()
