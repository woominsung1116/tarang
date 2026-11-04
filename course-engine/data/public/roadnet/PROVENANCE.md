# 금정구 도로 속성(차로 수·제한속도·도로등급) 출처

수집: 2026-10-22. 원본 zip은 `raw/`에 그대로 두고 손대지 않는다. `raw/extracted/`는 그 zip에서 푼 파일이다.

## 데이터셋

| 항목 | 값 |
| --- | --- |
| 이름 | 전국 표준노드링크 (국토교통부, 국가교통정보센터 배포) |
| 포털 등록 | 공공데이터포털 [15025526](https://www.data.go.kr/data/15025526/fileData.do) "국토교통부_표준노드링크": 제공 방식 '기관자체에서 다운로드', 업데이트 '수시', 포털 수정일 2025-06-24 |
| 받은 곳 | [its.go.kr/nodelink/nodelinkRef](https://www.its.go.kr/nodelink/nodelinkRef) 노드링크 자료실(로그인 불필요). `scripts/collect_roadnet.py --download`가 그 페이지와 같은 요청 순서로 받는다 |
| 버전 | `[2026-10-14]NODELINKDATA.zip`. 자료실 설명: "2026-10-14까지 반영된 전국표준노드링크 및 회전제한정보, 링크부가정보". 금정구 링크의 `UPDATEDATE`는 20250512(2,706개)와 20251223(16개)이다 |
| 크기·해시 | 271,402,628 bytes, sha256 `ccddb655a96a4544cf71fbd91715aa4b2d0a617f92aa1b89a0cfc062118b70f3` |
| 자료실 목록 | `raw/its_filelist_2026-10-22.json` (조회 응답 원문), `raw/naeyeokseo_2026-10-14.csv` (변경 내역서, CP949) |
| 라이선스 | 포털 표기 "이용허락범위 제한 없음". 공공누리 유형 표기는 없다. 출처 표시: "국토교통부 국가교통정보센터, 표준노드링크(2026-10-14)" |
| 좌표계 | `MOCT_LINK.prj` = ITRF2000 Central Belt 60 (TM, 중부원점 127°E·38°N, FE 200,000, FN 600,000) = EPSG:5186. 산출 GeoJSON은 WGS84(EPSG:4326) |
| 인코딩 | DBF CP949 |

OSM 쪽: Geofabrik 한국 추출본 `data/south-korea-latest.osm.pbf`(2026-10-20), © OpenStreetMap contributors, ODbL 1.0. 금정구 경계는 OSM relation 4064527.
`osm_way_attributes_geumjeong.json`은 OSM way에 공공데이터 값을 붙인 파생 데이터라 ODbL 출처 표기도 함께 해야 한다.

## MOCT_LINK 필드

| 필드 | 뜻 | 산출물에서 |
| --- | --- | --- |
| `LINK_ID`, `F_NODE`, `T_NODE` | 링크 ID, 시작·끝 노드 ID. 링크는 방향이 있다(F→T가 진행 방향) | `linkId`, `fromNode`, `toNode` |
| `LANES` | 그 링크 진행 방향의 차로 수 | `lanes` (방향별), `lanesTotal` (반대 방향 짝과 합) |
| `MAX_SPD` | 제한속도 km/h | `maxSpeedKmh` |
| `ROAD_RANK` | 101 고속국도, 102 도시고속국도, 103 일반국도, 104 특별·광역시도, 105 국가지원지방도, 106 지방도, 107 시·군도, 108 기타 | `roadRank`, `roadRankName` |
| `ROAD_TYPE` | 000 일반도로, 001 고가차도, 002 지하차도, 003 교량, 004 터널 | `roadType` |
| `ROAD_NO`, `ROAD_NAME` | 노선 번호, 도로명(이름 없음은 `-`) | `roadNo`, `roadName` |
| `CONNECT` | 연결로 여부(1 = 연결로) | `isRamp` |
| `ROAD_USE` | 도로 사용 여부 코드. 금정구 '0' 2,611 / '1' 111 | `inUse` (= '0') |
| `MULTI_LINK` | 중용 구간 여부. 금정구 '1'은 2개 | 안 씀 |
| `REST_VEH`, `REST_W`, `REST_H` | 통행 제한 차량·중량·높이 | 안 씀 |
| `LENGTH` | 링크 길이 m | `lengthM` |
| `UPDATEDATE`, `HIST_TYPE`, `HISTREMARK`, `REMARK` | 갱신일, 변경 이력 유형·비고 | `updated`만 |

코드표(`ROAD_RANK`, `ROAD_TYPE`)는 지능형교통체계 표준 노드·링크 구축기준을 따랐다고 스크립트에 적혀 있다. 이 문서를 쓰면서 고시 원문과 다시 대조하지는 않았다.
`LANES`가 방향별이라는 점은 데이터로 확인했다. 금정구 특별·광역시도 링크의 72%(길이 기준)가 1차로이고, 링크의 97%(2,628/2,722)는 반대 방향 짝이 있다.
**`ROAD_USE='1'`의 정확한 뜻(미사용·공사 중·계획 등)은 확인하지 못했다.** 이 111개 링크는 다른 링크와 겹치지 않는 독립 구간(금사로·개좌로·범어천로 등)이라 버리지 않았다. 최종 매칭 중 8개 way가 이 링크에 붙어 있다(`osm_way_enrichment_geumjeong.json`의 `linkInUse: false`).

## 처리 방법

1. `scripts/collect_roadnet.py --zip raw/...zip`: zip에서 `MOCT_LINK.*`만 풀고, 금정구 경계와 겹치는 링크 2,722개를 잘라 `geumjeong_links.geojson`으로 쓴다.
   같은 도로명이고 진행 방향이 150° 이상 다른 링크가 40m 안에 있으면 양방향 짝(`oppositeLinkId`)으로 기록한다. 등급별 채움 비율은 `coverage_geumjeong.json`.
2. `scripts/enrich_osm_with_nodelink.py`: 금정구 OSM 자동차 도로 way 1,611개(355.3km)를 10m 간격으로 찍고, 점마다 15m 안·진행 방향 ±30° 안의 가장 가까운 링크를 고른다.
   일방통행 way는 통행 방향 링크만, 양방향 way는 양쪽 방향 링크를 모두 받는다. 점의 50% 이상이 잡힌 way만 매칭으로 본다(`matchScore` = 잡힌 점 비율).
   양방향 way의 차로 수는 양쪽 대표 링크 `LANES`의 합, 한쪽만 잡히면 짝 링크가 있을 때 `lanesTotal`, 짝도 없으면 비운다. 결과: `osm_way_enrichment_geumjeong.json`.
3. `scripts/build_way_attributes.py`: 필드마다 OSM 태그 → 노드링크 → 기본값 순으로 정한다. 다음 매칭은 버린다.
   - `name_conflict` (49 way): OSM 이름과 링크 도로명이 둘 다 있고 다르다. 이면도로가 옆 고속도로 링크에 붙거나 교차로에서 다른 도로에 붙은 경우가 이 유형이었다.
   - `expressway_on_local` (7 way): 고속국도·도시고속국도 링크가 motorway/trunk 계열이 아닌 way에 붙었다.
   OSM과 노드링크 값이 다르면 OSM 값을 쓰고 `agreement_geumjeong.json`에 남긴다.

## 매칭률 (OSM 길이 기준, `osm_way_enrichment_geumjeong.json` report)

| OSM 등급 | km | 노드링크 매칭 |
| --- | --- | --- |
| residential | 142.5 | 13.3% |
| tertiary | 56.0 | 84.8% |
| secondary | 48.1 | 98.8% |
| trunk | 29.6 | 80.6% |
| motorway | 22.4 | 100% |
| primary | 18.4 | 100% |
| unclassified | 13.7 | 21.3% |
| _link 5종 | 24.5 | 18–89% |
| 전체 | 355.3 | 56.9% (644/1,611 way) |

이름 충돌·고속도로 오매칭을 버린 뒤 최종 채움(길이 기준, 전체 355.3km):

| 필드 | OSM | 노드링크 | 기본값(5030) | 없음 |
| --- | --- | --- | --- | --- |
| 차로 수 | 12.5% | 42.2% | - | 45.2% |
| 제한속도 | 9.5% | 45.4% | 42.3% | 2.8% |

노드링크는 주로 간선도로만 담는다. residential 매칭률 13%는 데이터 범위의 한계이지 매칭 실패가 아니다.

## OSM과 노드링크 비교 (`agreement_geumjeong.json`)

두 값이 모두 있는 way만 비교했다. 비교 대상이 적고(차로 90 way, 속도 72 way) 절반 가까이가 motorway라서 일반도로 품질을 대표하지 못한다.

| 범위 | 차로 수 비교 | 정확히 같음 | ±1 이내 | 제한속도 비교 | 같음 |
| --- | --- | --- | --- | --- | --- |
| 전체 | 90 | 75.6% | 95.6% | 72 | 77.8% |
| motorway | 41 | 82.9% | 97.6% | 41 | 80.5% |
| motorway_link | 16 | 62.5% | 100% | 11 | 81.8% |
| secondary | 15 | 80.0% | 93.3% | 12 | 58.3% |

큰 불일치 예(way id, OSM → 노드링크):
- 차로: 1177759116 경부고속도로 2 → 5, 37399201 체육공원로399번길 4 → 2, 476853248 중앙대로 2 → 4, 545929989 식물원로 1 → 3, 787202235 범어사로 2 → 1 (2.3km).
- 속도: 1177759116 경부고속도로 100 → 40, 1177759115 경부고속도로 100 → 60, 1356632119·1552713108 secondary 30 → 60, 1177759112 경부고속도로 80 → 100.
- 경부고속도로 불일치는 노포동 끝 구간(톨게이트·감속 구간)에 몰려 있다. 노드링크는 링크 단위로 낮은 속도를 두고 OSM은 본선 값을 이어 단 것으로 보인다. 어느 쪽이 현장 표지와 맞는지는 확인하지 않았다.

이 비교는 두 2차 자료끼리의 일치율이지 정답 대비 정확도가 아니다. 현장 표지 확인이 정답 역할을 해야 한다.

## 제한속도 기본값 (`source: "default_5030"`, 관측값 아님)

근거: 도로교통법 시행규칙 제19조①1가 "주거지역·상업지역 및 공업지역의 일반도로에서는 매시 50킬로미터 이내"(시·도경찰청장 지정 노선·구간은 60). 30km/h는 제19조에 없고 시·도경찰청장이 도로교통법 제17조②로 구간을 지정해 정한다(`hyundai/docs/research/FACT_CHECK_20261022.md` 5b).

| OSM 등급 (OSM·노드링크 둘 다 값 없을 때) | 기본값 | `defaultBasis` |
| --- | --- | --- |
| residential, unclassified | 30 | `busan_5030_local_street_assumption`: 부산 안전속도 5030의 이면도로 30 지정 정책 + 이 구 노드링크가 붙은 residential way의 최빈 제한속도가 30(최종 채택 매칭 82 way 중 61) |
| primary/secondary/tertiary와 _link | 50 | `road_traffic_act_rule19_1ga_urban_cap`: 도시부 일반도로 상한 |
| motorway/trunk와 _link | 없음(null, `source: "none"`) | 고속도로·자동차전용도로는 제19조 다른 호를 따른다 |

차로 수와 도로등급에는 기본값을 두지 않는다.

## 알려진 한계

- **residential 30은 추정이다.** 법정 일률값이 아니고, 지정 구간이 아닌 이면도로의 법정 상한은 50이다. 금정산 일대처럼 녹지지역에 있는 도로는 제19조①1나가 적용되어 상한이 다르다. 용도지역 데이터로 way를 가르지 않았다.
- 노드링크 제한속도의 52%(1,422/2,722 링크)가 30이고, 이름 있는 간선(금샘로·산성로·금단로 등 secondary)도 30이 많다. 보호구역·5030 지정이 반영된 것으로 보이지만 현장 대조는 하지 않았다.
- 링크 하나의 값을 way 전체에 붙인다(점이 가장 많이 잡힌 링크). way 안에서 차로 수·속도가 바뀌는 구간은 뭉개진다. 양방향 속도가 다른 way 13개는 enrichment의 `speedSplit`에 남겼다.
- 양방향 way인데 한쪽 방향 링크만 잡히고 짝도 없는 9개 way는 차로 수를 비웠다(`lanesBasis: one_direction_only`, 방향 차로 수는 `lanesOneDirection`).
- 이름 충돌 규칙은 보수적이다. 정관산업로(OSM)·번영로(링크)처럼 같은 도로의 다른 이름일 수 있는 경우도 버렸다. 그 결과 trunk 22.6%가 차로·속도 모두 없음이다.
- 비교 표본이 작다(위 '비교' 참고).

## 코스 서버에서 쓰는 방식 (`tarang_course/roads.py`)

- `scripts/build_road_layer.py`가 `osm_way_attributes_geumjeong.json`(차로 수·제한속도·출처) + `osm_way_enrichment_geumjeong.json`(oneway) + OSM pbf의 way 선형을 합쳐 `data/roads_geumjeong.json`(1,611 way)을 만든다.
- 경로를 10m 간격으로 짚어 15m 안·진행 방향 ±30° 안의 가장 가까운 way에 붙인다(GraphHopper 경로에 way id가 없어서).
- 1단계 조건: 관측 제한속도(`source` osm·nodelink) 50km/h 초과 구간과, 왕복 기준 4차로 이상(일방통행은 `lanes` × 2) 구간이 없어야 한다. `default_5030`은 ESTIMATED로 따로 세고 판정에 쓰지 않는다.
- 출처 표시: 응답 `dataNotes`에 "차로 수·제한속도: 국토교통부 국가교통정보센터 표준노드링크(2026-10-14), 공공데이터포털(data.go.kr)"(포털 15025526). OSM 부분은 ODbL 표기(응답 마지막 줄)와 함께.
