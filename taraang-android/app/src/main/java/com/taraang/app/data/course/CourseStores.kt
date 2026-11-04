package com.taraang.app.data.course

import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileNotFoundException

@Serializable
private data class CacheEnvelope(val savedAtMs: Long, val body: String)

/**
 * filesDir 아래 단계별 파일 하나(최대 4개). 저장 시각을 함께 담는다.
 * 임시 파일에 쓰고 rename해서 쓰다 죽어도 반쪽 파일이 남지 않게 한다. 형식이 깨진 파일은 없는 것으로 본다.
 */
class FileCourseCache(private val dir: File) : CourseCacheStore {

    override suspend fun read(level: Int): CachedCourses? = withContext(Dispatchers.IO) {
        val file = fileFor(level)
        // 우리가 쓴 파일은 한도 안이다. 그보다 크면 손상·변조로 보고 읽지 않는다.
        if (!file.exists() || file.length() > MAX_FILE_BYTES) return@withContext null
        runCatching { CourseJson.json.decodeFromString(CacheEnvelope.serializer(), file.readText()) }
            .getOrNull()
            ?.let { CachedCourses(it.body, it.savedAtMs) }
    }

    override suspend fun write(level: Int, entry: CachedCourses) = withContext(Dispatchers.IO) {
        if (entry.body.length > MAX_BODY_CHARS) error("캐시 본문이 너무 큼")
        dir.mkdirs()
        val tmp = File(dir, "courses_L$level.json.tmp")
        tmp.writeText(CourseJson.json.encodeToString(CacheEnvelope.serializer(), CacheEnvelope(entry.savedAtMs, entry.body)))
        if (!tmp.renameTo(fileFor(level))) {
            tmp.delete()
            error("캐시 파일 교체 실패")
        }
    }

    private fun fileFor(level: Int) = File(dir, "courses_L$level.json")

    private companion object {
        /** 코스 5개 응답은 수십 KB. 비정상적으로 큰 응답이 저장소를 채우지 않게 막는다. */
        const val MAX_BODY_CHARS = 2_000_000
        /** UTF-8 한글 3바이트 + JSON 이스케이프 여유. */
        const val MAX_FILE_BYTES = 8L * 1024 * 1024
    }
}

class AssetBundledCourses(private val assets: AssetManager, private val path: String = DEFAULT_PATH) : BundledCourseSource {

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        try {
            assets.open(path).bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            null
        }
    }

    companion object {
        const val DEFAULT_PATH = "courses/demo_pnu.json"
    }
}
