package com.taraang.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** enum은 이름(String)으로 저장한다. 순서가 바뀌어도 기존 행이 깨지지 않게. */
@Entity(
    tableName = "sessions",
    indices = [Index("courseId"), Index("startedAtMs")],
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    /** 단계 진행 계산용. 코스를 나중에 못 찾아도 단계는 남는다. */
    val courseLevel: Int,
    val mode: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val interrupted: Boolean,
    /** v2: 주행 시작 시 코스 전체 스냅샷(JSON). 같은 id의 코스가 나중에 바뀌어도 기록은 원래 코스를 보여 준다. v1 행은 null. */
    val courseJson: String? = null,
    /** v2: 단계 진행 판정용 코스 거리. v1 행은 null. */
    val courseDistanceM: Int? = null,
    /** v2: 코스 경로를 따라 실제로 진행한 거리. 모르면 null. */
    val drivenM: Double? = null,
)

@Entity(
    tableName = "events",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    // 같은 이벤트를 두 번 써도 한 줄만 남게 한다(재시도·중복 호출에 멱등).
    indices = [Index("sessionId"), Index(value = ["sessionId", "type", "timestampMs", "detail"], unique = true)],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sessionId: String,
    val type: String,
    val timestampMs: Long,
    val lat: Double?,
    val lon: Double?,
    val maneuverIndex: Int?,
    val detail: String,
)

@Entity(
    tableName = "feelings",
    primaryKeys = ["sessionId", "maneuverIndex"],
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
)
data class FeelingEntity(
    val sessionId: String,
    val maneuverIndex: Int,
    val feeling: String,
)

/** skills·hardSpots는 구조가 작고 함께만 읽으므로 JSON 문자열로 둔다. */
@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey val id: String,
    val name: String,
    val originLat: Double,
    val originLon: Double,
    val destinationLat: Double,
    val destinationLon: Double,
    val skillsJson: String,
    val hardSpotsJson: String,
    val createdAtMs: Long,
)
