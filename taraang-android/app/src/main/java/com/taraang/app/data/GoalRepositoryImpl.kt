package com.taraang.app.data

import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.Geo
import com.taraang.app.core.Logger
import com.taraang.app.core.runCatchingApp
import com.taraang.app.data.db.GoalDao
import com.taraang.app.data.db.toDomain
import com.taraang.app.data.db.toEntity
import com.taraang.app.domain.model.GoalRoute
import com.taraang.app.domain.repository.GoalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

class GoalRepositoryImpl(
    private val dao: GoalDao,
    private val logger: Logger,
) : GoalRepository {

    override val goals: Flow<List<GoalRoute>> = dao.observeAll()
        .map { list -> list.map { it.toDomain() } }
        .catch { e ->
            logger.e(TAG, "목표 경로 읽기 실패", e)
            emit(emptyList())
        }

    override suspend fun save(goal: GoalRoute): AppResult<Unit> {
        if (goal.name.isBlank() || goal.name.length > MAX_NAME_LENGTH) return AppResult.Err(AppError.InvalidInput("이름 길이"))
        if (!Geo.isInKorea(goal.origin) || !Geo.isInKorea(goal.destination)) return AppResult.Err(AppError.InvalidInput("좌표 범위"))
        return runCatchingApp({ AppError.Storage(it.javaClass.simpleName) }) { dao.upsert(goal.toEntity()) }
    }

    override suspend fun delete(goalId: String): AppResult<Unit> =
        runCatchingApp({ AppError.Storage(it.javaClass.simpleName) }) { dao.delete(goalId); Unit }

    private companion object {
        const val TAG = "GoalRepo"
        const val MAX_NAME_LENGTH = 40
    }
}
