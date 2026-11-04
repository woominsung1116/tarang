# roadnet: 금정구 도로 속성

OSM 금정구는 차로 수가 길이 기준 13%, 제한속도가 9%만 채워져 있다. 국가 표준노드링크로 간선도로를 채우고, 남는 이면도로 제한속도는 안전속도 5030 기본값으로 표시한다. 출처·라이선스·방법·한계는 [PROVENANCE.md](PROVENANCE.md).

## 코스 엔진이 읽을 파일

`osm_way_attributes_geumjeong.json` → `ways[osmWayId]`:

| 키 | 뜻 |
| --- | --- |
| `lanes` | 차로 수. OSM 관례대로 일방통행은 통행 방향, 양방향은 양쪽 합계. 모르면 null |
| `maxSpeedKmh` | 제한속도 km/h. 모르면 null |
| `roadRank` / `roadRankName` | 노드링크 도로등급 코드(101 고속국도 … 107 시·군도)와 이름. 매칭 없으면 null |
| `source` | **제한속도 값의 출처**: `osm` / `nodelink` / `default_5030`(관측값 아님, 추정) / `none` |
| `fieldSources` | 필드별 출처 `{lanes, maxSpeedKmh, roadRank}`: `osm` / `nodelink` / `default_5030` / null |
| `defaultBasis` | 기본값을 쓴 근거 키. 기본값이 아니면 null |
| `matchScore` | 노드링크 매칭 점 비율(0.5–1.0). 매칭 시도에서 실패했으면 null |
| `linkId` | 값을 가져온 노드링크 링크. 버린 매칭이면 null |
| `nodelinkRejected` | 매칭을 버린 이유: `name_conflict` / `expressway_on_local` / null |
| `highway`, `lengthM` | OSM 등급, way 길이(m) |

`source: default_5030`인 way를 "제한속도 확인됨"으로 보여주면 안 된다. 난이도 채점에 쓰더라도 추정값으로 표시한다.

## 다시 만들기 (course-engine/ 에서)

```sh
uv run --python 3.12 scripts/collect_roadnet.py --zip "data/public/roadnet/raw/[2026-10-14]NODELINKDATA.zip"  # 또는 --download
uv run --python 3.12 scripts/enrich_osm_with_nodelink.py   # OSM pbf 필요: data/south-korea-latest.osm.pbf
uv run --python 3.12 scripts/build_way_attributes.py
```

## 파일

| 파일 | 내용 |
| --- | --- |
| `raw/` | 원본 zip(변경 금지), 푼 shapefile, 자료실 목록·변경 내역서 |
| `geumjeong_boundary.geojson` | OSM relation 4064527 경계 |
| `geumjeong_links.geojson` | 금정구 노드링크 2,722개(WGS84), 정규화된 속성 |
| `coverage_geumjeong.json` | 노드링크 등급별 연장·채움 비율·차로/속도 분포 |
| `osm_way_enrichment_geumjeong.json` | OSM way 1,611개의 OSM 태그와 매칭 근거(대표 링크, 방향별 링크, 거리, `lanesBasis`, `speedSplit`) |
| `osm_way_attributes_geumjeong.json` | 최종 속성(위 표) + 등급별 출처 비율 `coverageByLengthPct` |
| `agreement_geumjeong.json` | OSM·노드링크 둘 다 값이 있는 way의 일치율과 불일치 상위 25개 |
