# 상용 지도·모빌리티 API 평가 (타랑, 2026-10-22)

대상: 카카오모빌리티 길찾기, 카카오 로컬·지도(로드뷰), 네이버 클라우드 Maps(Directions 5/15, 파노라마), TMAP API(경로·Road API).
목적: IDEA_타랑.md 3.2·4.3·4.4의 데이터 공백(신호, 비보호 좌회전, 차로 수, 제한속도, 어린이보호구역, 회전 안내)을 상용 API로 메울 수 있는지, 약관상 어떻게 써야 하는지.
방법: 공식 문서·약관 원문만 읽음. 키가 필요한 엔드포인트는 호출하지 않음(응답 형태는 문서 기준). 개발자 포럼(DevTalk) 답변은 약관 원문이 아니므로 따로 표시.

## 결론

1. **세 회사 모두 결과 데이터를 저장해 두고 쓰는 것을 금지한다.** 네이버는 "값을 리턴 받는 즉시 1회" 사용만 허용, 카카오는 "실시간 호출로만" 허용(길찾기 단기 캐싱도 금지라고 답변), TMAP은 "저장 후 24시간 이상 사용할 수 없습니다". 따라서 **상용 API로 신호·차로·제한속도 DB를 만들거나, 코스를 미리 계산해 쌓아 두는 설계는 안 된다.** 저장하는 도로 속성 DB는 OSM·공공데이터·직접 조사로만 만든다.
2. 대신 **코스를 요청하는 그 순간 후보 코스를 검증하는 용도로는 쓸 만하다.** 특히:
   - **네이버 Directions 5의 `guide.type = 8` "비보호 좌회전"** — 비보호 좌회전을 직접 표시하는 필드는 조사한 API 중 이것뿐이다.
   - **TMAP 경로 API의 `turnType`(211~217 횡단보도, 191 제한속도, 192 사고다발, 193 급커브), `roadType`(일반도로를 차로 수 구간으로 분류), `facilityType = 5` 교차로통과, `searchOption = 3` "교통최적+초보", `19` "어린이보호구역 회피"**, 그리고 **Road API `nearToRoad`의 `lane`·`speed`(제한속도), `matchToRoads`의 `speed`**.
3. **신호등(신호 교차로 여부)을 알려 주는 필드는 세 API 어디에도 없다.** "신호 있는 좌회전"은 여전히 공공데이터·현장 조사로 풀어야 한다. 네이버 type 8이 없다고 해서 신호 교차로라는 보장도 없다.
4. **로드뷰 미리보기:** 카카오 지도 Android SDK v2의 RoadView는 "로드뷰 사용은 별도의 협의가 필요합니다". 카카오 JavaScript SDK 로드뷰를 실시간 뷰어로 띄우는 것은 정책상 문제없다는 답변이 있다(캡처·저장·가공 배포는 안 됨). 네이버 파노라마는 Web Dynamic Map(JS)에만 있다. → 주차 중 미리보기는 **WebView 안의 카카오 JS 로드뷰**가 현실적이나, AAOS(Pleos)에서 WebView를 쓸 수 있는지는 미확인.
5. 비용: 시연(코스 약 100개)은 모두 무료 한도 안. 하루 1,000명 규모에서는 TMAP이 무료 한도(경로 1,000건/일)를 넘으므로 정액제 Lite(월 2,200,000원) 또는 종량제가 필요하다.

## API별 판정

| API | 우리에게 쓸모 있는 것 | 막는 것 / 주의 |
| --- | --- | --- |
| 네이버 Directions 5 | `guide.type` 8 = 비보호 좌회전, 2 좌회전, 3 우회전, 6 유턴, 21~34 로터리, 91~104 회전교차로식 교차로. `section.name`(도로명), `congestion`, `speed`(평균속도). 경유지 5개. 월 60,000건 무료(대표 계정) | Maps 약관 제7조 ⑨·⑪: 저장·DB화·가공 금지, 즉시 1회 사용만. 신호·차로·제한속도 필드 없음 |
| 네이버 Directions 15 | 경유지 15개 | 월 3,000건만 무료. 나머지는 Directions 5와 같음 |
| 네이버 파노라마 | Web Dynamic Map panorama 서브모듈(거리뷰) | Android SDK에는 파노라마 언급 없음. 저장 금지 동일 |
| TMAP 자동차 경로안내 `/tmap/routes` | `turnType`(12 좌회전, 13 우회전, 14 U턴, 16~19 시계방향 회전, 191 제한속도, 192 사고다발, 193 급커브, 211~217 횡단보도), `roadType`(5: 6·5차로, 6: 4·3차로, 7: 2차로, 8: 1차로, 9: 이면도로), `facilityType`(5 교차로통과, 6 철도건널목), `searchOption` 3 초보·19 어린이보호구역 회피, `traffic`. 경유지 5개. 1,000건/일 무료 | "저장 후 24시간 이상 사용할 수 없습니다". 비보호·신호 필드 없음. 출발=도착 같은 좌표면 빈 경로 |
| TMAP Road API `nearToRoad` | 좌표 하나 → 가장 가까운 링크의 `lane`(차로 1~9), `laneType`(편도/왕복), `speed`(링크 제한속도), `roadCategory`, `roadName`, `linkFacil` | 24시간 제한 동일. 과금 그룹(지오코딩/Road API) 미확인 |
| TMAP Road API `matchToRoads` | 좌표 최대 100개(500·1000 버전 별도) → 매칭 링크별 `speed`(제한속도), `roadCategory`, `linkId` | 1,000건/일 무료(500: 200건, 1000: 10건). 24시간 제한 |
| TMAP 다중 경유지 30/100 | 경유지 30·100개 | 응답에 `turnType`·`roadType` 없음(거리·시간·형상만). 무료 100·50건/일 → 검증용으론 부적합 |
| 카카오모빌리티 자동차 길찾기 / 다중 경유지 | `guides[].type`(1 좌회전, 2 우회전, 3 유턴, 70~81 회전교차로, 86 분기 후 합류), `roads[].traffic_speed`·`traffic_state`, `avoid`에 `schoolzone`·`uturn`. 경유지 5개(다중 경유지 30개). 10,000건/일·5,000건/일 무료 | 비보호·신호·차로·제한속도 없음. `road_details=true`의 응답 필드는 공개 문서에 없음. 카카오 운영정책 제5조 + "실시간 호출로만" 답변 → 캐싱 금지 |
| 카카오 로컬 | 카테고리 검색 `SC4` 학교, `PS3` 어린이집·유치원(반경 최대 20,000m). 일 100,000건 무료 | 좌표 저장 금지 답변("실시간 호출로만"). 어린이보호구역 자체는 카테고리 아님 → 공공 표준데이터가 정답 |
| 카카오 지도 JS SDK 로드뷰 | `RoadviewClient`로 좌표 근처 파노라마 찾기, 실시간 뷰어 임베드 "정책 상 이슈 없습니다"(DevTalk) | 캡처·저장·가공 배포 불가. JS 키 + 도메인 등록. 무료 쿼터는 계정의 첫 활성화 앱만 |
| 카카오 지도 Android SDK v2 로드뷰 | `RoadView`, `RoadViewRequest(LatLng)`, `lookAtPan/Tilt` | **"로드뷰 사용은 별도의 협의가 필요합니다."** 협의 없이는 사용 불가로 본다 |
| 카카오모빌리티 Navi SDK | 실시간 안내·안전운전 모드(레퍼런스 목록에 `KNSafety`, `KNLane`, `KNCits_TrafSignal` 클래스명 존재) | 계약·가격 문의 필요, AAOS 지원 언급 없음, 차량 내비와 겹침 → 이번 범위 밖 |
| V-World(국토부) | 평가하지 않음 | 미확인 |

## 상세 분석

### 1. 약관: 저장·캐싱 (가장 중요한 제약)

**네이버 클라우드 플랫폼 Maps 서비스 이용약관 (2025-03-20 시행)** — 원문 인용:
> 제7조 ⑨ '고객'은 '회사'의 사전 동의 없이 '본 서비스'의 결과 데이터를 본 약관에서 허용한 범위를 넘어서서 무단으로 복제, 저장, 가공, 배포하거나 제3자에게 제공해서는 안됩니다.
>
> 제7조 ⑪ '고객'은 '본 서비스'의 결과 데이터를 (해당 결과 데이터를 받은 즉시 자신의 서비스에서 사용하는 것이 아니라) 별도로 저장해서는 안되며, 따라서 그와 같은 결과 데이터를 별도로 저장하는 방식으로 데이터베이스화하여 이용해서도 안됩니다. … 즉, 모든 Maps API의 결과 데이터는 값을 리턴 받는 즉시 1회 자신의 서비스에서 사용하는 것만 허용되며, 그렇지 않고 그 결과 값들을 별도로 저장, DB화, 재사용하는 것은 금지됩니다.
>
> 제7조 ⑫ … API 및 SDK 자체를 그대로 혹은 리패키징하여 재판매할 수는 없습니다.

**TMAP API 약관 (tmapapi.tmapmobility.com/terms.html, "준수사항/제약사항")** — 원문 인용:
> TMAP Open API를 이용하여 얻어진 데이터는 저장 후 24시간 이상 사용할 수 없습니다. 동일한 서비스를 위하여 다수의 프로젝트를 생성하여 사용하는 경우에는 불법 사용으로 간주되어 사용이 중지될 수 있습니다.

**카카오디벨로퍼스 운영정책 제5조 (2026-04-20 시행)** — 원문 인용:
> 앱에서 사용자 환경을 개선하기 위한 목적 외 다른 목적으로 카카오에서 받은 데이터를 캐시하거나 캐시 후 최신 데이터로 유지하지 않는 행위
>
> 서비스 및 개발자센터를 이용하여 얻은 정보(예: 데이터, 비밀 키, 엑세스 토큰 등 포함)를 카카오의 사전 승낙 없이, 복사, 복제, 변경, 번역, 출판, 방송, 검색 엔진 또는 디렉터리에 입력 기타의 방법으로 사용하거나 이를 타인에게 제공하는 행위

운영정책 원문은 캐시를 "사용자 환경 개선 목적"이면 열어 두는 것처럼 읽히지만, DevTalk의 "Map" 계정 답변은 더 엄격하다(약관 원문 아님, 운영 해석으로 취급):
- 길찾기 `totalTime` 단기 캐싱 문의 → "실시간 이 아닌 임시 캐싱 등의 방법으로 API 호출 및 이용은 금지됩니다." (2026-09-14)
- 로컬 지오코딩 좌표 저장 문의 → "위경도 좌표 데이터의 저장 및 활용은 허용하지 않으며 실시간 호출로만 이용해 주셔야 합니다." (2026-09-04)
- 기존 저장분 → "기존 저장 데이터는 즉각 삭제해 주셔야 합니다", "어떠한 경우에도 실시간 호출이 아닌 형태로는 이용할 수 없습니다", 별도 제휴 검토도 하지 않음 (2026-08-26)

**우리 설계에 주는 뜻**
- 코스 사전 계산 + 상용 속성 저장: 세 곳 모두 불가(TMAP만 24시간 이내 재사용 가능).
- "이 교차로는 비보호" 같은 결과를 우리 DB에 쌓아 두는 것: 네이버 ⑨ "가공"·⑪ "DB화", 카카오 "복제·변경" 금지에 걸린다. **상용 API 결과로 3.7의 AI를 학습시키는 것도 같은 이유로 불가.**
- 결과를 받은 즉시 후보 코스 채점·선택에 쓰는 것: 네이버 "즉시 1회 사용", TMAP 24시간 이내라 허용 범위로 읽힌다. 카카오는 실시간 호출이면 허용.
- 상업적 이용: 네이버·TMAP은 유료 요금제를 전제로 하므로 상업 이용 자체는 금지되지 않는다. 카카오 운영정책은 "허가되지 않은 상업적 목적으로 … 판매, 거래, 재판매하거나 악용하는 행위"만 금지, 로컬 API를 유료 서비스 기능으로 쓰는 문의에는 지도 없이 단독 사용 가능하고 비즈월렛 종량제 외 방식은 별도 제휴 검토라고 답변.
- 차량 내(AAOS) 사용·타 지도/OSM과 결합: 세 약관 모두 명시 조항을 찾지 못했다. 카카오는 "당사가 제공하는 API를 타사의 API와 함께 사용하시는 것을 별도로 제한하지는 않습니다"라는 DevTalk 답변이 있다. 네이버·TMAP은 미확인.

### 2. 데이터 필드 대조 (우리가 필요한 것 → 어디서 나오나)

| 필요 | 네이버 Dir5 | TMAP 경로 | TMAP Road API | 카카오 길찾기 | 결론 |
| --- | --- | --- | --- | --- | --- |
| 회전 종류·순서 | `guide.type` | `turnType` | — | `guides[].type` | 셋 다 됨 |
| 비보호 좌회전 | **type 8** | 없음 | 없음 | 없음 | 네이버만. 정확도는 실측 필요 |
| 신호 교차로 | 없음 | 없음 | 없음 | 없음 | **없음** → 공공데이터·현장 |
| 횡단보도 지점 | 없음 | **turnType 211~217** | 없음 | 없음 | TMAP만 |
| 차로 수 | 없음 | `roadType` 구간(1 / 2 / 3·4 / 5·6차로) | **`lane`, `laneType`** (nearToRoad) | 없음 | TMAP |
| 제한속도 | 없음 | turnType 191은 "제한속도" 안내점일 뿐 값 없음 | **`speed`** (nearToRoad, matchToRoads) | 없음 | TMAP Road API |
| 어린이보호구역 | 없음 | 회피 옵션 19 | 없음 | 회피 `schoolzone` | 회피만 가능, 구역 경계는 공공 표준데이터 |
| 사고다발·급커브 | 없음 | turnType 192, 193 | 없음 | 없음 | TMAP |
| 교통 혼잡 | `congestion`, `speed` | `traffic` | — | `traffic_state`, `traffic_speed` | 셋 다(시간대 판단용) |

### 3. 루프 코스를 상용 API에 넣는 방법

상용 API는 우리가 만든 OSM 루프를 그대로 따라가 주지 않는다. 경유지를 찍어 경로를 유도하고 결과 형상이 우리 루프와 일치할 때만 안내 필드를 쓴다.
- 출발=도착 좌표면 TMAP은 거리 0의 빈 경로를 준다(문서의 "출/도착지 동일좌표 요청" 예시). 도착점을 출발점 직전 지점으로 둔다.
- 경유지 상한: 네이버 Dir5 5개(Dir15 15개), TMAP 경로 5개, 카카오 길찾기 5개(다중 경유지 30개). 2~3km 루프면 회전 지점 사이 링크 중간점 5개로 대부분 유도될 것으로 보이나 **미검증**.
- 일치 검사: 반환 `path`/LineString과 우리 루프의 거리(예: 하우스도르프 30m 이내)로 판정, 불일치 구간의 안내는 버린다.

### 4. 로드뷰 (주차 중 회전 지점 미리보기)

- 카카오 Android SDK v2: `RoadView`·`RoadViewRequest` API는 공개돼 있으나 가이드 첫머리가 "로드뷰 사용은 별도의 협의가 필요합니다." 협의 전에는 쓰지 않는다.
- 카카오 JS SDK: `RoadviewClient`·로드뷰 샘플이 공개돼 있고, 실시간 뷰어 임베드는 "정책 상 이슈 없습니다", 단 "로드뷰 이미지를 직접 캡쳐, 저장, 가공 등 문서를 생성하여 배포하는 케이스가 아니라면" (DevTalk 2026-08-12). → 회전 지점 좌표와 진입 방향(`pan`)으로 실시간 표시만, 스크린샷 저장·PT 자료 삽입은 피하거나 카카오에 문의.
- 네이버: 파노라마는 Web Dynamic Map 서브모듈. 같은 저장 금지.
- AAOS: Pleos 정책상 주차 중(`CarUxRestrictions`)에만 표시. WebView 사용 가능 여부와 JS 키 도메인 등록 방식(로컬 HTML이면 도메인이 없음 → 우리 서버 도메인에서 페이지 제공)은 미확인.

### 5. 쿼터·요금 (공식 페이지 기준)

| 상품 | 무료 | 초과 |
| --- | --- | --- |
| 네이버 Directions 5 | 월 60,000건(대표 계정 1개) | 단가가 정적 페이지에 표시되지 않음(미확인) |
| 네이버 Directions 15 | 월 3,000건 | 미확인 |
| 네이버 Dynamic Map | 월 6,000,000건 | — |
| TMAP 경로안내(자동차) | 1,000건/일 | Lite 10,000건/일(월 2,200,000원 정액) / Premium 11원/건 |
| TMAP Road API | 1,000건/일 | Lite 20,000건/일 / 22원/건 |
| TMAP 지오코딩 그룹 | 20,000건/일 | Lite 50,000건/일 / 0.55원/건 |
| TMAP 공통 | "사용한도 초과 시 자동으로 차단" | |
| 카카오 자동차 길찾기 | 10,000건/일 | 8원/건 |
| 카카오 다중 경유지 | 5,000건/일 | 16원/건 |
| 카카오 로컬 카테고리 검색 | 100,000건/일 | 2원/건 |
| 카카오 지도 SDK(JS/Android) | 300,000건/일, **계정의 첫 활성화 앱만** | 0.1원/건(비즈월렛 연결) |

**비용 추정** (가정: 코스 1회 생성 = 후보 3개 검증 × [네이버 Dir5 1건 + TMAP 경로 1건 + TMAP matchToRoads 1건]; 미리보기 로드뷰는 지도 SDK 쿼터로 무시 가능)
- 시연 100코스: 각 API 300건. 모두 무료 한도 안 → **0원**.
- 하루 1,000명(1인 1코스/일): 하루 각 3,000건.
  - 네이버: 월 90,000건 → 30,000건 초과분 과금(단가 미확인).
  - TMAP: 경로 3,000건/일 + Road API 3,000건/일 → 종량제면 월 약 (3,000×11 + 3,000×22)×30 ≈ 2,970,000원, Lite 정액 2,200,000원이 더 싸다(Lite 한도 경로 10,000·Road 20,000건/일 안).
  - 후보를 3개에서 1개+예비 1개로 줄이면 절반 이하. OSM·공공데이터로 1차 채점을 끝내고 상용 API는 최종 관문으로만 쓰는 이유다.
- 주행 날 다시 호출: 저장 금지 때문에 미리보기와 주행이 다른 날이면 재검증 호출이 한 번 더 든다(TMAP은 24시간 안이면 재사용).

## 권장 통합 설계

```
[앱, 주차 중] "오늘 코스" 요청
   │  (사용자 위치, 단계)
   ▼
[코스 서버]
 1) GraphHopper + OSM + 공공데이터(신호·보호구역·사고다발) + 팀 현장 라벨로 후보 N개 생성·1차 채점
    └ 저장되는 도로 속성은 전부 여기(우리 소유 데이터)
 2) 상위 2~3개만 상용 API로 실시간 검증  ← 결과는 메모리에서 즉시 사용
    - 네이버 Dir5: 좌회전마다 type 8(비보호) 여부 → 1~2단계 코스에서 비보호 발견 시 탈락
    - TMAP 경로(searchOption=0): turnType 211~217 횡단보도, 192/193, roadType 차로 구간
    - TMAP matchToRoads(코스 좌표 ≤100): 링크별 제한속도 → 1단계 고속 구간 탈락 + 주행 중 과속 판정용
 3) 통과한 코스 1개 반환 (형상·회전 지점은 우리 OSM 기반, 제한속도·횡단보도 등 TMAP 값은 "24시간 유효" 표시와 함께)
   ▼
[앱, 주차 중] 회전 지점 미리보기: WebView + 카카오 JS 로드뷰(실시간 표시만)
[앱, 주행 중] 코스와 TMAP 제한속도로 피드백. 24시간이 지나면 서버가 TMAP 유래 값 삭제
[복기] 이벤트("제한속도 초과 1건")만 남기고 원 제한속도 값은 남기지 않음  ← 해석 여지, 아래 미확인 2
```

API 역할:
- **네이버 Directions 5** → 비보호 좌회전 검출(유일). 저장 금지라 결과로 OSM 태그를 고치거나 학습 데이터를 만들지 않는다.
- **TMAP 경로 + Road API** → 횡단보도·사고다발·급커브·차로 구간·제한속도. 주행 피드백(3.4의 3번 제한속도)에 필요한 값을 주행 당일 받아 24시간 안에 쓴다.
- **TMAP `searchOption=3`(초보)·카카오 `avoid=schoolzone`** → 3.1 목표 경로 분해(집→회사)를 상용 API로 직접 하고, 초보용 대안 경로와 비교 표시. 분해 결과도 저장하지 않고 필요할 때 다시 계산.
- **카카오 JS 로드뷰** → 주차 중 회전 지점 미리보기. Android SDK 로드뷰는 카카오와 협의 후.
- **카카오 로컬 SC4/PS3** → 보조로만. 보호구역 판정의 정답은 공공 어린이보호구역 표준데이터(저장 가능 여부는 해당 데이터 이용조건 확인)에서.
- 상용 API 호출은 **전부 서버에서**. 앱은 우리 서버만 부른다(키 노출 방지, 쿼터 통제, 일치 검사·폐기 로직을 한 곳에).

검증 실험(키 발급 후 첫 작업): 4.4의 부산대 앞 후보 20개에 대해 네이버 type 8이 나온 교차로를 팀 현장 라벨(비보호 표지)과 대조 → 놓친 것·잘못 잡은 것 수. 이 숫자가 나오기 전에는 "네이버로 비보호를 판정한다"고 쓰지 않는다. 대조에 쓴 네이버 응답은 저장하지 않고, 대조 결과(일치/불일치 개수)만 남긴다.

## 사람이 만들 키·등록 체크리스트

공통: 키는 서버 환경변수(`.env`는 커밋 금지, 배포 서버 시크릿 저장소)에만. APK에는 카카오 JS 키(도메인 묶임) 또는 네이티브 앱 키(패키지+키 해시 묶임)만 들어갈 수 있다.

**A. 네이버 클라우드 플랫폼 (Directions 5)**
1. ncloud.com 가입(결제수단 등록 필요 여부는 콘솔에서 확인). 이 계정이 Maps의 "대표 계정"이어야 무료 이용량이 붙는다.
2. 콘솔 → AI·NAVER API > Application Services > **Maps** → Application 등록.
3. API 선택: **Directions 5** (필요 시 Directions 15). 파노라마를 쓸 경우에만 Web Dynamic Map + Web 서비스 URL.
4. Android SDK를 쓸 경우에만 Dynamic Map 선택 + Android 패키지명 `com.taraang.app`.
5. 발급된 Client ID / Client Secret → 서버 `NAVER_MAPS_CLIENT_ID`, `NAVER_MAPS_CLIENT_SECRET` (헤더 `x-ncp-apigw-api-key-id`, `x-ncp-apigw-api-key`).
6. 콘솔에서 이용 한도·초과 알림 설정.

**B. TMAP (SK open API)**
1. tmapapi.tmapmobility.com → openapi.sk.com 회원가입·로그인.
2. 앱(프로젝트) **하나만** 생성 (약관: 같은 서비스에 여러 프로젝트 생성 시 사용 중지 가능).
3. TMAP 상품 **Free** 사용하기 → appKey 발급.
4. appKey → 서버 `TMAP_APP_KEY` (헤더 `appKey`). 앱에 넣지 않는다.
5. 1,000명/일 규모로 가면 Lite(정액) 전환 검토.

**C. 카카오디벨로퍼스 (모빌리티 길찾기, 로컬, 지도 JS 로드뷰)**
1. developers.kakao.com → 앱 생성(앱 이름, 회사명, 카테고리 필수). **이 계정에서 처음으로 카카오맵을 활성화하는 앱**이어야 지도 무료 쿼터가 붙는다.
2. 앱 설정 → 카카오맵 → 사용 설정 **ON**.
3. 플랫폼 키 → **REST API 키**: 허용 IP에 코스 서버의 고정 출구 IP 등록 → 서버 `KAKAO_REST_API_KEY` (헤더 `Authorization: KakaoAK …`). 모빌리티 길찾기도 이 키를 쓴다.
4. 플랫폼 키 → **JavaScript 키**: JS SDK 도메인에 로드뷰 페이지를 제공할 우리 서버 도메인 등록. 이 키는 페이지에 노출되는 것이 정상이며 도메인으로 묶인다.
5. (로드뷰를 Android SDK로 하려면) 카카오와 로드뷰 협의 먼저. 협의되면 **네이티브 앱 키** + Android 앱 정보에 패키지명 `com.taraang.app` + 키 해시(디버그·릴리스 서명키 각각: `keytool -exportcert -alias <alias> -keystore <keystore> | openssl sha1 -binary | openssl base64`).
6. 무료 쿼터 초과·두 번째 앱부터는 비즈월렛 연결 + 유료 API 설정.

## 출처

- [네이버 Directions 5 API 문서](https://api.ncloud-docs.com/docs/ai-naver-mapsdirections-driving) — 2026-10-22 확인 (분기점 안내 코드 "8 비보호 좌회전")
- [네이버 클라우드 플랫폼 Maps 서비스 이용약관 PDF (2025-03-20 시행)](https://xv-ncloud.pstatic.net/images/provision/%5B%EB%AF%BC%EA%B0%84%5DMaps%EC%84%9C%EB%B9%84%EC%8A%A4%EC%9D%B4%EC%9A%A9%EC%95%BD%EA%B4%80_v0.4_(CLEAN)_1742433558704.pdf) — 2026-10-22 확인
- [네이버 클라우드 Maps 상품·요금](https://www.ncloud.com/product/applicationService/maps), [Maps 개요](https://guide.ncloud-docs.com/docs/application-maps-overview), [Maps 시나리오](https://guide.ncloud-docs.com/docs/maps-procedure), [Android 지도 SDK 시작](https://navermaps.github.io/android-map-sdk/guide-ko/1.html) — 2026-10-22 확인
- [TMAP 자동차 경로안내](https://tmapapi.tmapmobility.com/main.html#webservice/docs/tmapRouteDoc), [Road API matchToRoads](https://tmapapi.tmapmobility.com/main.html#webservice/docs/roadApi), [가까운 도로 찾기 nearToRoad](https://tmapapi.tmapmobility.com/main.html#webservice/docs/nearToRoad), [다중 경유지 30](https://tmapapi.tmapmobility.com/main.html#webservice/docs/tmapRouteSequential30) — 2026-10-22 확인
- [TMAP API 약관](https://tmapapi.tmapmobility.com/terms.html), [SK open API TMAP 요금](https://openapi.sk.com/products/calc?svcSeq=4&menuSeq=5) — 2026-10-22 확인
- [카카오모빌리티 길찾기](https://developers.kakaomobility.com/guide/navi-api/directions), [다중 경유지](https://developers.kakaomobility.com/guide/navi-api/waypoints), [코드표](https://developers.kakaomobility.com/guide/navi-api/reference), [시작하기](https://developers.kakaomobility.com/guide/navi-api/start), [가격](https://developers.kakaomobility.com/price/), [제휴용 다중 경유지(100개, road_details)](https://developers.kakaomobility.com/en/affiliate/navi-api/waypoints) — 2026-10-22 확인
- [카카오디벨로퍼스 운영정책 (2026-04-20 시행)](https://developers.kakao.com/terms/latest/ko/site-policies), [쿼터](https://developers.kakao.com/docs/latest/ko/getting-started/quota), [카카오맵 공통 가이드](https://developers.kakao.com/docs/ko/kakaomap/common), [로컬 API](https://developers.kakao.com/docs/ko/local/dev-guide) — 2026-10-22 확인
- [카카오 지도 Android v2 로드뷰 가이드](https://apis.map.kakao.com/android_v2/docs/api-guide/roadview/), [Android v2 시작하기](https://apis.map.kakao.com/android_v2/docs/getting-started/quickstart/), [JS 로드뷰 샘플](https://apis.map.kakao.com/web/sample/basicRoadview/) — 2026-10-22 확인
- DevTalk 운영 답변(약관 원문 아님): [길찾기 캐싱 금지](https://devtalk.kakao.com/t/api-totaltime-caching/151653), [좌표 저장 불가](https://devtalk.kakao.com/t/api/151497), [기존 저장분 삭제](https://devtalk.kakao.com/t/api/151343), [로드뷰 임베드](https://devtalk.kakao.com/t/pdf/151107), [영리 이용](https://devtalk.kakao.com/t/api/150986), [타사 지도와 함께 사용](https://devtalk.kakao.com/t/local-api-sdk/149943) — 2026-10-22 확인

## 미확인 사항 / 추가 조사 필요

1. **네이버 type 8의 실제 정확도.** 문서상 코드만 확인. 금정구 실측 대조 전에는 판정 근거로 쓰지 않는다.
2. **"결과로 판정한 이벤트"를 저장하는 것**(예: 복기에 "제한속도 초과 1건"을 남기는 것, 코스 통과/탈락 기록)이 네이버 ⑨ "가공"·TMAP 24시간 조항에 걸리는지. 원 값 없이 우리 판정만 남기는 쪽으로 설계했지만, 각 사에 서면 문의 권장(네이버 1:1 문의, skopenapi@sktelecom.com, 카카오 DevTalk).
3. 카카오 `road_details=true` 응답 필드 — 공개 문서에 없음. 제휴 API 영역(api.partners@kakaomobility.com).
4. TMAP `nearToRoad`의 과금 그룹(지오코딩 20,000건/일인지 Road API 1,000건/일인지).
5. 네이버 Directions 5 초과 단가 — 요금 페이지 정적 HTML에 값이 비어 있음.
6. 네이버·TMAP 결과를 타사 지도(카카오) 위에 표시하거나 OSM과 겹쳐 쓰는 것에 대한 명시 조항 — 찾지 못함.
7. 차량 내(AAOS) 사용 제한 조항 — 세 약관에서 찾지 못함(없다는 확인은 아님).
8. Pleos/AAOS에서 WebView 사용 가능 여부, 카카오 지도 Android SDK의 x86_64 에뮬레이터 지원 — 설명회 질문 목록(4.2)에 추가 권장.
9. 경유지 5개로 2~3km 루프를 원래 형상대로 유도할 수 있는지 — 키 발급 후 실험.
10. V-World, 국가 표준노드링크(차로 수·제한속도, 저장 가능한 공공 데이터일 가능성) — 이 문서에서 평가하지 않음. 저장 가능한 속성 DB의 본 소스이므로 도로망 담당 조사로 넘김.
11. 피칭 자료에 로드뷰·지도 결과 화면을 캡처해 넣는 것 — 카카오 답변은 "캡처, 저장, 가공 … 배포"를 허용 범위 밖으로 둠. 시연은 라이브로, 자료 삽입 전 문의.
12. SK open API 회원 이용약관 본문(JS로 로딩되어 읽지 못함) — TMAP 저장 조항은 TMAP API 약관 페이지 기준.
