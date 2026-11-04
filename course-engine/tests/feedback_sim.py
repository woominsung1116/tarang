"""앱 FeedbackEngine(tarang-android/app/src/main/java/com/taraang/app/feedback/)의 파이썬 이식.

리플레이 로그가 앱에서 실제로 어떤 문장을 말하고 어떤 이벤트를 남기는지 서버 쪽 테스트에서 확인하려는 용도다.
Kotlin 구현이 정답이며, 여기 값과 동작은 그 파일들(FeedbackConfig, SpeedEstimator, TurnTracker, CourseTrackers,
TurnSignalJudge, rules/*, FeedbackArbiter, FeedbackEngine)을 그대로 옮긴 것이다. 앱이 바뀌면 같이 바꾼다.
리플레이는 모든 신호가 AVAILABLE이고 프레임마다 틱 1회라고 본다(ReplaySignalSource).
"""

import math
from collections import deque
from dataclasses import dataclass, field

from tarang_course.geo import haversine

# FeedbackConfig
MIN_GAP_MS = 20_000
MAX_MESSAGES = 12
SAME_TYPE_COOLDOWN_MS = 60_000
SILENT_NEAR_TURN_M = 20.0
NAG_M = 50.0
JUDGE_M = 40.0  # 법 30m(교차로 가장자리) + 교차로 반폭 10m
MIN_SPACING_M = 40.0  # 앞 회전과 이보다 붙은 회전은 판정하지 않는다
NEW_TURN_JUMP_M = 25.0
SCHOOL_AHEAD_M = 300
SCHOOL_FALLBACK_KMH = 30.0
SCHOOL_SUSTAIN_MS = 3_000
OVER_LIMIT_KMH = 10.0
SPEED_SUSTAIN_MS = 5_000
HYSTERESIS_KMH = 3.0
PREVIEW_MAX_M = 150.0
PREVIEW_MIN_M = 50.0
MANEUVER_REACHED_M = 25.0
MANEUVER_MATCH_M = 80.0
HARD_SPLIT_KMH = 30.0
HARD_DROP_LOW_KMH = 14.0
HARD_DROP_HIGH_KMH = 15.0
HARD_MIN_END_KMH = 6.0
HARD_MIN_START_SMOOTHED_KMH = 10.0  # 감속 시작 표본의 평활 속도. 신호 대기 중 위치 떨림을 급제동으로 세지 않게
HARD_WINDOW_MS = 1_000
HARD_SPEED_WINDOW_MS = 500
HARD_REPEAT = 3
HARD_QUIET_MS = 5_000
OFF_COURSE_M = 60.0
PROGRESS_JUMP_SLACK_M = 120.0  # FeedbackConfig.progressJumpSlackM
OFF_COURSE_SUSTAIN_MS = 5_000
SPEED_WINDOW_MS = 1_500
ALPHA = 0.6
STATIONARY_KMH = 3.0
MAX_PLAUSIBLE_KMH = 180.0
JUMP_RESET = 3
STALE_MS = 5_000
PASSED_FALLBACK_MS = 3_000
PROGRESS_BACK_M = 50.0
PROGRESS_AHEAD_M = 300.0
M_PER_DEG_LAT = 111_320.0

PRIORITY = {
    "NEW_WARNING": 1,
    "SCHOOL_ZONE_AHEAD": 2,
    "SCHOOL_ZONE_SPEED": 2,
    "SPEED_LIMIT_SUSTAINED": 3,
    "TURN_SIGNAL_MISSING": 4,
    "SKILL_PREVIEW": 5,
    "HARD_BRAKE_REPEAT": 6,
}
# FeedbackType.defaultEnabled. 보호구역 진입 예고는 차량 내비가 이미 안내해 기본으로 끈다(설정에서 켤 수 있다).
DEFAULT_ENABLED = frozenset(PRIORITY) - {"SCHOOL_ZONE_AHEAD"}
MANEUVER_KINDS = {"LEFT", "RIGHT", "UTURN", "ROUNDABOUT"}


def dist(a, b) -> float:
    return haversine(a[0], a[1], b[0], b[1])


class SpeedEstimator:
    def __init__(self):
        self.samples: deque = deque()
        self.smoothed = None
        self.short = None
        self.jumps = 0

    def update(self, pos, ts):
        last = self.samples[-1] if self.samples else None
        if last and ts <= last[1]:
            return
        if last and dist(last[0], pos) / ((ts - last[1]) / 1000) * 3.6 > MAX_PLAUSIBLE_KMH:
            self.jumps += 1
            if self.jumps < JUMP_RESET:
                return
            self.samples.clear()
            self.smoothed = self.short = None
        self.jumps = 0
        self.samples.append((pos, ts))
        while len(self.samples) > 2 and self.samples[1][1] <= ts - SPEED_WINDOW_MS:
            self.samples.popleft()
        raw = self._raw()
        if raw is not None:
            nxt = raw if self.smoothed is None else ALPHA * raw + (1 - ALPHA) * self.smoothed
            self.smoothed = 0.0 if nxt < STATIONARY_KMH else nxt
        short = self._raw_span(HARD_SPEED_WINDOW_MS)
        if short is not None:
            self.short = short

    def _raw(self):
        if len(self.samples) < 2:
            return None
        (p0, t0), (p1, t1) = self.samples[0], self.samples[-1]
        if t1 - t0 < SPEED_WINDOW_MS / 2:
            return None
        return _kmh(p0, t0, p1, t1)

    def _raw_span(self, min_span):
        """마지막 점과, 그보다 min_span 이상 앞선 점 중 가장 최근 점 사이 속도(평활 없음)."""
        if not self.samples:
            return None
        p1, t1 = self.samples[-1]
        for p0, t0 in reversed(list(self.samples)[:-1]):
            if t1 - t0 >= min_span:
                return _kmh(p0, t0, p1, t1)
        return None

    def _fresh(self, now) -> bool:
        return bool(self.samples) and now - self.samples[-1][1] <= STALE_MS

    def current(self, now):
        return self.smoothed if self._fresh(now) else None

    def current_short(self, now):
        return self.short if self._fresh(now) else None


def _kmh(p0, t0, p1, t1) -> float:
    raw = dist(p0, p1) / ((t1 - t0) / 1000) * 3.6
    return 0.0 if raw < STATIONARY_KMH else raw


@dataclass
class Turn:
    id: int
    kind: str
    distance: float
    max_seen: float


class TurnTracker:
    def __init__(self):
        self.current = None
        self.next_id = 0
        self.passed_at = None
        self.passed_ms = None
        self.near = False

    def update(self, nt, pos, now):
        prev = self.current
        if nt is None:
            nxt = None
        elif prev is None or prev.kind != nt["type"] or nt["distanceM"] > prev.distance + NEW_TURN_JUMP_M:
            nxt = Turn(self.next_id, nt["type"], nt["distanceM"], nt["distanceM"])
            self.next_id += 1
        else:
            nxt = Turn(prev.id, prev.kind, nt["distanceM"], max(prev.max_seen, nt["distanceM"]))
        if prev and prev.kind in MANEUVER_KINDS and (nxt is None or prev.id != nxt.id) and prev.distance <= JUDGE_M:
            self.passed_at, self.passed_ms = pos, now
        self.current = nxt
        approaching = nxt is not None and nxt.kind in MANEUVER_KINDS and nxt.distance <= SILENT_NEAR_TURN_M
        if self.passed_at is not None:
            just_passed = dist(self.passed_at, pos) <= SILENT_NEAR_TURN_M
        else:
            just_passed = self.passed_ms is not None and now - self.passed_ms <= PASSED_FALLBACK_MS
        self.near = approaching or just_passed
        return nxt


class CoursePath:
    """코스 선과 누적 거리. project는 (선까지 거리, 선을 따라 간 거리)."""

    def __init__(self, path):
        self.points = [tuple(p) for p in path]
        self.cum = [0.0]
        for a, b in zip(self.points, self.points[1:]):
            self.cum.append(self.cum[-1] + dist(a, b))

    @property
    def usable(self) -> bool:
        return len(self.points) >= 2

    def _project_segment(self, p, i):
        a, b = self.points[i], self.points[i + 1]
        kx = M_PER_DEG_LAT * math.cos(math.radians(p[0]))
        ax, ay = (a[1] - p[1]) * kx, (a[0] - p[0]) * M_PER_DEG_LAT
        dx, dy = (b[1] - a[1]) * kx, (b[0] - a[0]) * M_PER_DEG_LAT
        ln = dx * dx + dy * dy
        t = 0.0 if ln == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / ln))
        return math.hypot(ax + t * dx, ay + t * dy), self.cum[i] + t * (self.cum[i + 1] - self.cum[i])

    def project(self, p, from_m=0.0, to_m=math.inf):
        """구간 [from_m, to_m]과 겹치는 선분 중 가장 가까운 투영."""
        best = None
        for i in range(len(self.points) - 1):
            if self.cum[i + 1] < from_m or self.cum[i] > to_m:
                continue
            proj = self._project_segment(p, i)
            if best is None or proj[0] < best[0]:
                best = proj
        return best

    def first_within(self, p, max_offset, from_m=0.0):
        """from_m부터 선을 따라 max_offset 안에 처음 들어오는 투영(순환 코스에서 끝으로 건너뛰지 않게)."""
        for i in range(len(self.points) - 1):
            if self.cum[i + 1] < from_m:
                continue
            proj = self._project_segment(p, i)
            if proj[0] <= max_offset and proj[1] >= from_m:
                return proj
        return None


class PathProgress:
    def __init__(self, path: CoursePath):
        self.path = path
        self.progress = None
        self.last_pos = None
        self.moved_since_advance = 0.0  # 진행 거리가 마지막으로 늘어난 뒤 움직인 직선거리 합

    def update(self, pos):
        if not self.path.usable:
            return self.progress
        if self.last_pos is not None:
            self.moved_since_advance += dist(self.last_pos, pos)
        self.last_pos = pos
        current = self.progress
        last = current or 0.0
        near = self.path.project(pos, last - PROGRESS_BACK_M, last + PROGRESS_AHEAD_M)
        if near is None or near[0] > OFF_COURSE_M:
            near = self.path.first_within(pos, OFF_COURSE_M, last)
        if near is None:
            return self.progress
        # 한 번에 늘 수 있는 양은 실제로 움직인 거리 + 여유까지(유턴 후 순환 코스 끝으로 건너뛰지 않게). 첫 위치는 그대로 받는다.
        if current is not None and near[1] - last > self.moved_since_advance + PROGRESS_JUMP_SLACK_M:
            return self.progress
        if near[1] > last:
            self.moved_since_advance = 0.0
        self.progress = max(near[1], last)  # 뒤로 가지 않는다
        return self.progress


class ManeuverTracker:
    def __init__(self, maneuvers, path: CoursePath):
        self.ordered = sorted(maneuvers, key=lambda m: m["index"])
        self.trusted = [path.usable and self._on_path(path, m) for m in self.ordered]
        self.cursor = 0

    @staticmethod
    def _on_path(path, m) -> bool:
        d = m["distanceFromStartM"]
        proj = path.project((m["lat"], m["lon"]), d - MANEUVER_MATCH_M, d + MANEUVER_MATCH_M)
        return proj is not None and proj[0] <= MANEUVER_REACHED_M and abs(proj[1] - d) <= MANEUVER_MATCH_M

    def update(self, pos, progress, include):
        while self.cursor < len(self.ordered):
            m = self.ordered[self.cursor]
            if progress is not None and self.trusted[self.cursor]:
                passed = progress > m["distanceFromStartM"] + MANEUVER_REACHED_M
            else:
                passed = dist(pos, (m["lat"], m["lon"])) <= MANEUVER_REACHED_M
            if not passed:
                break
            self.cursor += 1
        i = self.cursor
        while i < len(self.ordered) and not include(self.ordered[i]):
            i += 1
        if i >= len(self.ordered):
            return None
        m = self.ordered[i]
        if progress is not None and self.trusted[i]:
            return m, max(m["distanceFromStartM"] - progress, 0.0)
        return m, dist(pos, (m["lat"], m["lon"]))


def school_zone(cautions):
    """(aheadM, inside, cautionPresent). remainM ≤ 0이면 구역 안, 1..300이면 예고 대상. 상태 없음."""
    remains = [c["remainM"] for c in cautions if c["type"] == "SCHOOL_ZONE"]
    if not remains:
        return None, False, False
    nearest = min(remains)
    return (nearest if 1 <= nearest <= SCHOOL_AHEAD_M else None), nearest <= 0, True


class SustainedOver:
    def __init__(self, sustain_ms):
        self.sustain = sustain_ms
        self.since = None
        self.in_episode = False

    def reset(self):
        self.since, self.in_episode = None, False

    def update(self, speed, threshold, now) -> bool:
        if speed is None or threshold is None:
            self.reset()
            return False
        if speed <= threshold:
            self.since = None
            if speed <= threshold - HYSTERESIS_KMH:
                self.in_episode = False
            return False
        if self.since is None:
            self.since = now
        if not self.in_episode and now - self.since >= self.sustain:
            self.in_episode = True
            return True
        return False


def expected_signal(kind):
    return {"LEFT": "LEFT", "UTURN": "LEFT", "RIGHT": "RIGHT"}.get(kind)


def practice_skills(maneuvers) -> set[str]:
    """SkillPreviewRule.practiceSkills: 보호구역은 전용 규칙이 있어 빼고, 우회전은 우회전뿐인 코스에서만."""
    skills = {m["skill"] for m in maneuvers} - {"SCHOOL_ZONE"}
    return skills - {"RIGHT_TURN"} if any(s != "RIGHT_TURN" for s in skills) else skills


@dataclass
class Result:
    spoken: list = field(default_factory=list)  # (t ms, type)
    events: list = field(default_factory=list)  # (t ms, DriveEventType)

    def spoken_types(self) -> list[str]:
        return [t for _, t in self.spoken]

    def event_types(self) -> list[str]:
        return [t for _, t in self.events]


def run(course: dict, frames: list[dict], enabled: frozenset[str] = DEFAULT_ENABLED) -> Result:
    """enabled: 설정에서 켠 피드백 타입(기본값 = 앱 기본 설정). NEW_WARNING은 끌 수 없다.
    꺼진 타입도 규칙 판정·기록(이벤트)은 그대로 하고 말할 후보에서만 뺀다(앱 FeedbackEngine과 같다)."""
    enabled = enabled | {"NEW_WARNING"}
    res = Result()
    speed_est = SpeedEstimator()
    turns = TurnTracker()
    path = CoursePath(course["path"])
    progress = PathProgress(path)
    mtrack = ManeuverTracker(course["maneuvers"], path)
    practice = practice_skills(course["maneuvers"])

    # 규칙 상태
    prev_warn = None
    pending_warn: list[str] = []
    ahead_announced = False
    school_over = SustainedOver(SCHOOL_SUSTAIN_MS)
    school_spoken = False
    speed_over = SustainedOver(SPEED_SUSTAIN_MS)
    speed_spoken = False
    judged_id = -1
    nagged_id = -1
    previewed: set[int] = set()
    brake_hist: deque = deque()
    brake_count = 0
    last_hard = None
    in_hard = False
    brake_spoken = False
    far_since = None
    off_course = False

    last_spoken = None
    last_by_type: dict[str, int] = {}

    for f in frames:
        now = f["t"]
        pos = (f["lat"], f["lon"])
        speed_est.update(pos, now)
        speed = speed_est.current(now)
        quick = speed_est.current_short(now)
        turn = turns.update(f["nextTurn"], pos, now)

        # OffCourseDetector
        proj = path.project(pos) if path.usable else None
        if proj is None or proj[0] <= OFF_COURSE_M:
            far_since, off_course = None, False
        else:
            far_since = now if far_since is None else far_since
            if not off_course and now - far_since >= OFF_COURSE_SUSTAIN_MS:
                off_course = True
                res.events.append((now, "OFF_COURSE"))

        ahead_m, inside, caution_present = school_zone(f["caution"])
        nm = mtrack.update(pos, progress.update(pos), lambda m: m["skill"] in practice)

        cands: list[str] = []
        # NEW_WARNING
        active = set(f["warnings"])
        if prev_warn is not None:
            for w in sorted(active - prev_warn):
                res.events.append((now, "NEW_WARNING"))
                pending_warn.append(w)
        prev_warn = active
        pending_warn = [w for w in pending_warn if w in active]
        if pending_warn:
            cands.append("NEW_WARNING")
        # SCHOOL_ZONE_AHEAD
        if not caution_present and not inside:
            ahead_announced = False
        if not ahead_announced and ahead_m is not None:
            cands.append("SCHOOL_ZONE_AHEAD")
        # SCHOOL_ZONE_SPEED: 지점 제한속도, 없으면 30
        if not inside:
            school_over.reset()
            school_spoken = False
        else:
            posted = f["speedLimitKmh"] if f["speedLimitKmh"] and f["speedLimitKmh"] > 0 else None
            if school_over.update(speed, posted or SCHOOL_FALLBACK_KMH, now):
                res.events.append((now, "SCHOOL_ZONE_SPEEDING"))
            if school_over.in_episode and not school_spoken:
                cands.append("SCHOOL_ZONE_SPEED")
        # SPEED_LIMIT_SUSTAINED
        limit = f["speedLimitKmh"] if f["speedLimitKmh"] and f["speedLimitKmh"] > 0 else None
        if speed_over.update(speed, limit + OVER_LIMIT_KMH if limit else None, now):
            speed_spoken = False
            res.events.append((now, "SPEEDING"))
        if speed_over.in_episode and not speed_spoken:
            cands.append("SPEED_LIMIT_SUSTAINED")
        # TURN_SIGNAL_MISSING + 기록 판정(TurnSignalJudge)
        signal = f["turnSignal"]
        if turn is not None:
            exp = expected_signal(turn.kind)
            if off_course:
                judged_id = turn.id
            elif exp and turn.id != judged_id and turn.distance <= JUDGE_M and turn.max_seen >= MIN_SPACING_M:
                judged_id = turn.id
                res.events.append((now, "TURN_SIGNAL_OK" if signal == exp else "TURN_SIGNAL_MISSED"))
            if exp and turn.id != nagged_id and signal != exp and turn.distance <= NAG_M:
                cands.append("TURN_SIGNAL_MISSING")
        # SKILL_PREVIEW
        if nm is not None:
            m, d = nm
            if m["index"] not in previewed and PREVIEW_MIN_M <= d <= PREVIEW_MAX_M:
                cands.append("SKILL_PREVIEW")
        # HARD_BRAKE_REPEAT: 평활하지 않은 짧은 창 속도로 1초 감속을 잰다
        braking = f["brake"] is True
        if not braking:
            in_hard = False
        if quick is None:
            brake_hist.clear()
        else:
            brake_hist.append((now, quick, speed))
            while brake_hist and now - brake_hist[0][0] > HARD_WINDOW_MS:
                brake_hist.popleft()
            # 창 안 짧은 창 속도가 가장 높은 표본(동률이면 앞)이 감속 시작. 그때 평활 속도가 있어야 하고 10 이상
            _, start, start_smoothed = max(brake_hist, key=lambda h: h[1])
            drop = start - quick
            min_drop = HARD_DROP_LOW_KMH if start <= HARD_SPLIT_KMH else HARD_DROP_HIGH_KMH
            was_moving = (start_smoothed or 0.0) >= HARD_MIN_START_SMOOTHED_KMH
            if braking and not in_hard and was_moving and drop >= min_drop and quick >= HARD_MIN_END_KMH:
                in_hard = True
                brake_count += 1
                last_hard = now
                res.events.append((now, "HARD_BRAKE"))
            if last_hard is not None and not (
                brake_spoken or braking or brake_count < HARD_REPEAT or now - last_hard < HARD_QUIET_MS
            ):
                cands.append("HARD_BRAKE_REPEAT")

        # FeedbackArbiter
        cands = [c for c in cands if c in enabled]
        if turns.near or not cands:
            continue
        for c in sorted(cands, key=PRIORITY.get):
            if PRIORITY[c] != 1:
                if len(res.spoken) >= MAX_MESSAGES:
                    continue
                if last_spoken is not None and now - last_spoken < MIN_GAP_MS:
                    continue
                if c in last_by_type and now - last_by_type[c] < SAME_TYPE_COOLDOWN_MS:
                    continue
            last_spoken = now
            last_by_type[c] = now
            res.spoken.append((now, c))
            if c == "NEW_WARNING":
                pending_warn.clear()  # 동시에 켜진 경고는 한 문장으로 말한다(기록은 경고마다)
            elif c == "SCHOOL_ZONE_AHEAD":
                ahead_announced = True
            elif c == "SCHOOL_ZONE_SPEED":
                school_spoken = True
            elif c == "SPEED_LIMIT_SUSTAINED":
                speed_spoken = True
            elif c == "TURN_SIGNAL_MISSING":
                nagged_id = turn.id
            elif c == "SKILL_PREVIEW":
                previewed.add(nm[0]["index"])
            elif c == "HARD_BRAKE_REPEAT":
                brake_spoken = True
            break
    return res
