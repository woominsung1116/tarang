# 타랑 코스 엔진

초보 운전자 연습용 순환 코스를 만드는 서버. 출발지에서 GraphHopper `round_trip`으로 후보를 여러 개 만들고,
회전 안내를 운전 기술(우회전, 신호 좌회전, 신호 정보 없는 좌회전, 유턴, 회전교차로)로 분해해 단계 조건에 맞춰 고른다.

응답 형식과 API는 `../tarang-android/ARCHITECTURE.md` 4.1·4.2가 단일 출처다. 단계 정의는 `../IDEA_타랑.md` 3.2.

```
tarang_course/
  graphhopper.py  GraphHopper /route 클라이언트(타임아웃, 동시 요청 제한, 오류 2종 분류)
  analysis.py     instruction sign → 기술, 신호등 30m 조회, 경유지 3개
  levels.py       단계 1–4 조건과 위반 판정
  engine.py       후보 생성 → 채점 → 선택 → 4.1 JSON
  server.py       FastAPI: GET /v1/courses, GET /v1/route-skills, GET /healthz, 설정(Settings)
  signal_layer.py OSM 신호 + 부산 공공 신호제어기를 도로 교차점에 붙여 합치기, 레이어 읽기
  roads.py        경로를 금정구 OSM way에 붙여 차로 수·제한속도 요약(빠른 길·큰길 구간)
  zones.py        보호구역·사고다발지역 → 요청 지역 custom_model areas, 경로의 어린이보호구역 진입 지점
scripts/
  build_demo_courses.py  앱 번들 코스 assets/courses/demo_pnu.json
  build_replays.py       시연 리플레이 assets/replay/*.json
  build_signal_layer.py  합친 신호 레이어 data/signals_busan_merged.json
  build_road_layer.py    도로 속성 레이어 data/roads_geumjeong.json(way 선형 + 차로 수·제한속도)
  compare_signal_layers.py  신호 레이어별(--before), 도로 속성 반영 전후(--roads) 조건 충족 코스 수 비교(GraphHopper 필요)
  compare_zone_layers.py    구역 반영 전후 조건 충족 코스 수·보호구역 통과 비교(GraphHopper 필요)
tests/feedback_sim.py    앱 피드백 엔진의 파이썬 이식(리플레이 검증용)
generate_course.py       CLI(신호등 추출, 코스 확인)
osm_quality.py           시연 지역 OSM 태그 채움 비율 점검
```

## 1. GraphHopper 띄우기

`data/south-korea-latest.osm.pbf`(Geofabrik 한국 추출본)가 있어야 한다. 처음 실행하면 그래프를 만드느라 수 분 걸린다.

```sh
cd course-engine
docker run -d --name taraang-gh -p 127.0.0.1:8989:8989 -e JAVA_OPTS="-Xmx6g -Xms1g" \
  -v "$PWD/data:/data" -v "$PWD/graphhopper/config.yml:/graphhopper/config.yml:ro" \
  israelhikingmap/graphhopper@sha256:7371676550916f6c8c1e79b8e3732c606bc945ff781effc54f6ac7aad78c7460 \
  -c /graphhopper/config.yml
curl localhost:8989/health   # OK
```

- GraphHopper는 밖에 열지 않는다. 컨테이너 안에서는 `0.0.0.0`에 묶어야 포트 연결이 되므로, 호스트 쪽 공개를 `127.0.0.1`로만 한다. GraphHopper API는 인증이 없고 요청 하나로 무거운 계산을 시킬 수 있다.
- 이미지는 digest로 고정한다(2026-10-22 받은 `latest`). 올릴 때는 새 digest로 그래프를 다시 만들어 테스트한 뒤 바꾼다.
- 그래프는 마운트한 `data/default-gh`에 있어 컨테이너를 다시 만들면 몇 초 안에 뜬다(설정의 encoded values를 바꾸면 다시 import, 수 분).
- `round_trip`과 요청별 `custom_model`을 쓰므로 CH/LM 없이(flexible 모드) 돌린다(`graphhopper/config.yml`). 요청 하나가 서버를 오래 묶지 않게 `routing.max_visited_nodes: 100000`, `routing.non_ch.max_waypoint_distance: 50000`(m)으로 둔다. 실측 최대 방문 노드: 순환(부산 9곳 × 단계 1–4 × seed 24 + 10km 순환 8개, 구역 반영, 936요청) 12,772, 점 대 점(부산 북동 끝 ↔ 남서 끝, 직선 41.5km·경로 47.9km) 17,530. 한도를 낮춘 뒤에도 순환 실패 수(36, 경로 없음)가 같았다. 서버는 `/v1/route-skills`에 직선 40km 넘는 요청을 보내지 않는다.

## 2. 서버 실행

Python 3.12, `uv` 사용.

```sh
uv sync
uv run python -m tarang_course.server     # 127.0.0.1:8000
```

Android 에뮬레이터에서는 `http://10.0.2.2:8000`으로 접속한다(에뮬레이터의 10.0.2.2가 호스트 루프백이라 127.0.0.1 바인드로 된다). 같은 망의 다른 기기에서는 닿지 않는다.

배포: 서버는 루프백에만 두고 앞에 TLS 리버스 프록시(Caddy)를 둔다. IP별 요청 제한은 프록시에서 한다(Caddy는 `caddy-ratelimit` 모듈을 넣어 빌드, 예: IP당 분당 30회 `/v1/*`). 서버 안의 제한(아래 동작 정책)은 전체 부하 상한이지 IP별 제한이 아니다.

```
taraang.example.kr {
    rate_limit {
        zone per_ip { key {remote_host}  events 30  window 1m }
    }
    reverse_proxy 127.0.0.1:8000
}
```

```sh
curl localhost:8000/healthz
# {"status":"ok","graphhopper":"ok"}
curl "localhost:8000/v1/courses?lat=35.231&lon=129.084&level=1&count=3"
curl "localhost:8000/v1/courses?lat=35.231&lon=129.084&level=3&focus=LEFT_TURN_SIGNALIZED,U_TURN"
curl "localhost:8000/v1/route-skills?fromLat=35.231&fromLon=129.084&toLat=35.158&toLon=129.059"
```

설정은 `tarang_course/server.py`의 `Settings` 한곳에 있고 `TARAANG_<필드 대문자>` 환경변수로 바꾼다. 잘못된 값(형식, 범위, `nan`·`inf`)이면 시작할 때 바로 실패한다.

| 환경변수 | 기본값 | 뜻 |
| --- | --- | --- |
| `TARAANG_HOST` / `TARAANG_PORT` | `127.0.0.1` / `8000` | 바인드 주소. 외부 공개는 리버스 프록시로 |
| `TARAANG_NEGATIVE_TTL_S` | `10` | GraphHopper 탓 503을 키별로 기억하는 시간 |
| `TARAANG_MAX_INFLIGHT` | `4` | 동시에 만드는 서로 다른 (출발지, 단계) 수. 넘으면 바로 503 |
| `TARAANG_LIMIT_CONCURRENCY` / `TARAANG_KEEP_ALIVE_S` | `64` / `5` | uvicorn 동시 연결 상한(넘으면 503), keep-alive 유휴 시간 |
| `TARAANG_GH_URL` | `http://localhost:8989` | GraphHopper 주소(http/https만, `user:pass@` 거부). 로그에는 host:port만 남긴다 |
| `TARAANG_GH_TIMEOUT_S` | `3` | GraphHopper 요청 1개 타임아웃. 전체 상한보다 짧아야 한다 |
| `TARAANG_GH_MAX_CONCURRENCY` | `8` | GraphHopper 동시 요청 수 |
| `TARAANG_DEADLINE_S` | `6` | 코스 요청 전체 상한. 앱 타임아웃(8초)보다 짧게 |
| `TARAANG_NAV_DEADLINE_S` | `4` | 생성 시작부터 이 시간이 지나면 내비 경유지 확인을 멈춘다(3장 `waypoints`). 전체 상한보다 짧아야 한다 |
| `TARAANG_SEEDS` | `24` | 요청당 후보 수(round_trip seed 0..N-1, 1–64) |
| `TARAANG_CACHE_TTL_S` / `TARAANG_CACHE_MAX_ENTRIES` | `600` / `256` | 응답 캐시 유지 시간·최대 개수(LRU) |
| `TARAANG_SIGNALS_FILE` | `data/signals_busan_merged.json`(없으면 `data/signals_busan.json`) | 신호 교차로 좌표. 파일이 없으면 모든 회전이 "신호 정보 없음" |
| `TARAANG_ROADS_FILE` | `data/roads_geumjeong.json` | 금정구 차로 수·제한속도. 없으면 도로 조건을 보지 않고 경고 로그만 남긴다 |
| `TARAANG_ZONES_DIR` | `data/public/zones` | 부산 보호구역·사고다발지역 GeoJSON. 없으면 구역을 반영하지 않고 경고 로그만 남긴다 |
| `TARAANG_SCHOOL_ZONE_BUFFER_M` | `150` | 어린이·노인보호구역 시설 중심 원 반경(30–500). 300이면 부산대 앞 순환 코스가 막힌다 |

동작 정책
- 입력: 위도 33–39, 경도 124–132, level 1–4, count 1–5(기본 3). 어긋나거나, 모르는·중복 인자, 쿼리 200자 초과, 본문 1KB 초과면 400 `INVALID_ARGUMENT`. 없는 경로(404/405)도 같은 오류 형식이다.
- 출발지는 소수점 3자리(약 100m, 앱이 보내는 자리수와 같음)로 반올림한 뒤 생성·캐시·로그에 쓴다. 캐시 키는 출발지 + level이다. count와 상관없이 5개를 한 번 만들어 두고 count만큼 잘라 준다(대체 안내·출처는 잘라낸 코스 기준으로 다시 쓴다). 메모리 캐시, TTL·LRU. 오류는 캐시하지 않는다.
- 같은 키의 동시 요청은 생성 작업 하나를 함께 기다린다. 앱 재시도가 GraphHopper 요청을 불리지 않는다.
- GraphHopper 연결 실패·타임아웃으로 503을 준 키는 10초(`TARAANG_NEGATIVE_TTL_S`) 동안 기억해 바로 503으로 답한다. 앱 재시도·여러 사용자가 죽은 경로 엔진에 요청을 쌓지 않게. 성공만 길게(10분) 캐시한다. 우리 전체 상한(`TARAANG_DEADLINE_S`)을 넘긴 503은 붐빈 것이지 경로 엔진 고장이 아니라 기억하지 않는다.
- 분석 중 예외(GraphHopper 응답은 `graphhopper.valid_path` 형식 검사를 이미 통과)는 우리 버그다. 나머지 seed로 응답하되 기록하고, 후보가 하나도 없으면 500 `INTERNAL`. 앱은 503만 다시 시도하므로 버그가 재시도로 불어나지 않는다.
- 부하 상한: 생성 1건이 GraphHopper 요청 24–48개(+ 내비 경유지 확인 16–240개)이고 모든 생성이 GraphHopper 동시 요청 `TARAANG_GH_MAX_CONCURRENCY`(8)개를 나눠 쓴다. 서로 다른 키의 생성이 이미 `TARAANG_MAX_INFLIGHT`(4)개 돌고 있으면 새 키는 기다리지 않고 503 `UPSTREAM_UNAVAILABLE` "잠시 후 다시 시도해 주세요"(앱은 저장·번들 코스로 대체). 캐시 히트와 이미 도는 키에 합류하는 요청은 막지 않는다. uvicorn 동시 연결 64, keep-alive 5초.
  - 왜 4: 모의 GraphHopper(요청당 30ms, 동시 8, 내비 일치도가 늘 미달이라 확인 240개를 다 쓰는 최악, 2026-11-04) 실측. 마감이 없던 때는 상한 16에서 서로 다른 키 4개는 4.2초에 모두 200, 6개는 2개만 200, 16개는 16개 모두 503(전체 상한에 걸려 GraphHopper 요청 1,764개를 버림). 내비 확인 마감을 둔 뒤 상한 16이면 16개 모두 200이지만 round_trip이 GraphHopper를 나눠 써 내비 확인을 거의 못 하고(코스 80개 모두 `navMatch` null), 요청당 100ms면 round_trip만 5초라 전체 상한 직전이다. 상한 4면 16개 중 4개 200(4.0초, 요청당 100ms에도 4.0초)·12개 즉시 503이고 전체 상한에 걸려 버리는 생성이 없다. 계산상 요청당 100ms에서 구역 재생성까지 해도 round_trip은 4×48개 ÷ 초당 80개 = 2.4초다.
- 내비 확인 마감: 생성 시작부터 `TARAANG_NAV_DEADLINE_S`(4초)가 지나면 내비 경유지 확인을 멈추고, 확인을 끝내지 못한 코스는 `navMatch` null(통과로 본다, 3장)로 준다. 이미 만든 round_trip 후보를 전체 상한(503)으로 버리지 않게. 로그 `courses_generated`의 `nav_cut`.
- GraphHopper 연결 실패·타임아웃·5xx·깨진 응답으로 후보가 하나도 없으면 503 `UPSTREAM_UNAVAILABLE` "경로 엔진에 연결할 수 없습니다". 전체 상한을 넘기면 503 `UPSTREAM_UNAVAILABLE` "잠시 후 다시 시도해 주세요". seed 일부만 실패하면 나머지로 응답한다.
- 경로가 없거나(바다 위 등, GraphHopper 4xx) 거리 이상치만 남으면 404 `NO_COURSE`.
- 그 밖의 예외는 500 `INTERNAL`, 응답에 내부 정보(스택트레이스)를 넣지 않는다. 미들웨어 안에서 잡아 보안 헤더를 붙이고 `unhandled` JSON 로그 한 줄(스택트레이스는 `exc` 필드)로 남긴다.
- 서버는 재시도하지 않는다. 요청 하나가 seed 24개를 병렬로 보내 일부 실패를 흡수하고, 재시도는 앱(GET 2회, 지수 백오프)이 한다.
- API 문서 화면(/docs, /openapi.json)은 열지 않고 CORS를 허용하지 않는다. 응답에 `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`.
- 로그: 한 줄 JSON. 위치는 반올림한 값만 남기고 쿼리스트링은 남기지 않는다(uvicorn 접근 로그는 끔).
- 응답 시간(로컬 GraphHopper, 부산 9개 지점 × 단계 1–4, count 5, seed 24, 구역·도로 속성 반영, 2026-10-22): 캐시 미스 p50 158ms·p95 331ms·최대 380ms, 캐시 히트 p50 3.0ms·p95 16ms. 목표는 3초·100ms.

## 3. 코스 선택 규칙

| 단계 | 목표 거리 | 도로 | 보호구역·사고다발지역 | 조건 |
| --- | --- | --- | --- | --- |
| 1 출발·정지 | 2km | 고속도로·자동차전용도로·연결로 차단, 간선 0.2배, 제한속도 60 초과 0.1배 | 어린이·노인보호구역 0.05배, 사고다발지역 차단 | 신호 정보 없는 좌회전 0, 신호 좌회전 ≤2, 유턴 0, 회전교차로 0, 제한속도 50 초과 구간 0, 편도 3차로 이상 구간 0(금정구만) |
| 2 기술 하나 | 3km | 고속도로·자동차전용도로·연결로 차단, 제한속도 60 초과 0.3배 | 1단계와 같음 | 신호 좌회전 ≥2, 신호 정보 없는 좌회전 0, 유턴 0, 회전교차로 0 |
| 3 기술 조합 | 4.5km | 고속도로 차단, 자동차전용도로 0.2배, 간선 허용, 제한속도 70 초과 0.3배 | 셋 다 0.5배 | 기술 2종 이상, 신호 정보 없는 좌회전 0, 유턴 ≤1 |
| 4 목표 경로 | 6km | 고속도로 차단, 자동차전용도로 0.5배 | 반영 안 함 | 기술 2종 이상, 유턴 ≤2, 신호 정보 없는 좌회전 허용 |

- 회전 판정(GraphHopper instruction sign): -2/-3 좌회전, 2/3 우회전, -98/-8/8 유턴, 6 회전교차로. 약간 꺾기(±1), 차로 유지(±7)는 기술로 세지 않는다.
- 신호 유무: 회전 지점 30m 안에 신호 레이어(아래 6장) 점이 있으면 `VERIFIED`, 없으면 `UNKNOWN`. 좌회전이 `UNKNOWN`이면 `LEFT_TURN_UNKNOWN`(비보호 간주)이다.
- 거리 이상치(목표의 0.5배 미만, 1.5배 초과)와 같은 경로(seed만 다른 것)는 뺀다.
- 도로 속성(`roads.py`, 금정구만): GraphHopper 경로에는 OSM way id가 없어(넣으려면 그래프를 다시 만들어야 한다) 경로를 10m 간격으로 짚어 15m 안·진행 방향 ±30° 안의 가장 가까운 way에 붙인다. 방향 조건 때문에 큰길을 가로지르는 것은 그 큰길로 세지 않는다.
  - 1단계 조건: 관측 제한속도(OSM·표준노드링크) 50km/h 초과 구간, 왕복 기준 6차로 이상(편도 3차로, 일방통행은 차로 수 ×2) 구간이 없어야 한다. 30m 미만으로 스치는 곳과 출발·도착 150m(사용자가 고른 출발 도로)는 세지 않는다. 문장: `제한속도 50km/h보다 빠른 길이 1구간 있어요`, `편도 3차로 이상 큰길이 1구간 있어요`.
  - 편도 2차로(왕복 4차로 상당)는 보통 시내 주행이라 조건·경고 없이 `debug.road.stretches.MULTI_LANE_ROAD`에만 센다.
  - `source: default_5030`(이면도로 30·간선 50 어림값)은 ESTIMATED다. 빠른 길 판정에 쓰지 않고, `debug.road.speedEstimatedShare`로 따로 센다.
  - 도로 데이터가 없는 곳(금정구 밖)은 "모름"이라 위반으로 세지 않는다.
  - 코스 `debug.road`: `{"maxSpeedKmh":50,"maxSpeedConfidence":"VERIFIED","matchedShare":1.0,"speedVerifiedShare":0.76,"speedEstimatedShare":0.24,"stretches":{"FAST_ROAD":0,"WIDE_ROAD":1,"MULTI_LANE_ROAD":1}}`. 비율은 경로 길이 기준.
- 정렬: 조건 위반 합계가 적은 순, 같으면(1–3단계) 어린이보호구역 진입이 적은 순, 그다음 짧은 순. 조건을 모두 만족하는 코스가 count개보다 적으면 위반이 적은 코스로 채운다.
- 보호구역·사고다발지역(`zones.py`, `levels.zone_factors`)
  - 요청마다 출발지에서 목표 거리 0.75배 안의 구역을 모아 종류별로 합치고 10m로 단순화해 GraphHopper `custom_model.areas`로 보낸다(부산대 앞 1–3단계 8–24KB). 어린이·노인보호구역은 시설 좌표 중심 150m 원(16각형), 사고다발지역은 원문 폴리곤 중 2020년 이후.
  - 짝수 seed는 감점·차단 모두, 홀수 seed는 차단(사고다발지역)만 쓴다. 감점을 모든 seed에 걸면 후보가 한쪽으로 몰려 조건에 딱 맞는 코스가 23 → 12개로 준다(아래 6장 측정). 보호구역 회피는 위 정렬의 두 번째 기준으로 다시 반영된다.
  - 차단 구역 중 출발지 100m 안의 것은 뺀다(출발 도로를 못 찾는다). 그래도 구역 때문에 고를 코스가 없으면 구역 없이 한 번 더 만든다(`zones_relaxed` 로그). seed 일부가 GraphHopper 오류여도 다시 만들고, 모두 오류(경로 엔진 고장)면 다시 만들지 않는다.
  - 경로가 어린이보호구역 원에 들어가는 지점마다 `SCHOOL_ZONE` maneuver(`confidence: "ESTIMATED"`)를 넣고 `skillCounts`에 센다. 원을 나와 50m 안에 다시 들어가면 한 구역으로 본다. 단계의 "기술 2종 이상"에는 세지 않는다.
  - 경고 문장: `어린이보호구역을 지나요 — 시속 30 이하로 천천히`(여러 곳이면 `어린이보호구역 2곳을 지나요 — …`), `dataNotes`에 `어린이보호구역은 학교·유치원 등 시설 주변 150m로 어림한 범위예요`.
- 사용자 문장과 기계용 값을 나눈다. `warnings`·`dataNotes`는 초보 운전자에게 그대로 보이므로 쉬운 말로만 쓴다.
  - 신호등 정보 없는 좌회전은 단계가 허용해도 항상 맨 앞에: `신호등 정보가 없는 좌회전 1곳 — 출발 전에 지도에서 확인해 주세요`. 그다음 어린이보호구역 경고.
  - 그 밖의 조건 위반: `신호 있는 좌회전이 1곳뿐이에요`, `유턴이 1곳 있어요`, `연습할 기술 종류가 적은 코스예요` 등(`levels.user_message`)
  - 대체 선택이 섞이면 `dataNotes`에 `1단계 조건에 딱 맞는 코스가 없어 가장 가까운 코스를 골랐어요`
  - 단계·기준 값은 코스의 선택 필드 `debug`에만 둔다: `{"seed":3,"penalty":1,"violations":[{"kind":"max","skill":"LEFT_TURN_UNKNOWN","count":1,"limit":0}]}`. 4.1 계약에 필드를 더한 것이고 앱은 무시한다.
- `id`: `L{단계}-{위도}-{경도}-s{seed}-{경로 해시 8자리}`. 경로 해시는 소수 5자리로 반올림한 path의 sha1 앞 8자리라, 데이터·엔진이 바뀌어 같은 seed의 경로가 달라지면 id도 바뀐다(ARCHITECTURE.md 4.1).
- `waypoints`: 정확히 2개(NaviHelper `WaypointIndex`가 FIRST/SECOND뿐). 내비가 출발지 → 출발지를 제자리·지름길로 끝내지 않고 이 순환대로 돌게 고른다.
  - 왜: 코드 리뷰 실측에서 경유지 3개 중 가운데를 빼면 순환의 40–45%(L1-s3 1,356→795m), 고르게 1/3·2/3에 두어도 12–28%를 지름길로 건너뛰었다.
  - 후보(시험 순서): 연습 회전(좌회전·유턴·회전교차로, 모자라면 우회전) 중 순환을 가장 고르게 나누는 두 개의 30m 뒤 지점 → 잘 맞던 경로 거리 비율 쌍 8개(`analysis.FRACTION_PAIRS`) → 0.1 간격 격자. 코스당 16쌍까지.
  - 각 후보로 출발지 → 경유지1 → 경유지2 → 출발지를 GraphHopper 기본 차량 경로(내비 경로 탐색의 근사)로 이어 보고 일치도(`geo.route_match`)를 잰다: 코스를 25m 안에서 덮는 비율, 내비 경로가 코스 밖으로 새지 않는 비율, 길이 비 중 가장 작은 값. 0.95 이상이 나오면 멈춘다. `debug.navMatch`.
  - **0.95 미만인 코스는 뺀다.** 순위대로 count개씩 확인하고 3×count개까지만 본다(부하 상한). 하나도 통과하지 못하면 가장 잘 맞는 코스 1개를 `내비 안내가 코스와 다를 수 있어요 — 화면의 코스 선을 따라가 주세요` 경고와 함께 준다(빈 응답보다 낫다). 그래서 count보다 적게 나올 수 있다. GraphHopper가 답하지 않거나 내비 확인 마감(2장 `TARAANG_NAV_DEADLINE_S`)까지 확인을 끝내지 못하면 그때까지 가장 잘 맞은(없으면 회전 기준) 후보를 쓰고 `navMatch`는 null(통과로 본다).
  - 실측(부산 9곳 × 단계 1–3, count 5, 2026-10-23): 27칸 중 5개를 다 채운 칸 4, 통과 코스가 없어 경고 1개를 준 칸 6(남포 1·2단계 0.86, 부산대 정문 3단계 0.93 등), 나머지는 1–4개. 경유지 확인 GraphHopper 요청은 생성 1건당 16–240개, 생성 시간 0.17–1.3초. 격자를 37쌍 전부 시험해도 일치도가 올라가지 않아 16쌍으로 묶었다.
  - 번들(`demo_pnu.json`)은 seed 96·확인 32×count로 만들어 통과한 코스만 넣는다(단계마다 3개, 모두 0.96–1.0. seed 48·확인 16×count일 때는 2·3·1개였다). `scripts/check_waypoints.py`와 `tests/test_waypoints_live.py`(GraphHopper가 떠 있을 때)가 다시 확인한다.
- `focus`(`/v1/courses?focus=LEFT_TURN_SIGNALIZED,U_TURN`): 목표 경로에서 뽑은 기술. 정렬에서 단계 조건 위반 다음 기준으로 "빠진 focus 기술 수"가 적은 코스를 앞에 둔다. 단계 조건은 바꾸지 않는다(1단계에 유턴을 focus로 줘도 유턴 코스는 위반 코스다). 모르는 기술 이름은 400. 캐시 키에 들어간다.

## 3-1. 목표 경로 분해 `GET /v1/route-skills`

`fromLat, fromLon, toLat, toLon`(한국 범위, 소수 3자리로 반올림). 응답 `{"schemaVersion":1,"distanceM","skillCounts","maneuvers","warnings","dataNotes"}`(maneuvers는 4.1 형식).
- 경로는 GraphHopper 기본 차량 경로(단계 조건·구역 감점 없음)다. 사용자가 실제로 갈 길에 가깝게 본다. 분석(회전·신호·보호구역·도로 속성)은 코스와 같다.
- 두 점 직선 200m 미만이나 40km 초과는 400 `INVALID_ARGUMENT`. 경로가 없으면 404 `NO_COURSE`("두 지점 사이 경로를 찾을 수 없습니다"). 캐시·같은 키 합류·동시 생성 상한은 코스와 같다(우리 데이터라 캐시해도 된다).
- 앱은 결과의 `skillCounts`를 `/v1/courses`의 `focus`로 넘긴다.

## 3-2. 상용 API 확인은 따로(설계 메모)

나중에 상용 지도·교통 API(실시간 통제, 상용 신호 정보 등)로 코스를 확인하게 되면 `GET /v1/courses/{id}/live-check` 같은 별도 엔드포인트로 둔다. 응답은 `Cache-Control: no-store`이고 서버 캐시에도 넣지 않으며, 코스 JSON·번들·`debug`에 섞지 않는다. 상용 약관상 저장·재배포가 막힌 값이 우리 캐시·앱 번들로 새지 않게 하려는 것이다. 지금 코스 데이터는 모두 공개 데이터(OSM ODbL, 공공데이터포털)라 캐시한다.
- 거리와 횟수는 가설이다. 시험 주행으로 조정한다.

## 4. 테스트

```sh
uv run pytest -q
```

GraphHopper는 모킹한다(`httpx.MockTransport`, `tests/fixtures/gh_round_trip_pnu_s3.json`은 실제 응답에서 필요한 필드만 남긴 것). 실패 경로(GraphHopper 다운·타임아웃·5xx·깨진 응답, 잘못된 인자, 전체 상한 초과, 잘못된 설정)를 포함한다.

`tests/test_replays.py`는 번들 리플레이를 앱 피드백 엔진에 넣었을 때 의도한 문장·기록만 나오는지 확인한다. 엔진은 `tests/feedback_sim.py`에 앱 Kotlin 코드(`tarang-android/.../feedback/`)를 그대로 옮겨 두었다(ARCHITECTURE.md 5장: 급제동 1초 14/15km/h·끝 속도 6km/h 이상, 깜빡이 판정 40m·앞 회전 40m 미만 제외, 보호구역 속도는 지점 제한속도 없으면 30). 앱 규칙이 바뀌면 같이 바꾼다. 급제동 기준값 경계 사례도 이 시뮬레이터로 확인한다.

`tests/test_zones.py`는 보호구역 진입 지점, custom_model 구성(종류별 규칙·출발지 차단 구역 제외·4단계 미반영), seed별 모델 분배, 구역 없이 다시 만들기, 실제 부산 데이터 크기를 확인한다.

## 5. 앱 번들 자산 다시 만들기

GraphHopper가 떠 있어야 한다. 코스를 다시 만들면 리플레이도 다시 만든다(리플레이는 번들 코스의 `path`를 따라간다).

```sh
uv run python scripts/build_demo_courses.py   # assets/courses/demo_pnu.json
uv run python scripts/build_replays.py        # assets/replay/*.json + *.expected.json
uv run python scripts/check_waypoints.py      # 경유지 2개로 내비가 코스를 따라 도는지(≥80%)
uv run pytest -q tests/test_replays.py
```

데이터 수집부터 전부는 한 명령: `uv run python scripts/build_all.py [--collect]`(수집 → 레이어 → 번들 코스 → 리플레이 → 경유지 확인 → 테스트 → `data/MANIFEST.json`). `MANIFEST.json`에 OSM pbf의 sha256·데이터 시각, GraphHopper 이미지 digest·설정 해시, 서버가 읽는 파생 파일의 sha256이 남는다. 같은 pbf와 이미지로 다시 만들면 같은 결과가 나와야 한다.

`demo_pnu.json`: 부산대 정문 앞(35.2310, 129.0840)에서 1·2·3단계 코스 최대 3개씩(seed 96, 구역·도로 속성 반영, 내비 일치도 0.95 이상만. 지금 3·3·3개). 제목은 엔진 제목 앞에 "부산대 앞", 뒤에 거리를 붙인다.

리플레이마다 `<이름>.expected.json`(말하는 문장 타입 순서 + 기록 이벤트 7종 수)을 함께 쓴다. **앱 Kotlin 리플레이 테스트가 이 파일로 판정한다(단일 판정 기준).** 빌더는 시나리오가 약속한 결과(`Scenario.expect_*`)를 `tests/feedback_sim.py`(앱 엔진의 파이썬 이식, 튜닝 보조)로 확인하고, 약속이 깨지면 파일을 쓰지 않고 멈춘다. `tests/test_replays.py`는 기대 파일·리플레이·번들 코스 id가 서로 맞는지 확인한다.

리플레이(0.5초 간격, 속도는 넣지 않고 위치 간격으로 표현, 앱이 위치 변화로 추정). 앱은 발화 사이 20초를 두므로 사건을 직선 구간마다 하나씩 떨어뜨렸다. 실제 어린이보호구역(`SCHOOL_ZONE`)이 없는 코스를 먼저 고른다(리플레이 보호구역은 합성이라 코스 안내와 어긋나지 않게).

| 파일 | 코스 | 앱에서 나오는 것 |
| --- | --- | --- |
| `demo_l1_signal_miss.json` | 보호구역용 직선(≥380m) 뒤에 과속용 직선(≥340m)이 오는 가장 짧은 1단계 코스(현재 s11) | 말함: 어린이보호구역 예고 → 보호구역 속도 → 깜빡이 → 제한속도 초과. 기록: 깜빡이 누락 1, 깜빡이 OK 2(15m 붙은 회전 1곳은 판정 안 함), 보호구역 과속 1, 과속 1, 급제동 0 |
| `demo_l2_warning_brake.json` | 가장 짧은 2단계 코스(현재 s78) | 말함: 기술 예고 → 새 경고(TIRE_PRESSURE, 주행 55% 시점) → 급제동 반복. 기록: 급제동 3(45→20km/h, 6m/s² = 1초 21.6km/h, 끝 속도 20), 깜빡이 OK 5. 정확한 값은 `demo_l2_warning_brake.expected.json`. 정지는 급제동으로 세지 않는다 |

공통: 순항 40–45km/h, 회전 지점 ±12m 15km/h, 회전 전 감속 1.6m/s², 정지 직전 감속 0.9m/s², 출발 전·도착 정차와 중간 정지 2회(4초), `nextTurn`은 다음 maneuver까지 path 거리, 마지막 회전 뒤 `ARRIVE`.
- 1단계: 보호구역 직선 앞 신호 대기로 연습 기술 예고 구간이 보호구역 예고 뒤 20초 안에 지나가게 하고, 누락 회전 25m 앞(회전 ±20m 침묵 밖)에서 신호 대기해 깜빡이 안내가 나오게 한다.
- 보호구역은 합성 구간이다. 접근 중 `remainM`이 500부터 줄고(올림, 구역 앞에서 0이 되지 않게), 구역 안은 `remainM` 0 + 제한속도 30. 앱은 `remainM` 0인 동안만 구역 안으로 본다.

## 6. 데이터 출처와 라이선스

저장소에는 서버가 읽는 정규화·파생 파일(각 5MB 이하)과 출처 문서만 넣는다(`.gitignore`). 원본 다운로드(`raw/`), OSM pbf, GraphHopper 그래프는 넣지 않으며 아래 명령으로 다시 만든다.

| 서버가 읽는 파일 | 크기 | 다시 만들기 |
| --- | --- | --- |
| `data/signals_busan.json` | 18KB | `uv run --no-project --with osmium python generate_course.py signals data/south-korea-latest.osm.pbf` |
| `data/signals_busan_merged.json` | 214KB | `uv run --no-project --python 3.12 --with pyshp --with shapely --with osmium python -I scripts/collect_signals.py all`(API 단계는 서비스키 필요, `data/public/signals/PROVENANCE.md`) 뒤 `uv run --with osmium python scripts/build_signal_layer.py` |
| `data/public/zones/busan_{school_zones,elderly_zones,accident_hotspots}.geojson` | 2.0MB / 147KB / 4.2MB | `uv run --python 3.12 scripts/collect_zones.py all` |
| `data/roads_geumjeong.json` | 490KB | `data/public/roadnet/README.md`의 세 명령 뒤 `uv run --with osmium python scripts/build_road_layer.py` |

OSM pbf는 Geofabrik 한국 추출본(`data/south-korea-latest.osm.pbf`)을 받아 둔다. 위 전부를 차례로 다시 만들고 `data/MANIFEST.json`(pbf sha256, GraphHopper 이미지 digest, 파생 파일 sha256)을 쓰는 한 명령: `uv run python scripts/build_all.py [--collect]`.

- 도로망·신호등: © OpenStreetMap contributors, [ODbL 1.0](https://opendatacommons.org/licenses/odbl/). 코스 응답 `dataNotes`와 앱 화면에 출처를 표기해야 한다. Geofabrik 한국 추출본(2026-10-20).
- `data/signals_busan.json`: 위 추출본에서 부산 범위 `highway=traffic_signals` 노드 684개를 뽑은 파생 데이터(ODbL 동일 적용).
  다시 뽑기: `uv run --no-project --with osmium python generate_course.py signals data/south-korea-latest.osm.pbf`
- 신호 공공데이터: 부산광역시 교통정보서비스센터, 공공데이터포털(data.go.kr), 이용허락범위 제한 없음. 원문·필드·한계는 `data/public/signals/PROVENANCE.md`.
- `data/signals_busan_merged.json`(서버 기본 신호 레이어, 2026-10-22, 부산 신호등 OpenAPI 반영): OSM 신호 노드 + 공공 차량 신호제어기를 합친 2,029점. 숫자의 기준은 파일의 `stats`.
  - 공공 6,592행(OpenAPI 2,670 + 2020 SHP에만 있는 110 + 신호 횡단보도 3,812) 중 차량 신호(`intersection` 2,144 + `reversible_lane` 15)만 쓴다. 보행 신호(횡단보도 3,812, 단일로 617)와 `unknown` 4는 "신호 있는 좌회전" 근거가 아니라 뺀다.
  - 제어기 좌표는 교차로 중심이 아니라 보도 모서리 제어기함(일부는 주소 지오코딩)이다. 60m 안 가장 가까운 자동차 도로 교차점(선분 3개 이상, 부산 33,180개)에 붙였다: 1,532개 붙음(중앙 거리 27.9m), 627개는 60m 안에 교차점이 없어 버렸다. 같은 교차점에 붙은 제어기는 한 점(1,454점)이다.
  - OSM 점과 30m 안에 겹치면 한 점으로 합친다: OSM만 575, 둘 다 109, 공공만 1,345.
  - 효과(부산대 정문 + 부산 7곳 × 단계 1–3, count 5, seed 24, `scripts/compare_signal_layers.py`):
    - OSM 신호만 → 이 레이어(구역 미반영): 조건에 딱 맞는 코스 15 → 27개 / 112개, 신호 확인 좌회전 32% → 54%.
    - API 반영 전 합친 레이어(1,794점) → 이 레이어(서버와 같이 구역 반영, `--zones --before`): 조건에 딱 맞는 코스 18 → 21개 / 110개, 신호 확인 좌회전 39% → 43%. 부산대 정문은 1·2·3단계 0·0·1개로 그대로.
    - (이전 판 기록: 2020 SHP만 쓴 레이어는 seed 16에서 OSM만 대비 8 → 11개 / 112개, 27% → 44%.)
  - 다시 만들기: `uv run --with osmium python scripts/build_signal_layer.py`. 응답 `dataNotes`에 `신호 데이터: 부산광역시, 공공데이터포털(data.go.kr)`이 붙는다.
- 보호구역·사고다발지역: 공공데이터포털(data.go.kr) 전국어린이보호구역·노인장애인보호구역·교통사고다발지역 표준데이터, 이용허락범위 제한 없음. 원문·필드·한계는 `data/public/zones/PROVENANCE.md`. 응답 `dataNotes`에 출처 한 줄이 붙는다.
  - 부산: 어린이보호구역 793곳(제한속도 원문 없음 → 30 가정), 노인·장애인보호구역 58곳(원문 제한속도), 사고다발지역 950곳 중 2020년 이후 294곳(보행자·자전거 사고 다발지만 있다).
  - 효과(부산대 정문 + 부산 8곳 × 단계 1–3, count 5, seed 24, `scripts/compare_zone_layers.py`, 전은 구역 없이 같은 신호 레이어, API 반영 전 신호 레이어로 측정): 조건에 딱 맞는 코스 23 → 18개 / 125개(딱 맞는 코스가 있는 출발지·단계 9 → 10칸), 어린이보호구역을 지나는 코스 99 → 71개(1단계 30 → 19, 2단계 30 → 19, 3단계 39 → 33), 진입 지점 232 → 170곳. 응답 시간 차이는 수십 ms.
- 차로 수·제한속도: 국토교통부 국가교통정보센터 표준노드링크(2026-10-14), 공공데이터포털 [15025526](https://www.data.go.kr/data/15025526/fileData.do), 이용허락범위 제한 없음 + OSM(ODbL). 원문·매칭 방법·한계는 `data/public/roadnet/PROVENANCE.md`. 응답 `dataNotes`에 출처 한 줄(코스가 금정구 도로를 지날 때)이 붙는다.
  - `data/roads_geumjeong.json`: 금정구 OSM 자동차 도로 way 1,611개의 선형 + `osm_way_attributes_geumjeong.json` 속성(제한속도 출처 nodelink 516·osm 87·default_5030 979·none 29). 다시 만들기: `uv run --with osmium python scripts/build_road_layer.py`.
  - 효과(위 8곳 × 단계 1–3, 구역·새 신호 레이어 반영, `compare_signal_layers.py --zones --roads`): 조건에 딱 맞는 코스 21 → 17개 / 110개. 줄어든 4개는 모두 금정구 출발지 1단계(부산대 북쪽 2 → 0: 제한속도 80 구간·편도 3차로 이상, 온천장 2 → 0: 편도 3차로 이상·제한속도 60). 부산대 정문은 0·0·1개로 그대로이고 경로도 같다(1단계 코스의 금강로는 일방통행 2차로라 debug에만 남는다). 금정구 밖 출발지는 데이터가 없어 그대로.
- 경로 엔진: [GraphHopper](https://github.com/graphhopper/graphhopper) (Apache 2.0).

## 7. 알려진 한계

- **신호 데이터가 아직 부족하다.** 공공 신호제어기를 합쳐도 좌회전의 절반 가까이가 "신호 정보 없음"이다. OpenAPI 좌표 중 373개는 2020 좌표와 15m 넘게(93개는 1km 넘게) 다르고, 77개 좌표에는 제어기 2–9대가 겹쳐 있다(주소 지오코딩으로 보인다). 그래서 1–3단계는 조건을 모두 만족하는 코스가 적고, 대체 선택 + 경고로 응답한다(IDEA 4.4). 이 경고는 정상 동작이다.
- 제어기를 교차점에 붙이는 단계는 "가장 가까운 교차점"이라 큰 교차로나 교차점이 촘촘한 곳에서 이웃 교차점에 붙을 수 있다. 비보호 좌회전 표지 데이터는 공개된 것이 없다.
- 우회전·유턴·회전교차로도 근처 신호등 노드가 없으면 `confidence`가 `UNKNOWN`이다(신호 정보 유무 기준). 기술 분류 자체는 경로 기하에서 나온다.
- GraphHopper 안의 차로 수·제한속도 태그 채움 비율이 9–13%라 경로 탐색의 도로 선호(`max_speed`)는 일부 도로에만 작동한다. 표준노드링크 값은 코스 선택 단계(1단계 조건)에서만 쓰고, 금정구만 있다(부산 전체는 다음 단계).
- 이면도로 제한속도 30은 5030 정책 어림값이고, way 하나에 링크 하나의 값을 붙여 way 안에서 바뀌는 차로 수·속도는 뭉개진다(roadnet PROVENANCE).
- 보호구역 범위는 시설 중심 원으로 어림한 것이라 실제 구역(시설 주변 도로 구간)과 다르다. 운영 시간·구간별 제한속도(20km/h 등) 데이터가 없어 어린이보호구역은 모두 30으로 안내한다.
- 사고다발지역 표준데이터는 보행자·자전거 사고 다발지뿐이다. 차대차·법규위반 다발지역(TAAS 오픈API)은 서비스키가 필요해 아직 없다.
- 감점 배수(0.05, 0.5)와 seed 분배는 가설값이다. 시험 주행과 위 측정으로 조정한다.
- 합류(MERGE)는 아직 반영하지 않는다.
- 캐시는 프로세스 메모리라 서버를 재시작하면 비워지고, 여러 프로세스 간에 공유되지 않는다.
