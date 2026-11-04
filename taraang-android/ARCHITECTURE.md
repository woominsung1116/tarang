# 타랑 Android 앱 — 아키텍처와 계약

기획: `../IDEA_타랑.md`. 이 문서는 코드 구조, 레이어 간 계약, 데이터 형식, 오류 처리 정책의 단일 출처다.

## 1. 환경

| 항목 | 값 | 근거 |
| --- | --- | --- |
| JDK | 17 | Pleos Vehicle SDK Sample App 기준 |
| Gradle / AGP / Kotlin | 8.10.2 / 8.8.2 / 2.0.0 | 같은 기준 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 28 | 공모전 요건 minSdk 28 이상 |
| 실행 대상 | Pleos Connect AVD (Android 14, API 34, Automotive) | Pleos 에뮬레이터 가이드 |
| Pleos SDK | `ai.pleos.playground:Vehicle:2.3.2`, `ai.pleos.playground:NaviHelper:3.0.5` | 공개 Maven `https://nexus-playground.pleos.ai/repository/maven-releases/` |
| SDK 소스 | `../docs/pleos-sdk/src/` | 시그니처는 여기서 확인한다. 추측하지 않는다 |

UI는 Jetpack Compose + Material3, 상태는 ViewModel + StateFlow, 저장은 Room + DataStore, DI는 수동 `AppContainer`.

## 2. 패키지와 소유

```
com.taraang.app
├── core/            AppResult, AppError, Logger, Clock, Geo 유틸        (계약 — 수정 시 전 레이어 합의)
├── domain/model/    도메인 모델                                        (계약)
├── domain/repository/ 저장소 인터페이스                                (계약)
├── domain/signal/   차량·내비 신호 소스 인터페이스와 스냅샷              (계약)
├── feedback/        FeedbackEngine(순수 Kotlin), 규칙, 속도 추정, 판정     (core 담당)
├── data/            Room, DataStore, 코스 API 클라이언트, 저장소 구현       (core 담당)
├── vehicle/         Pleos Vehicle/NaviHelper 어댑터, 리플레이 신호 소스      (core 담당)
├── audio/           음성 피드백 재생(TTS + 알림음)                        (core 담당)
├── di/              AppContainer                                          (core 담당)
├── ui/              테마, 스플래시, 내비게이션, 화면, ViewModel            (ui 담당)
├── TaraangApp.kt    Application — AppContainer 생성                      (core 담당)
└── MainActivity.kt  단일 Activity, Compose 진입                          (ui 담당)
res/drawable 로고·아이콘, res/mipmap 런처 아이콘                           (brand 담당)
assets/courses, assets/replay                                              (server 담당)
```

규칙
- `ui`는 `domain`과 `di`만 안다. `data`·`vehicle`·`feedback` 구현을 직접 참조하지 않는다.
- `feedback`은 Android 의존성이 없다(JVM 단위 테스트 대상).
- Pleos SDK 타입은 `vehicle/` 밖으로 나가지 않는다. 어댑터가 도메인 타입으로 바꾼다.

## 3. 오류 처리 정책

- 레이어 경계는 예외 대신 `AppResult<T>`(Ok/Err)로 넘긴다. 예외는 경계에서 `AppError`로 변환한다.
- `CancellationException`은 삼키지 않는다(`runCatchingApp`가 다시 던진다).
- 차량 신호: 시작 시 capability를 확인해 신호별 `SignalStatus`(AVAILABLE / UNSUPPORTED / PERMISSION_DENIED / ERROR)를 만든다. 입력 신호가 없는 규칙은 끄고, UI에 "이 차에서는 확인 불가"를 보여준다. 신호 하나의 실패가 주행 세션 전체를 멈추지 않는다.
- 코스 API: 타임아웃 8초, GET 재시도 최대 2회(지수 백오프), 실패 시 마지막 성공 캐시 → 앱 번들 코스(assets) 순으로 대체하고 `Course.source`로 출처를 표시한다.
- 주행 이벤트는 발생 즉시 Room에 쓴다. 프로세스가 죽어도 진행 중 세션을 복구하거나 "중단됨"으로 닫는다.
- 사용자에게 보이는 오류 문구는 `AppError.userMessage()` 한 곳에서 만든다. 스택트레이스·내부 코드를 화면에 노출하지 않는다.
- 로그: `Logger`만 사용. 위치는 소수점 3자리(약 100m)로 줄여 기록하고 토큰·개인정보를 남기지 않는다.

## 4. 데이터 형식

### 4.1 코스 JSON (서버 응답, `assets/courses/*.json` 동일)

```json
{
  "schemaVersion": 1,
  "origin": {"lat": 35.2310, "lon": 129.0840},
  "generatedAt": "2026-10-22T08:00:00Z",
  "dataNotes": ["신호 정보가 없는 좌회전은 비보호로 간주했습니다"],
  "courses": [
    {
      "id": "L1-35.2310-129.0840-s3",
      "level": 1,
      "title": "부산대 앞 우회전 순환",
      "distanceM": 1356,
      "path": [[35.2310, 129.0840], [35.2312, 129.0843]],
      "waypoints": [[35.2321, 129.0851]],
      "maneuvers": [
        {"index": 0, "skill": "RIGHT_TURN", "street": "금정로", "lat": 35.23, "lon": 129.08,
         "distanceFromStartM": 120.5, "confidence": "VERIFIED"}
      ],
      "skillCounts": {"RIGHT_TURN": 3, "LEFT_TURN_UNKNOWN": 1},
      "warnings": ["신호 정보 없는 좌회전 1곳 — 출발 전 확인"]
    }
  ]
}
```

- `path`, `waypoints`는 `[lat, lon]` 배열.
- `skill` 값: `RIGHT_TURN, LEFT_TURN_SIGNALIZED, LEFT_TURN_UNKNOWN, U_TURN, ROUNDABOUT, MERGE, SCHOOL_ZONE, UNDERGROUND_PARKING`.
- `confidence`: 그 지점의 **신호등 데이터** 근거. `VERIFIED`(30m 안에 신호 노드 있음) / `ESTIMATED`(추정) / `UNKNOWN`(정보 없음). 난이도에서 UNKNOWN을 어려운 쪽으로 간주하는 것은 **좌회전·유턴에만** 적용한다(우회전은 신호 유무와 무관하게 기술 자체로 본다).
- `waypoints`는 NaviHelper `requestRouteWithWaypoints`에 넘길 **최대 2개** 지점(NaviHelper 3.0.5의 `WaypointIndex`가 FIRST/SECOND뿐). 서버가 순환 코스를 내비가 그대로 따르도록 고른다(연습 회전 지점 사이를 강제하는 위치). 마지막 목적지는 `origin`.
- `id`는 같은 출발지·단계·seed라도 경로가 다르면 달라야 한다(경로 해시 포함). 앱은 주행 시작 시 코스 전체를 세션에 함께 저장하므로 나중에 같은 id가 다른 경로로 바뀌어도 기록은 원래 코스를 보여준다.

### 4.2 서버 API

- `GET /v1/courses?lat={lat}&lon={lon}&level={1..4}&count={1..5}` → 4.1 형식. 입력 검증: 한국 범위(위도 33–39, 경도 124–132), level 1–4, count 1–5(선택, 기본 3). 알 수 없는 경로(404/405)도 같은 오류 형식에 `INVALID_ARGUMENT`.
- `GET /v1/courses`의 선택 파라미터 `focus=LEFT_TURN_SIGNALIZED,U_TURN,...`: 목표 경로에서 뽑은 기술을 우선 포함하는 코스를 고른다(추가 파라미터, 하위 호환).
- `GET /v1/route-skills?fromLat&fromLon&toLat&toLon` → `{"schemaVersion":1,"distanceM":..., "skillCounts":{"LEFT_TURN_SIGNALIZED":2,...}, "maneuvers":[...4.1과 같은 형식], "warnings":[...], "dataNotes":[...]}`. 목표 경로 분해(IDEA 3.1). 같은 입력 검증·오류 형식.
- `GET /healthz` → `{"status":"ok","graphhopper":"ok|down"}`
- 오류 응답: `{"error":{"code":"INVALID_ARGUMENT|NO_COURSE|UPSTREAM_UNAVAILABLE|INTERNAL","message":"..."}}`, HTTP 400/404/503/500.

### 4.3 리플레이 주행 로그 (`assets/replay/*.json`)

에뮬레이터 시연용. 실제 주행 신호 대신 이 로그를 재생한다.

```json
{
  "schemaVersion": 1,
  "courseId": "L1-35.2310-129.0840-s3",
  "title": "시연: 깜빡이 1회 누락 + 어린이보호구역",
  "tickMs": 500,
  "frames": [
    {"t": 0, "lat": 35.2310, "lon": 129.0840, "heading": 90.0, "speedLimitKmh": 50,
     "turnSignal": "OFF", "brake": false, "warnings": [],
     "nextTurn": {"type": "RIGHT", "distanceM": 120.0},
     "caution": [{"type": "SCHOOL_ZONE", "remainM": 300}]}
  ]
}
```

- `t`는 시작 기준 ms. `turnSignal`: OFF/LEFT/RIGHT. `warnings`: `WarningType` 이름 배열. `nextTurn.type`: LEFT/RIGHT/UTURN/STRAIGHT/ROUNDABOUT/ARRIVE. `caution.type`: SCHOOL_ZONE/ACCIDENT_PRONE_AREA/SPEED_BUMP/OTHER.
- 속도는 넣지 않는다. 앱이 위치 변화로 추정한다(실차와 같은 경로를 탄다).
- 어린이보호구역 **안**은 `caution`에 `{"type":"SCHOOL_ZONE","remainM":0}`이 있는 프레임이다(`remainM > 0`은 접근 중). 실차 NaviHelper도 같은 규칙으로 해석한다.

## 5. 피드백 규칙 (IDEA 3.4, 3.6)

| 타입 | 우선순위 | 판정 | 입력 |
| --- | --- | --- | --- |
| NEW_WARNING | 1 | 출발 시점 대비 새로 켜진 경고 | Safety telltales, Tire |
| SCHOOL_ZONE_AHEAD | 2 | 어린이보호구역 300m 이내 진입 예고(1회) | NaviHelper caution SCHOOL_ZONE |
| SCHOOL_ZONE_SPEED | 2 | 보호구역 안에서 추정속도 > 그 지점 제한속도(onCurrentRoadSpeedLimit, 없으면 30km/h)가 3초 지속 | caution + 제한속도 + 속도 추정 |
| SPEED_LIMIT_SUSTAINED | 3 | 추정속도 > 제한속도+10이 5초 지속 | onCurrentRoadSpeedLimit + 속도 추정 |
| TURN_SIGNAL_MISSING | 4 | 다음 회전 50m 이내인데 방향지시등 OFF 또는 반대 방향(회전교차로 진출 제외) | TBT + TurnSignal |
| SKILL_PREVIEW | 5 | 오늘 연습할 기술 지점 150m 전 | 코스 maneuver + 위치 |
| HARD_BRAKE_REPEAT | 6 | 급제동이 한 주행에 3회째 | Brake + 속도 추정 |

- **급제동(HARD_BRAKE) 정의**: 브레이크 중 1초 감속이 시작 속도 30km/h 이하면 ≥14km/h, 초과면 ≥15km/h이고, 감속 후 속도가 6km/h 이상일 때 1건(5km/h 이하로 서면 정상 정지로 보고 세지 않는다). 한국교통안전공단 eTAS 위험운전행동 기준의 택시 값을 승용차에 준용(승용차 공식 기준 없음). 급제동 판정에는 평활화하지 않은 0.5초 창 속도를 쓴다(평활 속도는 1초 감속을 30–40% 깎아 기준에 닿지 못함). 실차에서는 위치 갱신 주기·잡음에 따라 오탐 가능성이 있어 시험 주행으로 확인한다.
- **SKILL_PREVIEW 대상**: 다음 maneuver가 아니라 **다음 "연습 기술" maneuver**를 예고한다(연습 대상이 아닌 회전에 가려 놓치지 않게). 연습 기술 = 좌회전·유턴·회전교차로·합류·지하주차장. 우회전은 코스의 기술이 우회전뿐일 때만 포함(우회전 예고가 20초 간격 규칙으로 바로 뒤 좌회전 예고를 막지 않게). 어린이보호구역은 SCHOOL_ZONE_AHEAD가 이미 예고하므로 제외. 단일 출처: `SkillPreviewRule.practiceSkills()`.
- **기록용 판정**: 회전 지점 **40m** 앞에서 방향지시등이 맞는 방향이면 TURN_SIGNAL_OK, 아니면 TURN_SIGNAL_MISSED. 법 기준은 교차로 **가장자리** 30m 전이고, 코스의 회전 지점은 교차로 중심 노드이므로 교차로 반폭(약 10m)을 더한다. 앞 회전과 40m 미만으로 붙은 회전과 회전교차로 진출은 판정하지 않는다.
- 발화 제한: 한 번에 하나, 최소 간격 20초(우선순위 1 제외), 한 주행 최대 12회, 같은 타입은 60초 쿨다운. 회전 중(회전 지점 ±20m)에는 말하지 않는다.
- 임계값은 모두 `FeedbackConfig` 한 곳에 두고, 각 값 옆 주석에 근거를 적는다.
- 근거와 성격(`hyundai/docs/research/FACT_CHECK_20261022.md`):
  - 법령 근거: 신호 시기 30m(도로교통법 시행령 별표 2, 2023-10-17 개정), 어린이보호구역 "시속 30km 이내"이며 구간·시간대별로 20km/h나 야간 50km/h도 있음(도로교통법 제12조, 시행규칙 제9조) → 고정 30 대신 지점 제한속도 우선.
  - 공단 기준 준용: 급제동(eTAS 택시 값).
  - **코칭 가설값**(법·공단 근거 없음, 초보용으로 eTAS 과속 +20보다 엄격하게 잡음): 제한속도+10이 5초, 보호구역 3초 지속, 예고 거리 150/300m, 발화 간격. 시험 주행으로 조정한다.

## 7. 엔지니어링 체크리스트 (모든 담당 공통, 완료 조건)

구현을 끝냈다고 보고하기 전에 자기 범위에서 아래를 모두 점검한다. 해당 없으면 "해당 없음"과 이유를 보고한다.

| # | 영역 | 이 프로젝트에서의 기준 |
| --- | --- | --- |
| 1 | 서비스 로직 분리 | 판정·선택·대체 규칙은 domain/feedback/서비스 클래스에만. UI·DAO·어댑터에 규칙 금지 |
| 2 | 레이어·의존 방향 | ui → domain ← data/vehicle/feedback. 순환 의존 금지 |
| 3 | 인터페이스·주입 | 외부 자원(시계, 네트워크, SDK, 파일)은 주입받아 테스트에서 교체 가능 |
| 4 | 설정값 분리 | 임계값·URL·타임아웃은 Config/상수 한곳 |
| 5 | 예외처리 | 3장 정책. 경계에서 AppError로 변환, CancellationException 재전파, 빈 catch 금지 |
| 6 | 입력 검증 | 사용자 입력·API 응답·SDK 값·assets JSON 모두 검증. 잘못된 값은 거부하거나 안전한 기본값 + 로그 |
| 7 | 타임아웃·재시도·대체 | 모든 외부 호출에 타임아웃. 재시도는 멱등 요청과 일시적 오류만 |
| 8 | 부분 실패 허용 | 기능 하나의 실패가 화면·세션 전체를 죽이지 않음 |
| 9 | null·경계값 | 빈 목록, 0, 음수, NaN, 범위 밖 좌표, 아주 긴 문자열 |
| 10 | 동시성 | 메인 스레드에서 I/O 금지, 공유 상태는 단일 스코프/뮤텍스, 중복 시작 방지 |
| 11 | 생명주기 | 화면 회전·프로세스 종료·백그라운드 전환 시 동작 정의, 리스너·TTS·SDK 해제 |
| 12 | 상태 관리 | 단일 출처 StateFlow, 상태 전이를 명시(주행: Idle→Starting→Running→Stopping→Finished/Failed) |
| 13 | 데이터 무결성 | 관련 쓰기는 트랜잭션, 세션 종료·이벤트 저장은 멱등 |
| 14 | 마이그레이션 | Room version 명시, 스키마 내보내기, destructive fallback 금지(사용자 기록 보존). 변경 시 Migration 추가 규칙 주석 |
| 15 | 캐시 | 캐시 코스에 저장 시각과 유효기간(24시간), 오래된 캐시는 "저장된 코스" 표시, 서버 캐시는 TTL·크기 제한 |
| 16 | API 설계 | 4.2 형식·오류 형식, `/v1` 하위 호환(필드 추가만 허용), 앱은 모르는 필드 무시 |
| 17 | 외부 연동 격리 | Pleos 타입은 vehicle/ 밖으로 안 나감. 지원 여부(capability) 먼저 확인 |
| 18 | 보안 | 평문 HTTP는 10.0.2.2/localhost만, 비밀값 하드코딩 금지, 서버 입력 검증·요청 크기 제한·CORS 미허용, 서버 URL 설정값 검증(http/https만) |
| 19 | 개인정보 최소화 | 서버로 보내는 출발지 좌표는 소수점 3자리(약 100m)로 반올림. 위치·주행 기록은 기기 안에만. 로그는 LatLng.redact() |
| 20 | 로깅 | Logger만 사용, 태그 일관, 실패 경로는 원인과 함께 1회 기록(반복 스팸 금지) |
| 21 | 성능·자원 | 주행 틱은 가볍게(할당 최소), 지도 타일·이미지 메모리, 리플레이는 스트리밍 또는 한 번만 파싱, 서버 응답 목표 3초 이내(캐시 히트 100ms) |
| 22 | 장애 대응 | 서버 다운 → 번들 코스 + 안내, SDK 미연결 → 시연 모드 안내, `/healthz`가 GraphHopper 상태 포함 |
| 23 | 테스트 | 핵심 규칙·대체 순서·오류 변환·검증·마이그레이션 경로는 테스트 필수. 실패 경로 테스트 포함 |
| 24 | 가독성 | 작은 함수, 의도가 드러나는 이름, 주석은 "왜"만, 죽은 코드 금지 |
| 25 | 문서화 | 설계 결정과 이유를 README/KDoc에 짧게 |
| 26 | 로딩·빈·오류 화면 | 모든 비동기 화면에 세 상태와 재시도 |
| 27 | 접근성 | 대비 4.5:1 이상, 터치 76dp 이상, 아이콘에 contentDescription, 색만으로 의미 전달 금지 |

## 8. 디자인

- 색(공식 로고에서 측정, `brand/BRAND.md`가 출처): 주황 `#F06519`(Primary), 흰색 `#FFFFFF`(배경), 흑연 `#31383F`(텍스트·주요 면), 보조 흑연 `#5B6067`, 연한 회색 `#F2F3F5`(카드), 경고 빨강 `#D93025`(경고에만).
- 대비 규칙(WCAG AA): 주황 바탕 위 글자는 흰색 **굵게 24sp 이상**(큰 글자 기준 3:1, 실측 3.19:1)만 허용. 흰 바탕 위 주황 글자는 `#BA4D0E`(5.05:1). 흑연 on 흰색 11.88:1. 흑연 on 주황(3.72:1)은 쓰지 않는다.
- 차량 화면(가로, 넓음): 큰 터치 영역(최소 76dp), 본문 글자 24sp 이상, 주행 중 화면은 글자 최소화.
- 스플래시: 흰 배경 + 로고 마크와 "타랑" 워드마크 조합(`R.drawable.taraang_splash_lockup`).
