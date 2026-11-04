"""도로 속성 레이어(data/roads_geumjeong.json)를 만든다: 금정구 OSM way 선형 + 차로 수·제한속도.

코스 경로를 OSM way에 붙이려면 way 선형이 필요하다. GraphHopper `osm_way_id` 경로 정보는 그래프를 다시
만들어야(한국 전체 수 분, 서버 중단) 쓸 수 있어서, 선형을 OSM pbf에서 한 번 뽑아 두고 공간 매칭한다(roads.py).

입력
- data/public/roadnet/osm_way_attributes_geumjeong.json: way별 lanes·maxSpeedKmh·source(README.md 표)
- data/public/roadnet/osm_way_enrichment_geumjeong.json: way별 oneway
- data/south-korea-latest.osm.pbf: way 선형

사용: uv run --with osmium python scripts/build_road_layer.py
"""

import json
from collections import Counter
from datetime import UTC, datetime
from pathlib import Path

from taraang_course import roads

ROOT = Path(__file__).resolve().parents[1]
PBF = ROOT / "data" / "south-korea-latest.osm.pbf"
ROADNET = ROOT / "data" / "public" / "roadnet"


def way_geometry(pbf: Path, ids: set[int]) -> dict[int, list[list[float]]]:
    import osmium

    out = {}
    fp = osmium.FileProcessor(str(pbf)).with_locations().with_filter(osmium.filter.KeyFilter("highway"))
    for obj in fp:
        if obj.is_way() and obj.id in ids:
            out[obj.id] = [[round(n.lat, 6), round(n.lon, 6)] for n in obj.nodes if n.location.valid()]
    return out


def main() -> None:
    attrs = json.loads((ROADNET / "osm_way_attributes_geumjeong.json").read_text())["ways"]
    enrich = json.loads((ROADNET / "osm_way_enrichment_geumjeong.json").read_text())["ways"]
    geometry = way_geometry(PBF, {int(k) for k in attrs})
    ways = []
    for key, a in sorted(attrs.items(), key=lambda kv: int(kv[0])):
        coords = geometry.get(int(key))
        if not coords or len(coords) < 2:
            continue
        ways.append(
            {
                "id": int(key),
                "coords": coords,
                "oneway": int(enrich.get(key, {}).get("oneway") or 0),
                "lanes": a["lanes"],
                "maxSpeedKmh": a["maxSpeedKmh"],
                "speedSource": a["source"],
                "lanesSource": a["fieldSources"]["lanes"],
                "highway": a["highway"],
            }
        )
    layer = {
        "generated": datetime.now(UTC).strftime("%Y-%m-%d"),
        "description": "금정구 OSM 자동차 도로 way 선형 + 차로 수·제한속도(OSM → 표준노드링크 → 5030 기본값)",
        "attribution": [roads.ATTRIBUTION],
        "stats": {
            "attribute_ways": len(attrs),
            "ways_with_geometry": len(ways),
            "speed_source": dict(Counter(w["speedSource"] for w in ways)),
        },
        "ways": ways,
    }
    tmp = roads.ROAD_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(layer, ensure_ascii=False, separators=(",", ":")) + "\n")
    tmp.replace(roads.ROAD_FILE)
    print(json.dumps(layer["stats"], ensure_ascii=False))
    print(f"-> {roads.ROAD_FILE} ({roads.ROAD_FILE.stat().st_size // 1024}KB)")


if __name__ == "__main__":
    main()
