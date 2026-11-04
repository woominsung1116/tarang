import re

import pytest

from tarang_course.levels import LEVELS, user_message, violations


def amounts(level: int, counts: dict[str, int]) -> int:
    return sum(v.amount for v in violations(LEVELS[level], counts))


def test_level1_right_turns_only_is_strict():
    assert violations(LEVELS[1], {"RIGHT_TURN": 5}) == []


@pytest.mark.parametrize(
    "counts, penalty",
    [
        ({"RIGHT_TURN": 3, "LEFT_TURN_UNKNOWN": 1}, 1),
        ({"LEFT_TURN_UNKNOWN": 2, "U_TURN": 1}, 3),
        ({"LEFT_TURN_SIGNALIZED": 3}, 1),  # 좌회전 최대 2
        ({"ROUNDABOUT": 1}, 1),
    ],
)
def test_level1_violations(counts, penalty):
    assert amounts(1, counts) == penalty


def test_unknown_left_violation_is_data_not_text():
    (v,) = violations(LEVELS[1], {"RIGHT_TURN": 3, "LEFT_TURN_UNKNOWN": 2})
    assert (v.kind, v.skill, v.count, v.limit, v.amount) == ("max", "LEFT_TURN_UNKNOWN", 2, 0, 2)
    assert v.as_debug() == {"kind": "max", "skill": "LEFT_TURN_UNKNOWN", "count": 2, "limit": 0}
    assert user_message(v) is None  # 신호등 정보 없는 좌회전은 코스 경고가 따로 알린다


def test_level2_needs_two_signalized_lefts():
    assert amounts(2, {"LEFT_TURN_SIGNALIZED": 2, "RIGHT_TURN": 1}) == 0
    (v,) = violations(LEVELS[2], {"LEFT_TURN_SIGNALIZED": 1})
    assert v.amount == 1 and v.kind == "min"
    assert user_message(v) == "신호 있는 좌회전이 1곳뿐이에요"
    (v,) = violations(LEVELS[2], {"RIGHT_TURN": 4})
    assert v.amount == 2 and user_message(v) == "신호 있는 좌회전이 없어요"
    assert amounts(2, {"LEFT_TURN_SIGNALIZED": 2, "LEFT_TURN_UNKNOWN": 1}) == 1


def test_level3_combines_skills_and_forbids_unknown_left():
    assert amounts(3, {"RIGHT_TURN": 3, "LEFT_TURN_SIGNALIZED": 1}) == 0
    assert amounts(3, {"RIGHT_TURN": 3, "ROUNDABOUT": 1, "U_TURN": 1}) == 0
    (v,) = violations(LEVELS[3], {"RIGHT_TURN": 4})
    assert v.skill is None and user_message(v) == "연습할 기술 종류가 적은 코스예요"
    assert amounts(3, {"RIGHT_TURN": 2, "LEFT_TURN_UNKNOWN": 1}) == 1
    assert amounts(3, {"RIGHT_TURN": 2, "LEFT_TURN_SIGNALIZED": 1, "U_TURN": 2}) == 1


def test_level4_allows_unknown_left():
    assert amounts(4, {"RIGHT_TURN": 4, "LEFT_TURN_UNKNOWN": 5}) == 0
    assert amounts(4, {"RIGHT_TURN": 4, "U_TURN": 3}) == 1


@pytest.mark.parametrize(
    "level, counts, text",
    [
        (1, {"U_TURN": 1}, "유턴이 1곳 있어요"),
        (1, {"ROUNDABOUT": 2}, "회전교차로가 2곳 있어요"),
        (1, {"LEFT_TURN_SIGNALIZED": 3}, "좌회전이 3곳으로 많은 편이에요"),
    ],
)
def test_user_messages_are_plain_korean(level, counts, text):
    (v,) = violations(LEVELS[level], counts)
    assert user_message(v) == text
    assert not re.search(r"단계|기준|최대|최소|[A-Z_]{3,}", text)


def test_levels_get_longer_and_looser():
    assert [LEVELS[k].target_m for k in (1, 2, 3, 4)] == sorted(LEVELS[k].target_m for k in (1, 2, 3, 4))
    # 1·2단계는 고속도로·자동차전용도로 차단, 3·4단계도 고속도로는 차단
    for k in (1, 2, 3, 4):
        assert any("MOTORWAY" in r["if"] and r["multiply_by"] == "0" for r in LEVELS[k].custom_model["priority"])
    assert any("TRUNK" in r["if"] and r["multiply_by"] == "0" for r in LEVELS[2].custom_model["priority"])
    assert not any("PRIMARY" in r["if"] for r in LEVELS[3].custom_model["priority"])


@pytest.mark.parametrize("distance, ok", [(999, False), (1000, True), (2000, True), (3000, True), (3001, False), (18000, False)])
def test_distance_outlier_bounds_level1(distance, ok):
    assert LEVELS[1].distance_ok(distance) is ok
