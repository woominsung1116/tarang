package com.taraang.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 마이그레이션 정책: 주행 기록은 사용자가 쌓은 연습 이력이라 지우지 않는다.
 * - fallbackToDestructiveMigration은 쓰지 않는다. 스키마를 바꾸면 version을 올리고 `Migration(n, n+1)`을 추가한다.
 * - 내보낸 스키마(app/schemas/.../N.json)는 그 버전이 나간 뒤 고치지 않는다. 1.json은 v1으로 동결했다.
 * - 마이그레이션 SQL은 상수 목록([MIGRATION_1_2_SQL])으로 두고, MigrationSqlTest가 N.json으로 만든 SQLite에 그대로 돌려
 *   (1) 기존 행이 남는지 (2) 결과 표 구조가 N+1.json과 같은지 확인한다. 새 버전마다 같은 테스트를 추가한다.
 * - 열 추가는 nullable 또는 기본값이 있는 ALTER TABLE, 그 밖의 변경은 새 테이블 생성 → 복사 → 교체 순서로 한다.
 */
@Database(
    entities = [SessionEntity::class, EventEntity::class, FeelingEntity::class, GoalEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class TaraangDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun eventDao(): EventDao
    abstract fun feelingDao(): FeelingDao
    abstract fun goalDao(): GoalDao

    companion object {
        private const val NAME = "taraang.db"

        fun create(context: Context): TaraangDatabase =
            Room.databaseBuilder(context.applicationContext, TaraangDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

/** v1 → v2: 세션에 코스 스냅샷·코스 거리·진행 거리 열 추가(모두 nullable, 기존 행은 null). */
val MIGRATION_1_2_SQL = listOf(
    "ALTER TABLE `sessions` ADD COLUMN `courseJson` TEXT",
    "ALTER TABLE `sessions` ADD COLUMN `courseDistanceM` INTEGER",
    "ALTER TABLE `sessions` ADD COLUMN `drivenM` REAL",
)

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_1_2_SQL.forEach(db::execSQL)
    }
}

/** 여러 쓰기를 한 트랜잭션으로 묶는다. 저장소는 이것만 알고, 테스트에서는 [Direct]로 그냥 실행한다. */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T

    companion object {
        val Direct = object : TransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        }
    }
}

fun TaraangDatabase.transactionRunner(): TransactionRunner = object : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = withTransaction { block() }
}
