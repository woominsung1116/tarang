package com.taraang.app.data.db

import com.taraang.app.core.LatLng
import com.taraang.app.domain.model.DriveEvent
import com.taraang.app.domain.model.DriveEventType
import com.taraang.app.domain.model.DriveSession
import com.taraang.app.domain.model.Feeling
import com.taraang.app.domain.model.GoalRoute
import com.taraang.app.domain.model.HardSpot
import com.taraang.app.domain.model.SegmentFeeling
import com.taraang.app.domain.model.SignalMode
import com.taraang.app.domain.model.Skill
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

internal fun SessionEntity.toDomain() = DriveSession(
    id = id,
    courseId = courseId,
    mode = enumValues<SignalMode>().firstOrNull { it.name == mode } ?: SignalMode.LIVE,
    startedAtMs = startedAtMs,
    endedAtMs = endedAtMs,
    interrupted = interrupted,
    drivenM = drivenM,
)

internal fun DriveEvent.toEntity() = EventEntity(
    sessionId = sessionId,
    type = type.name,
    timestampMs = timestampMs,
    lat = position?.lat,
    lon = position?.lon,
    maneuverIndex = maneuverIndex,
    detail = detail,
)

/** 저장 후 enum이 빠졌으면 그 행은 버린다(앱이 죽지 않게). */
internal fun EventEntity.toDomainOrNull(): DriveEvent? {
    val type = enumValues<DriveEventType>().firstOrNull { it.name == type } ?: return null
    val position = if (lat != null && lon != null) LatLng(lat, lon) else null
    return DriveEvent(sessionId, type, timestampMs, position, maneuverIndex, detail)
}

internal fun SegmentFeeling.toEntity() = FeelingEntity(sessionId, maneuverIndex, feeling.name)

internal fun FeelingEntity.toDomainOrNull(): SegmentFeeling? =
    enumValues<Feeling>().firstOrNull { it.name == feeling }?.let { SegmentFeeling(sessionId, maneuverIndex, it) }

@Serializable
private data class HardSpotJson(val id: String, val lat: Double, val lon: Double, val label: String)

private val skillsSerializer = MapSerializer(String.serializer(), Int.serializer())
private val hardSpotsSerializer = ListSerializer(HardSpotJson.serializer())

internal fun GoalRoute.toEntity() = GoalEntity(
    id = id,
    name = name,
    originLat = origin.lat,
    originLon = origin.lon,
    destinationLat = destination.lat,
    destinationLon = destination.lon,
    skillsJson = json.encodeToString(skillsSerializer, skills.mapKeys { it.key.name }),
    hardSpotsJson = json.encodeToString(hardSpotsSerializer, hardSpots.map { HardSpotJson(it.id, it.position.lat, it.position.lon, it.label) }),
    createdAtMs = createdAtMs,
)

internal fun GoalEntity.toDomain(): GoalRoute {
    val skills = runCatching { json.decodeFromString(skillsSerializer, skillsJson) }.getOrDefault(emptyMap())
        .mapNotNull { (k, v) -> enumValues<Skill>().firstOrNull { it.name == k }?.let { it to v } }
        .toMap()
    val spots = runCatching { json.decodeFromString(hardSpotsSerializer, hardSpotsJson) }.getOrDefault(emptyList())
        .map { HardSpot(it.id, LatLng(it.lat, it.lon), it.label) }
    return GoalRoute(id, name, LatLng(originLat, originLon), LatLng(destinationLat, destinationLon), skills, spots, createdAtMs)
}
