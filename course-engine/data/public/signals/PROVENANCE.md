# 부산 신호등 공공데이터 출처

수집: 2026-10-22, `scripts/collect_signals.py download`(파일데이터) · `api`(OpenAPI). 원본은 `raw/`에 그대로 두고 손대지 않는다. 파일별 sha256·바이트·포털 파일명은 `raw/manifest.json`.
내려받기 경로는 공공데이터포털 파일데이터의 '다운로드' 버튼과 같다(로그인·서비스키 불필요). 같은 날 두 번 받아 sha256이 같음을 확인했다.

라이선스: 아래 파일데이터 7종과 OpenAPI 모두 포털 표기 "이용허락범위 제한 없음, 무료". OpenAPI는 개발·운영 자동승인, 개발계정 10,000건/일. 공공누리 유형 표기는 페이지에 없다. 출처 표시는 "부산광역시, 공공데이터포털(data.go.kr)"로 한다.

## 받은 데이터

| raw 파일 | 데이터셋 (제공: 부산광역시 교통정보서비스센터) | 포털 기준일 / 수정일 | 행 | 좌표 |
| --- | --- | --- | --- | --- |
| `busan_traffic_lights_shp.zip` | 부산광역시_신호등 현황(SHP) [15084253](https://www.data.go.kr/data/15084253/fileData.do) | 제목 20240605 / 수정 2025-08-26 | 2,391 | WGS84(EPSG:4326) 점 |
| `busan_signal_crosswalks_csv.csv` | 부산광역시 횡단보도 위치 정보(신호등 있는 횡단보도) [3072152](https://www.data.go.kr/data/3072152/fileData.do) | 2025-04-23 / 2025-08-26 | 3,815 | 위도·경도 십진수(WGS84로 간주) |
| `busan_signal_crosswalks_shp.zip` | 부산광역시_신호등 설치 횡단보도 위치정보(SHP) [15084252](https://www.data.go.kr/data/15084252/fileData.do) | 20250423 / 2025-08-28 | 3,812 | WGS84 점 |
| `busan_signal_controllers_csv.csv` | 부산광역시_교통신호제어기 현황 [15083380](https://www.data.go.kr/data/15083380/fileData.do) | 2026-02-01 / 2026-03-25, 연간 갱신(다음 2027-03-20) | 2,962 | 없음(도로명 주소만) |
| `busan_intersections_csv.csv` | 교통시설물관리시스템_교통안전시설물정보(교차로 정보) [15084050](https://www.data.go.kr/data/15084050/fileData.do) | 20260320 / 2026-03-20, 연간 | 2,149 | 위도·경도 |
| `busan_safety_signs_csv.csv` | 교통시설물관리시스템_교통안전시설물정보(안전표지 정보) [15084056](https://www.data.go.kr/data/15084056/fileData.do) | 20220630 / 2025-08-22 | 57,527 | 위도·경도 |
| `busan_road_arrows_csv.csv` | 교통시설물관리시스템_교통안전시설물정보(노면방향표시 정보) [15084053](https://www.data.go.kr/data/15084053/fileData.do) | 20220630 / 2025-08-22 | 21,868 | 위도·경도 |
| `api_20261022/page_001…028.json` | 부산광역시_신호등 현황 OpenAPI [3079345](https://www.data.go.kr/data/3079345/openapi.do), `GET https://apis.data.go.kr/6260000/BusanTrafficLightInfoService/getWarningLightInfo` (`numOfRows=100&resultType=json`, 서비스키 필요) | 등록 2020-04-07 / 수정 2025-08-22, 갱신 주기 표기 '실시간'. 행 확인일(`confirm_date`)은 전부 2023년 | 2,775 | 위도·경도 십진수 |

CSV는 모두 CP949 인코딩이다.

필드
- 신호등 현황 SHP: `mgrnu`(관리번호), `name`(제어방식: 전자/일반/가변신호 제어), `confirm_da`(확인일, 전부 2020-12-31), `address`, `sigungu`, `ins_place`(설치 장소명), `road`, `road_type`(교차로 1,814 / 단일로 522 / 가변차로 28 / 모뎀 13 / 빈 값 14), `ins_date`, `lng`, `lat`.
- 신호등 현황 OpenAPI: `mgrnu, road, ins_place, gubun, ins_date, road_type(교차로 2,146 / 단일로 615 / 가변차로 14), sigungu, address(시 이름 없음), lat, lng, confirm_date`. `gubun`(제어방식)·`ins_date`는 전 행 공백, `road`는 2,333행 공백. 페이지 원문은 받은 그대로 저장하고 sha256은 `raw/api_20261022/manifest.json`. 응답·파일에 서비스키는 없다(저장 전 검사).
- 신호 횡단보도 CSV: 순번, 행정구역(구·동·리), 지번, 교차로(장소명), 신호등존재유무(전부 '유'), 가로·세로 길이, 면적, 경도, 위도.
- 신호제어기 CSV: 관리번호, 구군별, 설치주소, 설치년도, 제어방식(전자신호 2,510 / 일반신호 452), 데이터기준일자.
- 교차로 정보 CSV: 관리번호(`6-020` 형식), 시군구명, 동명, 리명, 지번, 도로명, 구·동·리코드, 교차로명, 경도, 위도.
- 안전표지·노면방향표시 CSV: 위치(구·동·도로명·교차로명·경도·위도)만 있고 **표지 종류·화살표 종류 열이 없다.**

## 알려진 한계

- **"신호등 현황"은 신호등 한 개가 아니라 신호제어기 한 대의 위치다.** 이름(`name`)이 제어방식이고 `road_type`이 교차로/단일로다. 2,391개는 2020-12-31 확인분이다. SHP 안 파일 날짜는 2021-07-06이고, 포털 제목의 20240605는 등록 갱신일로 보인다.
- 좌표는 교차로 중심이 아니라 제어기함(보도 모서리) 위치로 보인다. OSM 신호 노드가 100m 안에 있는 교차로 제어기의 중앙 거리는 부산 43.5m, 금정구 28m다. 그래서 회전 지점 기준 30m 조회로는 놓치는 경우가 많다. 엔진에 쓰려면 가장 가까운 도로 교차 노드(약 60m 안)에 붙이는 단계가 필요하다.
- 2026-02-01 제어기 목록은 2,962행(관리번호 기준 2,951개)이다. 이 중 2,205개는 관리번호로 2020 좌표와 연결되고, **746개는 2020 이후 생긴 것으로 좌표가 없다.** 133개는 2020에만 있다(철거 또는 번호 변경). 금정구는 2026 목록 176대, 좌표 있는 2020분은 경계 안 124대다. 주소만 있는 746대에 좌표를 붙이려면 지오코딩이 필요하다(아래 '사람이 할 일'). OpenAPI를 더하면 2026 목록 2,951개 중 2,184개가 API 관리번호와 맞고, 2020·API 둘 다에 없는 것은 615개로 줄어든다(API의 번호 없는 409행 중 일부일 수 있으나 번호로는 맞출 수 없다).
- 교차로 정보(2,149개)는 시설물 관리용 기준 교차로라서 신호 유무 열이 없다. 2020 제어기 100m 안에 있는 비율은 74%다. 신호 교차로 목록으로 단정할 수 없어 `busan_signals.json`에는 넣지 않았다.
- 신호 횡단보도는 보행 신호다. 교차로 안의 횡단보도와 단일로 횡단보도가 섞여 있고, 행 단위로 구분할 수 없다.
- **비보호 좌회전: 공개 데이터를 찾지 못했다.** 안전표지 데이터에 표지 종류가 없어 '비보호좌회전' 표지(규제표지)를 골라낼 수 없다. 포털에서 '비보호'로 검색해도 관련 데이터셋이 없다.
- 전국신호등표준데이터([15028198](https://www.data.go.kr/data/15028198/standard.do))의 제공기관 77곳 중 부산 기관은 없다. 포털 그리드 다운로드는 5만 행으로 잘린다.

- **OpenAPI(2023 확인분)도 깨끗하지 않다.** 2,775행 중 104행은 서울·대전 등 부산 밖 좌표라 버렸다(같은 이름 구 강서·중·동·남구의 지오코딩 오류로 보임). 409행은 관리번호가 비어 있어 `busan_api:noid-<내용 해시 10자>`로 id를 붙였다. 15개 관리번호는 서로 다른 두 곳에 나와(번호 재사용) 뒤의 것을 `<번호>~2`로 두었다. 77개 좌표에 2~9개 제어기가 겹쳐 찍혀 있어(예: 강서구 명지 9대가 한 점) 주소 지오코딩 좌표가 섞인 것으로 보인다.
- 2020 SHP와 관리번호로 맞춘 2,093개 중 1,720개는 15m 안(대부분 0m), 373개는 15m 넘게 다르다(15–100m 120, 100m–1km 160, 1km 넘음 93). 1km 넘게 다른데 설치 장소명이 같은 것도 있어(예: 대변골프비치 앞 14km) 이동이 아니라 둘 중 하나의 좌표 오류다. 어느 쪽이 맞는지 근거는 약하다: 100m 넘게 다른 행에서 OSM 신호 노드가 60m 안에 있는 쪽이 API 24 / SHP 8. 지시대로 API 좌표를 쓴다.

## 사람이 할 일 (서비스키 필요, 코드로 우회하지 않음)

1. (끝남, 2026-10-22) 부산광역시_신호등 현황 OpenAPI 서비스키 발급과 수집. 키는 `course-engine/.env`의 `TARAANG_DATA_GO_KR_KEY`.
2. 2026 제어기 746대 좌표: 도로명 주소 지오코딩용 키가 필요하다. 행정안전부 도로명주소 API(business.juso.go.kr, 좌표제공 API 승인키) 또는 브이월드 지오코더(vworld.kr 인증키) 중 하나를 발급받는다.
3. 범위 밖이라 받지 않은 것: 경찰청_신호 개방 데이터([15159340](https://www.data.go.kr/data/15159340/openapi.do))는 인천·대전·대구만 제공하고 부산은 없다. 부산광역시_음향신호기 현황([15034067](https://www.data.go.kr/data/15034067/openapi.do))은 API 전용이라 키가 필요하다.

## 산출물

- `busan_signals.json`: 6,592개. 신호제어기 2,780(+ 신호 횡단보도 3,812).
  - 제어기는 OpenAPI 2,670행(`source: busan_api`, `id: busan_api:<관리번호>`)이 같은 관리번호의 2020 SHP 행을 대체한다. API에 번호가 없는 2020 행 245개 중 2026 제어기 목록에 있고 30m 안에 같은 kind의 API 점이 없는 110개만 `busan_signal_lights_2020`(`id: busan_ctl:<관리번호>`)으로 남겼다. 나머지 135개는 철거 또는 번호 없는 API 행과 같은 제어기로 보고 뺐다.
  - `kind`: `intersection` 2,144, `midblock` 617, `reversible_lane` 15, `unknown` 4, `pedestrian_crossing` 3,812. `road_type` 교차로→intersection, 단일로→midblock, 가변차로→reversible_lane, 그 밖→unknown.
  - 공통 필드 `{lat, lon, source, id, kind, address, place, in_geumjeong}`. 제어기는 `in_2026_controller_list`를 더 갖고, API 행은 `mgrnu`(빈 번호면 null), `sigungu`, `confirmDate`, `vs2020`(`same` ≤15m / `moved` / `new`), `dist2020M`, 2020 행은 `control`을 갖는다.
  - 횡단보도는 전과 같다(같은 좌표 0.1m 안 중복 53건 제외, 좌표 없는 행 3건).
- `busan_signals_api.json`: 위 파일의 OpenAPI 행 2,670개만(같은 필드).
- `geumjeong_signals.json`: 금정구 경계 안 348개(intersection 120, midblock 55, pedestrian_crossing 173). 2020 SHP만 쓸 때는 297개(intersection 73).
- `busan_intersection_controllers_latlon.json`: `kind == intersection`만 `[lat, lon]`으로 담았다. `data/signals_busan.json`과 같은 형식이다.
- `geumjeong_boundary.geojson`: OSM relation 4064527 경계(`south-korea-latest.osm.pbf`, ODbL), 약 66.7km².
- `coverage.json`: OSM 신호(`data/signals_busan.json`, 684 노드)와 비교한 결과, 2026 제어기 목록 대조, `api_vs_2020`(관리번호·거리 대조, 금정구 수, 빠진 2020 id 목록).

## 엔진 신호 레이어 `data/signals_busan_merged.json`

`scripts/build_signal_layer.py`가 위 `busan_signals.json`(OpenAPI 반영 판)과 OSM으로 만든 파생 데이터다(2026-10-22 재생성). 아래 숫자는 파일의 `stats`와 같다. 코스 서버의 기본 신호 레이어이며, 회전 지점 30m 안에 점이 있으면 그 회전을 "신호 확인(VERIFIED)"으로 본다.

- 입력: `busan_signals.json` 6,592행 중 차량 신호 `kind ∈ {intersection, reversible_lane}` 2,159행(2,144 + 15) + `data/signals_busan.json`(OSM `highway=traffic_signals` 684점) + `data/south-korea-latest.osm.pbf`의 부산 자동차 도로 교차점(선분 3개 이상 노드) 33,180개. 보행 신호(`pedestrian_crossing`, `midblock`)와 `unknown` 4,433행은 넣지 않는다.
- 붙이기: 제어기를 60m 안 가장 가까운 교차점으로 옮긴다. 1,532행 붙음(이동 거리 중앙값 27.9m), 627행은 60m 안에 교차점이 없어 버림. 같은 교차점에 붙은 제어기(좌표가 겹친 지오코딩 행 포함)는 한 점 → 1,454점.
- 합치기: OSM 점과 30m 안이면 OSM 좌표 한 점으로 두고 출처를 둘 다 남긴다. 결과 2,029점(OSM만 575, OSM+공공 109, 공공만 1,345).
- 이전 판(2020 SHP만, 1,794점)과 비교한 코스 효과는 엔진 README 6장.
- 점 필드: `lat, lon, sources(["osm"|"busan_public"]), ids(공공 관리번호), snapDistanceM`. 파일의 `stats`·`method`에 위 숫자가 그대로 있다.
- 출처 표시: 파일의 `attribution`("신호 데이터: 부산광역시, 공공데이터포털(data.go.kr)")이 응답 `dataNotes`에 붙는다. OSM 부분은 ODbL.
- 한계: OpenAPI 좌표와 2020 좌표가 15m 넘게 다른 제어기 373개(1km 넘게 93개)는 어느 쪽이 맞는지 확인하지 않았다. 가장 가까운 교차점에 붙이므로 교차점이 촘촘한 곳에서는 이웃 교차점에 붙을 수 있다.
