"""데이터와 번들 자산을 한 번에 다시 만든다: (수집) → 레이어 → 번들 코스 → 리플레이 → 테스트 → data/MANIFEST.json.

GraphHopper가 떠 있어야 한다(번들 코스·리플레이·경유지 확인). 단계 하나가 실패하면 거기서 멈춘다.
수집(--collect)은 인터넷이 필요하고, 신호 OpenAPI 단계는 서비스키가 필요하다(data/public/signals/PROVENANCE.md).

사용
  uv run python scripts/build_all.py              # 이미 받은 원문으로 레이어부터
  uv run python scripts/build_all.py --collect    # 공공데이터 다시 받기부터
  uv run python scripts/build_all.py --manifest   # MANIFEST.json만 다시 쓰기
"""

import argparse
import hashlib
import json
import subprocess
import sys
from datetime import UTC, datetime
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data"
MANIFEST = DATA / "MANIFEST.json"
PBF = DATA / "south-korea-latest.osm.pbf"
GH_CONTAINER = "taraang-gh"

COLLECT = [
    ["uv", "run", "--python", "3.12", "scripts/collect_zones.py", "download"],
    ["uv", "run", "--no-project", "--python", "3.12", "--with", "pyshp", "--with", "shapely", "--with", "osmium",
     "python", "-I", "scripts/collect_signals.py", "all"],
]
BUILD = [
    ["uv", "run", "--python", "3.12", "scripts/collect_zones.py", "build"],
    ["uv", "run", "--no-project", "--with", "osmium", "python", "generate_course.py", "signals", str(PBF)],
    ["uv", "run", "--with", "osmium", "python", "scripts/build_signal_layer.py"],
    ["uv", "run", "--with", "osmium", "python", "scripts/build_road_layer.py"],
    ["uv", "run", "python", "scripts/build_demo_courses.py"],
    ["uv", "run", "python", "scripts/build_replays.py"],
    ["uv", "run", "python", "scripts/check_waypoints.py"],
    ["uv", "run", "pytest", "-q"],
]

# 서버·번들이 읽는 파생 파일(.gitignore 허용 목록과 같다)
DERIVED = [
    "data/signals_busan.json",
    "data/signals_busan_merged.json",
    "data/roads_geumjeong.json",
    "data/public/zones/busan_school_zones.geojson",
    "data/public/zones/busan_elderly_zones.geojson",
    "data/public/zones/busan_accident_hotspots.geojson",
]


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def run(cmd: list[str]) -> None:
    print("$", " ".join(cmd), flush=True)
    if subprocess.run(cmd, cwd=ROOT).returncode != 0:
        raise SystemExit(f"실패: {' '.join(cmd)}")


def gh_image() -> str | None:
    """실행 중인 GraphHopper 컨테이너의 이미지(digest 고정). docker가 없으면 None."""
    try:
        out = subprocess.run(
            ["docker", "inspect", "--format", "{{.Config.Image}}", GH_CONTAINER], capture_output=True, text=True, timeout=10
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    return out.stdout.strip() or None


def gh_road_data_timestamp() -> str | None:
    """GraphHopper가 읽은 OSM 추출본의 데이터 시각(/info)."""
    try:
        return httpx.get("http://localhost:8989/info", timeout=5).json().get("data_date")
    except (httpx.HTTPError, ValueError):
        return None


def write_manifest() -> None:
    previous = json.loads(MANIFEST.read_text()) if MANIFEST.exists() else {}
    manifest = {
        "generated": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "osm": {
            "file": PBF.relative_to(ROOT).as_posix(),
            "source": "Geofabrik south-korea-latest.osm.pbf",
            "bytes": PBF.stat().st_size,
            "sha256": sha256(PBF),
            "roadDataTimestamp": gh_road_data_timestamp() or previous.get("osm", {}).get("roadDataTimestamp"),
        },
        "graphhopper": {
            "image": gh_image() or previous.get("graphhopper", {}).get("image"),
            "configSha256": sha256(ROOT / "graphhopper" / "config.yml"),
        },
        "derived": {
            rel: {"bytes": (ROOT / rel).stat().st_size, "sha256": sha256(ROOT / rel)}
            for rel in DERIVED
            if (ROOT / rel).exists()
        },
        "rebuild": "uv run python scripts/build_all.py [--collect]",
    }
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=1) + "\n")
    print(f"-> {MANIFEST}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--collect", action="store_true", help="공공데이터를 다시 받는다(인터넷, 신호 API 키 필요)")
    ap.add_argument("--manifest", action="store_true", help="MANIFEST.json만 다시 쓴다")
    args = ap.parse_args()
    if not args.manifest:
        if not PBF.exists():
            raise SystemExit(f"{PBF}가 없습니다. Geofabrik 한국 추출본을 받아 두세요")
        for cmd in (COLLECT if args.collect else []) + BUILD:
            run(cmd)
    write_manifest()


if __name__ == "__main__":
    sys.exit(main())
