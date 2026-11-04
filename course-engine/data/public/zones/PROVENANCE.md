# 보호구역·사고다발지역 데이터 출처

수집: 2026-10-22, `scripts/collect_zones.py download`. 원문은 `raw/<slug>/<YYYYMMDD>/`에 그대로(gzip) 두고 다시 쓰지 않는다. `manifest.json`의 `contentHash`는 원문 바이트, `canonicalHash`는 행 순서와 무관한 내용 해시다. 다음 수집에서 `canonicalHash`가 같으면 내용 변경 없음.

## 원문

세 데이터 모두 공공데이터포털 표준데이터다. 로그인·서비스키 없이 상세 페이지의 "파일 다운로드" 버튼과 같은 공개 엔드포인트(`/download/columList.json`, `/download/standard.json`)로 받았다. 포털 표기 이용허락범위는 **"제한 없음"**, 비용 무료다. 공공누리 유형 번호는 해당 페이지에 따로 적혀 있지 않다. 출처 표시는 "공공데이터포털(data.go.kr), <데이터명>"으로 한다.

| slug | 데이터 | 제공 | 포털 수정일 / 갱신주기 | 전국 행 | 부산 행 | 좌표 |
| --- | --- | --- | --- | --- | --- | --- |
| `child_zones` | 전국어린이보호구역표준데이터 [15012891](https://www.data.go.kr/data/15012891/standard.do) | 지방자치단체 227곳 병합(소관 경찰청·교육부) | 2026-09-22 / 반기 | 14,640 | 797 | 위경도(WGS84) 점 |
| `accident_hotspots` | 전국교통사고다발지역표준데이터 [15029185](https://www.data.go.kr/data/15029185/standard.do) | 한국도로교통공단(TAAS), 소관 경찰청 | 2025-09-25 / 연간 | 12,780 | 950 | 위경도 점 + 폴리곤(EPSG:5179) |
| `elderly_zones` | 전국노인장애인보호구역표준데이터 [15034532](https://www.data.go.kr/data/15034532/standard.do) | 지방자치단체 180곳 병합 | 2026-06-16 / 반기 | 4,184 | 58 | 위경도(WGS84) 점 |

필드:
- `child_zones`: 시설종류, 대상시설명, 도로명/지번주소, 위도, 경도, 관리기관명, 관할경찰서명, CCTV 설치여부·대수, 어린이보호구역지정여부(+수정일), 보호구역도로폭, 데이터기준일자, 제공기관코드·명. **제한속도·운영시간·구역 범위 필드 없음.**
- `accident_hotspots`: 사고지역관리번호, 사고연도, 사고유형구분, 위치코드, 시도시군구, 사고지역위치명, 사고건수, 사상자·사망자·중상자·경상자·부상신고자수, 위도, 경도, 사고다발지역폴리곤정보, 데이터기준일자.
- `elderly_zones`: 장소유형코드, 대상시설명, 시도명, 시군구명·코드, 주소, 위도, 경도, **제한속도**, 관리기관, 관할경찰서, CCTV, 보호구역도로폭, 데이터기준일자.

금정구 경계: OpenStreetMap relation [4064527](https://www.openstreetmap.org/relation/4064527) (금정구), ODbL, `data/south-korea-latest.osm.pbf`(Geofabrik)에서 추출. 다른 OSM 파일은 `ZONES_OSM`로 지정한다(OSM API `/api/0.6/relation/4064527/full`로 받은 것과 형상이 같음을 확인).

## 산출물 (모두 WGS84, EPSG:4326)

| 파일 | 부산 | 금정구 | 형상 |
| --- | --- | --- | --- |
| `busan_school_zones.geojson` / `geumjeong_school_zones.geojson` | 793 | 51 | 시설 좌표 중심 300m 원(`geometrySource: "buffer"`) |
| `busan_accident_hotspots.geojson` / `geumjeong_accident_hotspots.geojson` | 950 | 92 | 원문 폴리곤(`"dataset_polygon"`), 950건 모두 |
| `busan_elderly_zones.geojson` / `geumjeong_elderly_zones.geojson` | 58 | 1 | 시설 좌표 중심 300m 원 |
| `graphhopper_areas_geumjeong.json` | | 3개 id, 11KB | 종류별 union → 15m 단순화 |
| `boundary_geumjeong.geojson`, `build_report.json` | | | 경계, 건수·제외 사유 |

- 부산 선택: 주소·제공기관명이 "부산광역시"로 시작하고 좌표가 부산 범위 안인 행. 어린이보호구역은 좌표 범위 밖 2건, 같은 시설 중복행 2건을 뺐다.
- 금정구 파일: 금정구 경계와 **겹치는** 피처를 자르지 않고 통째로 담는다. 이웃 구 시설의 원이 경계를 넘어오는 경우가 포함된다(금정구 기관이 관리하는 어린이보호구역 44곳은 모두 경계 안 좌표).
- 어린이보호구역 `speedLimitKmh: 30`은 원문 값이 아니라 법정 기본값 가정이다(`speedLimitSource: "assumed_legal_default"`). `hours`는 원문에 없어 `null`.
- 사고다발지역 `id`는 `사고지역관리번호_해시(연도·유형·위치코드)`. 관리번호만으로는 유형 간 중복된다.
- GraphHopper 파일: id `school_zones`, `accident_hotspots`, `elderly_zones` → custom model에서 `in_school_zones` 등으로 쓴다. `accident_hotspots`에는 2020년 이후만 넣었다(`HOTSPOT_MIN_YEAR`). 로컬 GraphHopper 12.0에서 MultiPolygon 영역이 그대로 받아들여지는 것을 확인했다.

## 주의

1. **사고다발지역은 보행자·자전거 사고 다발지다.** 유형은 보행노인(부산 706), 자전거(119), 보행어린이(73), 스쿨존어린이(52) 4가지뿐이다. 차대차·법규위반 같은 운전자 관점의 다발지역은 이 표준데이터에 없다. 원은 반경 약 200m(대부분)·100m·300m다.
2. 사고다발지역은 2012~2024년 누적이다. 오래된 해의 지점은 도로가 바뀌었을 수 있다.
3. 보호구역 300m 원은 실제 구역보다 넓다. 실제 구역은 시설 주변 도로 구간이다. 부산대 앞(35.2310, 129.0840)에서 `in_school_zones`를 `multiply_by: "0"`으로 막으면 3km 순환 코스가 6개 seed 모두 "Connection between locations not found"로 실패한다. 150m로 줄이면(`ZONES_BUFFER_M=150`) 6/6 생성된다. 1~2단계 회피는 `multiply_by` 0.01~0.05 같은 감점 방식을 쓰거나 반경을 줄여야 한다. 사고다발지역만 막을 때는 300m 설정에서도 6/6 생성된다.
4. 노인·장애인보호구역은 부산 16개 구·군 중 11곳만 제출했다. 금정구 기관 제출분은 없고 금정구 파일의 1건은 이웃 구 시설이다.
5. 어린이보호구역 지정여부 필드는 부산 797행 중 37행만 "Y"이고 나머지는 비어 있다. 빈 값은 지정된 것으로 본다.

## 서비스키가 필요한 추가 소스 (사람이 할 일)

- **도로교통공단 TAAS 오픈API** (opendata.koroad.or.kr): 운전자 관점 다발지역이 여기 있다. 지자체별·법규위반별·결빙·이륜차·보행자·음주운전·화물차·연휴기간 교통사고 다발지역, 링크기반 교통사고 위험지역, 세부링크 도로위험지수. 절차: 사이트 회원가입 → 로그인 → "API 인증키 발급" 신청 → 받은 키를 `.env`의 `TAAS_API_KEY`처럼 커밋하지 않는 곳에 둔다. 이용조건은 키 발급 화면에서 확인할 것(미확인).
- **부산광역시_교통사고 다발지역 정보** OpenAPI [15164340](https://www.data.go.kr/data/15164340/openapi.do): 이용허락범위 제한 없음, 개발·운영 자동승인, 개발계정 1,000건/일. 절차: data.go.kr 로그인 → "활용신청" → 마이페이지의 일반 인증키(Decoding) 확인. 응답 필드(폴리곤 여부)는 키 발급 후 확인해야 한다.
- 실제 보호구역 구간(도로 선형)이나 탄력 운영시간은 부산 데이터로는 찾지 못했다. 경기도 광주시처럼 보호구역 경계 공간정보를 공개한 지자체 사례는 있다(15120717).

## 코스 서버에서 쓰는 방식 (`tarang_course/zones.py`)

- 읽는 파일: `busan_school_zones.geojson`, `busan_elderly_zones.geojson`(시설 좌표 `lat`·`lon`과 `speedLimitKmh`만), `busan_accident_hotspots.geojson`(폴리곤, `year ≥ 2020`인 294곳). 300m 원 도형은 쓰지 않고 시설 좌표에서 반경을 다시 그린다. `graphhopper_areas_geumjeong.json`은 금정구 확인용이고 서버는 쓰지 않는다.
- 반경: 기본 150m(`TARAANG_SCHOOL_ZONE_BUFFER_M`). 위 주의 3의 측정(300m 차단 시 부산대 앞 순환 실패) 때문이다.
- 요청마다 출발지에서 목표 거리 0.75배 안의 구역만 모아 종류별로 합치고 10m로 단순화해 custom model `areas`(`school_zones`, `elderly_zones`, `accident_hotspots`)로 보낸다. 단계별 배수는 엔진 README 3장.
- 경로가 어린이보호구역 원에 들어가는 지점을 코스의 `SCHOOL_ZONE` 지점으로 표시한다(`confidence: "ESTIMATED"`). 제한속도는 30 가정값이다.
- 출처 표시: 응답 `dataNotes`에 "보호구역·사고다발지역: 공공데이터포털(data.go.kr) 전국어린이보호구역·노인장애인보호구역·교통사고다발지역 표준데이터".
