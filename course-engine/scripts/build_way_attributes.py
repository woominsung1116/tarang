"""OSM way별 차로 수·제한속도·도로등급을 하나로 정한다: OSM 태그 → 표준노드링크 → 안전속도 5030 기본값.

입력: enrich_osm_with_nodelink.py가 만든 osm_way_enrichment_geumjeong.json (pbf를 다시 읽지 않는다).
출력
- osm_way_attributes_geumjeong.json: 코스 엔진용 {osmWayId: {lanes, maxSpeedKmh, roadRank, source, matchScore, ...}}
- agreement_geumjeong.json: OSM과 노드링크 값이 둘 다 있는 way의 일치율과 불일치 목록

규칙
1. 노드링크 매칭을 버리는 경우(nodelinkRejected에 이유)
   - name_conflict: OSM name과 링크 도로명이 둘 다 있고 다르다(링크 도로명 '-'는 이름 없음으로 본다).
     실측에서 이면도로가 옆 고속도로 링크에 붙거나 교차로에서 다른 도로 링크에 붙은 경우가 이 유형이었다.
   - expressway_on_local: 링크가 고속국도·도시고속국도인데 OSM way가 motorway/trunk 계열이 아니다.
2. 값 우선순위(필드마다 따로): OSM 태그가 있으면 OSM, 없으면 노드링크, 둘 다 없으면 기본값 또는 null.
   노드링크가 국가 기준 자료지만 링크 단위로 뭉뚱그려져 있어 OSM 태그(조사자가 그 구간을 보고 단 값)를 덮어쓰지 않는다.
   불일치는 agreement_geumjeong.json에 남겨 사람이 확인한다.
3. 제한속도 기본값(source=default_5030, 관측값이 아니다)
   - residential, unclassified: 30. 법정 일률값이 아니다. 30km/h는 시·도경찰청장이 도로교통법 제17조②로
     구간을 지정해 정한다. 부산 안전속도 5030 정책의 이면도로 30 지정과, 이 구에서 노드링크가 붙은
     residential way의 제한속도 최빈값이 30이라는 점에 기댄 추정이다.
   - primary/secondary/tertiary와 그 _link: 50. 도로교통법 시행규칙 제19조①1가의 도시부(주거·상업·공업지역)
     일반도로 상한이다. 녹지지역 도로라면 상한이 다르다(제19조①1나).
   - motorway/trunk와 그 _link: 기본값 없음(null). 고속도로·자동차전용도로는 제19조 다른 호를 따른다.
   차로 수·도로등급은 기본값을 두지 않는다.

사용 (course-engine/ 에서):
  uv run --python 3.12 scripts/build_way_attributes.py
"""

import argparse
import json
import re
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "data" / "public" / "roadnet"

EXPRESSWAY_RANKS = {"101", "102"}
FAST_CLASSES = {"motorway", "motorway_link", "trunk", "trunk_link"}
DEFAULT_SPEED = {
    "residential": (30, "busan_5030_local_street_assumption"),
    "unclassified": (30, "busan_5030_local_street_assumption"),
    **{c: (50, "road_traffic_act_rule19_1ga_urban_cap")
       for c in ("primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link")},
}
TOP_N = 25


def parse_int(v):
    m = re.match(r"\s*(\d+)\s*$", v or "")
    return int(m.group(1)) if m else None


def rejection(osm: dict, match: dict) -> str | None:
    name, link_name = osm.get("name"), match["linkName"]
    if name and link_name and link_name != "-" and name != link_name:
        return "name_conflict"
    if match["roadRank"] in EXPRESSWAY_RANKS and osm["highway"] not in FAST_CLASSES:
        return "expressway_on_local"
    return None


def resolve(way: dict) -> dict:
    osm, match = way["osm"], way["match"]
    rejected = rejection(osm, match) if match else None
    nl = match if match and not rejected else None

    osm_lanes, osm_speed = parse_int(osm.get("lanes")), parse_int(osm.get("maxspeed"))
    out, src = {}, {}
    for key, osm_val, nl_val in (("lanes", osm_lanes, nl and nl["lanes"]),
                                 ("maxSpeedKmh", osm_speed, nl and nl["maxSpeedKmh"]),
                                 ("roadRank", None, nl and nl["roadRank"])):
        if osm_val:
            out[key], src[key] = osm_val, "osm"
        elif nl_val:
            out[key], src[key] = nl_val, "nodelink"
        else:
            out[key], src[key] = None, None
    basis = None
    if out["maxSpeedKmh"] is None and osm["highway"] in DEFAULT_SPEED:
        out["maxSpeedKmh"], basis = DEFAULT_SPEED[osm["highway"]]
        src["maxSpeedKmh"] = "default_5030"

    return {
        "lanes": out["lanes"],
        "maxSpeedKmh": out["maxSpeedKmh"],
        "roadRank": out["roadRank"],
        "roadRankName": nl["roadRankName"] if nl else None,
        # source = 제한속도 값의 출처(osm|nodelink|default_5030|none). 필드별 출처는 fieldSources.
        "source": src["maxSpeedKmh"] or "none",
        "fieldSources": src,
        "defaultBasis": basis,
        "matchScore": match["matchScore"] if match else None,
        "linkId": nl["linkId"] if nl else None,
        "nodelinkRejected": rejected,
        "highway": osm["highway"],
        "lengthM": way["lengthM"],
    }


def agreement(ways: dict) -> dict:
    """OSM 태그와 (버리지 않은) 노드링크 값이 둘 다 있는 way만 비교한다."""
    cmp = defaultdict(lambda: defaultdict(int))
    lane_rows, speed_rows = [], []
    for wid, w in ways.items():
        m, osm = w["match"], w["osm"]
        if not m or rejection(osm, m):
            continue
        ol, os_ = parse_int(osm.get("lanes")), parse_int(osm.get("maxspeed"))
        row = {"osmWayId": wid, "highway": osm["highway"], "name": osm.get("name"), "linkId": m["linkId"],
               "linkName": m["linkName"], "lengthM": w["lengthM"], "matchScore": m["matchScore"]}
        for key in (osm["highway"], "TOTAL"):
            c = cmp[key]
            if ol and m["lanes"]:
                c["lanesCompared"] += 1
                c["lanesExact"] += ol == m["lanes"]
                c["lanesWithin1"] += abs(ol - m["lanes"]) <= 1
            if os_ and m["maxSpeedKmh"]:
                c["speedCompared"] += 1
                c["speedEqual"] += os_ == m["maxSpeedKmh"]
        if ol and m["lanes"] and ol != m["lanes"]:
            lane_rows.append({**row, "osmLanes": ol, "nodelinkLanes": m["lanes"], "lanesBasis": m["lanesBasis"]})
        if os_ and m["maxSpeedKmh"] and os_ != m["maxSpeedKmh"]:
            speed_rows.append({**row, "osmMaxSpeed": os_, "nodelinkMaxSpeed": m["maxSpeedKmh"]})
    lane_rows.sort(key=lambda r: (-abs(r["osmLanes"] - r["nodelinkLanes"]), -r["lengthM"]))
    speed_rows.sort(key=lambda r: (-abs(r["osmMaxSpeed"] - r["nodelinkMaxSpeed"]), -r["lengthM"]))
    pct = lambda a, b: round(100 * a / b, 1) if b else None
    summary = {k: {"lanesCompared": c["lanesCompared"], "lanesExactPct": pct(c["lanesExact"], c["lanesCompared"]),
                   "lanesWithin1Pct": pct(c["lanesWithin1"], c["lanesCompared"]),
                   "speedCompared": c["speedCompared"], "speedEqualPct": pct(c["speedEqual"], c["speedCompared"])}
               for k, c in sorted(cmp.items(), key=lambda kv: -kv[1]["lanesCompared"] - kv[1]["speedCompared"])}
    return {"summary": summary, "laneDisagreements": lane_rows[:TOP_N], "speedDisagreements": speed_rows[:TOP_N],
            "laneDisagreementCount": len(lane_rows), "speedDisagreementCount": len(speed_rows)}


def coverage(attrs: dict) -> dict:
    by = defaultdict(lambda: defaultdict(float))
    for a in attrs.values():
        km = a["lengthM"] / 1000
        for key in (a["highway"], "TOTAL"):
            b = by[key]
            b["km"] += km
            for field in ("lanes", "maxSpeedKmh", "roadRank"):
                b[f"{field}:{a['fieldSources'][field]}"] += km
    out = {}
    for k, b in sorted(by.items(), key=lambda kv: -kv[1]["km"]):
        row = {"km": round(b["km"], 1)}
        for field in ("lanes", "maxSpeedKmh", "roadRank"):
            row[field] = {s: round(100 * b[f"{field}:{s}"] / b["km"], 1)
                          for s in ("osm", "nodelink", "default_5030", None) if b[f"{field}:{s}"]}
        out[k] = row
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--enrichment", type=Path, default=OUT / "osm_way_enrichment_geumjeong.json")
    ap.add_argument("--out", type=Path, default=OUT / "osm_way_attributes_geumjeong.json")
    ap.add_argument("--agreement", type=Path, default=OUT / "agreement_geumjeong.json")
    args = ap.parse_args()

    doc = json.loads(args.enrichment.read_text())
    ways = doc["ways"]
    attrs = {wid: resolve(w) for wid, w in ways.items()}
    rejected = defaultdict(int)
    for a in attrs.values():
        if a["nodelinkRejected"]:
            rejected[a["nodelinkRejected"]] += 1
    cov = coverage(attrs)
    args.out.write_text(json.dumps({
        "source": doc["source"], "matchParams": doc["params"],
        "coverageByLengthPct": cov, "nodelinkRejected": dict(rejected), "ways": attrs},
        ensure_ascii=False, indent=1))
    agr = agreement(ways)
    args.agreement.write_text(json.dumps(agr, ensure_ascii=False, indent=1))

    print(f"{len(attrs)} ways → {args.out}")
    print(f"nodelink matches rejected: {dict(rejected)}")
    for k in ("TOTAL", "residential", "tertiary", "secondary", "trunk", "primary", "unclassified"):
        print(f"{k:<13}{cov[k]['km']:>7} km  lanes {cov[k]['lanes']}  speed {cov[k]['maxSpeedKmh']}")
    print(f"agreement → {args.agreement}")
    for k, s in agr["summary"].items():
        print(f"{k:<16}lanes {s['lanesCompared']:>3} exact {s['lanesExactPct']}% ±1 {s['lanesWithin1Pct']}%"
              f"  speed {s['speedCompared']:>3} equal {s['speedEqualPct']}%")


if __name__ == "__main__":
    main()
