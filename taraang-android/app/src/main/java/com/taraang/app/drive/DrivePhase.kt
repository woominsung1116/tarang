package com.taraang.app.drive

/**
 * 주행 상태 기계(ARCHITECTURE.md 7장 12번).
 * Idle → Starting → Running → Stopping → Finished/Failed, 시작 실패는 Starting → Failed.
 * Finished/Failed에서는 다시 시작할 수 있다. 그 밖의 전이는 거부한다(중복 시작 방지).
 */
enum class DrivePhase {
    IDLE, STARTING, RUNNING, STOPPING, FINISHED, FAILED;

    fun canMoveTo(next: DrivePhase): Boolean = next in allowed.getValue(this)

    private companion object {
        val allowed = mapOf(
            IDLE to setOf(STARTING),
            STARTING to setOf(RUNNING, FAILED),
            RUNNING to setOf(STOPPING),
            STOPPING to setOf(FINISHED, FAILED),
            FINISHED to setOf(STARTING),
            FAILED to setOf(STARTING),
        )
    }
}
