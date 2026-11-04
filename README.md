# 타랑 (Taraang)

장롱면허·초보 운전자가 출근길·등하원길처럼 "내가 실제로 다녀야 하는 길"을 혼자 달릴 때, 차량 신호로 그 길의 어려운 지점마다 미리 짚어주고 그 길에 맞춘 연습 코스까지 만들어 주는 Pleos Connect 차량용 운전 코치 앱.

- 목표 경로를 기술(신호 좌회전·유턴·어린이보호구역 등)로 분해하고, 집 근처에서 쉬운 조건부터 연습하는 단계별 순환 코스를 만든다.
- 주행 중에는 방향지시등·브레이크·경고등·내비 신호로 필요한 순간에만 음성 한 문장을 준다.
- 주차 후에는 기록을 이전 주행과 비교해 다음 코스를 제안한다.

## APK

`apk/TEAM_Taraang_release.apk` — release 빌드(versionName 0.1.0, minSdk 28, targetSdk 35). 공모전 시연용으로 debug 키로 서명했다.

## 실행 방법

1. Android Studio에서 Pleos Connect 시스템 이미지(Android 14, API 34, Automotive)로 AVD를 만든다. ([Pleos 에뮬레이터 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator))
2. APK 설치: `adb install apk/TEAM_Taraang_release.apk`
3. 앱을 실행하면 부산대 정문 앞 시연 코스(단계 1–3, 각 3개)가 들어 있다. release 빌드는 코스 서버 없이 앱에 들어 있는 코스로 동작한다.
4. 코스 상세 화면에서 차량 신호 대신 시연 리플레이를 골라 재생하면 주행 코칭과 복기를 확인할 수 있다.
   - `demo_l1_signal_miss`: 어린이보호구역 예고 → 보호구역 속도 → 방향지시등 누락 → 제한속도 초과
   - `demo_l2_warning_brake`: 연습 기술 예고 → 새 타이어 경고 → 급제동 반복

## 테스트 환경 및 필요한 설정

| 항목 | 값 |
| --- | --- |
| 실행 대상 | Pleos Connect AVD (Android 14, API 34, Automotive) |
| 빌드 | JDK 17, Gradle 8.10.2, AGP 8.8.2, Kotlin 2.0.0 |
| Pleos SDK | `ai.pleos.playground:Vehicle:2.3.2`, `ai.pleos.playground:NaviHelper:3.0.5` (공개 Maven `https://nexus-playground.pleos.ai/repository/maven-releases/`) |
| 코스 서버(선택) | Python 3.12 + `uv`, GraphHopper(Docker). `course-engine/README.md` 참고 |

직접 빌드:

```sh
cd taraang-android
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest   # 단위 테스트
```

코스 서버를 함께 띄우면 debug 빌드가 에뮬레이터에서 `http://10.0.2.2:8000`으로 새 코스를 받는다(`course-engine/README.md` 1–2장).

## 저장소 구성

| 경로 | 내용 |
| --- | --- |
| `taraang-android/` | 차량 앱. 구조·계약은 `taraang-android/ARCHITECTURE.md` |
| `course-engine/` | 연습 코스 생성 서버(FastAPI + GraphHopper + 공공데이터) |
| `docs/research/` | 판정 기준값 사실 검증, 지도 API 검토 |
| `IDEA_타랑.md` | 기획 |
| `apk/` | 제출용 release APK |

## 데이터 출처

© OpenStreetMap contributors ([ODbL 1.0](https://opendatacommons.org/licenses/odbl/)), 공공데이터포털(부산 신호, 전국 어린이·노인보호구역, 교통사고다발지역, 국가교통정보센터 표준노드링크), [GraphHopper](https://github.com/graphhopper/graphhopper) (Apache 2.0).
