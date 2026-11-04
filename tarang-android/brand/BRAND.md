# 타랑 브랜드 자산

## 개념
- **엠블럼**: 지붕(차의 곡선) 아래 나란히 앉은 두 사람. 주황은 운전하는 나, 흑연은 곁에 탄 코치. 지붕이 두 색으로 갈라져 각자를 덮는다. "혼자 운전하지만 앱이 나랑 같이 탄다."
- **워드마크 "타랑"**: 굵고 둥근 기하 한글. 흑연 바탕에 ㅌ·ㄹ에 주황 곡선 포인트.
- 톤: 차분하고 믿음직한 코치.

## 원본과 생성
- 원본(공식): `source/` — 가로 로크업 `Tarang logo with curved orange accent.png`, 엠블럼 `두 사람과 지붕 곡선 엠블럼.png`, 앱 아이콘 목업 `주황·흑연 하우스피플 앱 아이콘.png`.
- 벡터는 `trace_logo.py`가 원본을 트레이싱해 만든다(픽셀을 흰/주황/흑연 중 가장 가까운 색으로 분류 → 색별 potrace → even-odd 채움). SVG(`brand/`)와 VectorDrawable(`res/drawable/`)이 같은 경로를 쓴다.
  - 재생성: `uv venv && uv pip install potracer pillow numpy` 후 `python -I brand/trace_logo.py brand/source .` (프로젝트 루트에서).
- 원본 대비 일치도(채색 픽셀 기준): 엠블럼 99.7%, 로크업 98.5%. 차이는 가장자리 안티앨리어싱.
- 이전 자체 제작 마크·워드마크는 `legacy/`에 참고용으로 보관.

## 리소스
| 용도 | 소스 | 안드로이드 리소스 |
|---|---|---|
| 엠블럼 | `taraang_logo_mark.svg` | `@drawable/taraang_logo_mark` (96dp 폭) |
| 워드마크 | `taraang_wordmark.svg` (로크업에서 잘라냄) | `@drawable/taraang_wordmark` |
| 가로 로크업 (스플래시) | `taraang_splash_lockup.svg` | `@drawable/taraang_splash_lockup` (320dp 폭, 흰 배경용) |
| 앱 아이콘 | `taraang_app_icon.svg` | `@mipmap/ic_launcher`, `ic_launcher_round`: 흰 배경 + 엠블럼(66dp 안전 영역 안 56 폭), monochrome = 엠블럼 단색 실루엣 |
| 시스템 스플래시 아이콘 | — | `@drawable/brand_splash_icon` (2/3 마스크 원 안 60 폭) |

배경은 모두 투명. 흰 바탕은 테마·스플래시가 깐다. 미리보기: `png/`.

## 색 (원본에서 측정, 채색 영역 중심 픽셀의 중앙값)
| 이름 | 값 | 비고 |
|---|---|---|
| `taraang_logo_orange` | `#F06519` | 공식 로크업 기준. 원본마다 다름: 엠블럼 파일 `#F26E29`, 앱 아이콘 목업 `#FD670F`. 벡터는 모두 `#F06519`로 통일. |
| `taraang_logo_graphite` | `#31383F` | 세 원본 모두 동일. |

UI 팔레트(`taraang_orange #FF6A13`, `taraang_graphite #2E3135`)와 로고 색은 약간 다르다. 팔레트를 로고 색에 맞출지는 미정. 경고 빨강 `#D93025`는 경고 전용이며 로고에 쓰지 않는다.

## 여백과 최소 크기
- 여백: 엠블럼 높이의 1/4 이상.
- 최소 크기: 엠블럼 폭 32dp, 로크업 높이 32dp. 이보다 작으면 엠블럼만 쓴다.

## 할 것 / 하지 말 것
- 할 것: 흰 바탕에 원본 두 색 그대로. 단색이 필요하면 `ic_launcher_monochrome`처럼 전체를 한 색으로.
- 하지 말 것: 두 사람의 색 바꾸기, 지붕 틈 메우기, 늘이기·회전·그림자·그라데이션.
- 하지 말 것: 주황 또는 흑연 바탕 위에 두 색 로고 올리기(한쪽이 사라짐). 이때는 흰 단색 실루엣.
- 하지 말 것: 워드마크를 일반 글꼴로 다시 쓰기. 항상 `taraang_wordmark` 벡터를 쓴다.
