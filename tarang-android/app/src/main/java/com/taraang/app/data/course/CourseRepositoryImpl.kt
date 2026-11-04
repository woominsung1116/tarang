package com.taraang.app.data.course

import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.Clock
import com.taraang.app.core.LatLng
import com.taraang.app.core.Logger
import com.taraang.app.domain.model.Course
import com.taraang.app.domain.model.CourseSource
import com.taraang.app.domain.model.Skill
import com.taraang.app.domain.repository.CourseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 코스 서버. [CourseApiClient]가 구현한다. */
fun interface CourseRemote {
    suspend fun fetch(origin: LatLng, level: Int, focus: Set<Skill>): AppResult<CourseFetch>

    /** 목표 경로 분해(`/v1/route-skills`). 기본은 미지원. */
    suspend fun routeSkills(origin: LatLng, destination: LatLng): AppResult<Map<Skill, Int>> =
        AppResult.Err(AppError.Server(0, SERVER_DISABLED, "경로 분석 미지원"))

    /** false면 서버를 부르지 않고 바로 캐시·번들로 간다(서버 주소 미설정). 오류가 아니다. */
    suspend fun isEnabled(): Boolean = true

    companion object {
        /** 서버 주소가 없어(release 기본) 서버 기능을 쓸 수 없음. userMessage가 이 코드를 구분해 안내한다. */
        const val SERVER_DISABLED = "SERVER_DISABLED"
    }
}

/** 마지막 성공 응답 원문과 저장 시각. */
data class CachedCourses(val body: String, val savedAtMs: Long)

/** 마지막 성공 응답을 단계별로 보관한다. */
interface CourseCacheStore {
    suspend fun read(level: Int): CachedCourses?
    suspend fun write(level: Int, entry: CachedCourses)
}

/** 앱 번들 코스(assets/courses) 원문. */
fun interface BundledCourseSource {
    suspend fun read(): String?
}

/**
 * 서버 → 마지막 성공 캐시 → 번들 순으로 대체한다(ARCHITECTURE.md 3장). 예외를 밖으로 던지지 않는다.
 * 모두 실패하면 서버 오류를 그대로 돌려준다(화면은 userMessage로 안내).
 *
 * 캐시 유효기간은 [CACHE_FRESH_MS](24시간). 지난 캐시도 번들보다는 내 위치에 가까우므로 대체로 쓰되
 * [CACHE_MAX_AGE_MS](7일)를 넘으면 버린다. 어느 쪽이든 Course.source=CACHE로 "저장된 코스"임을 표시한다.
 */
class CourseRepositoryImpl(
    private val remote: CourseRemote,
    private val cache: CourseCacheStore,
    private val bundled: BundledCourseSource,
    private val logger: Logger,
    private val clock: Clock = Clock.System,
    /** 캐시·번들 읽기와 JSON 해석을 메인 밖에서 하도록 한다. */
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CourseRepository {

    private val mutex = Mutex()

    /** 최근 추천 결과. 화면이 id로 다시 찾을 수 있게 하되 무한히 쌓이지 않게 오래된 것부터 버린다. */
    private val known = object : LinkedHashMap<String, Course>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Course>?) = size > MAX_REMEMBERED
    }
    @Volatile
    private var bundledParsed: ParsedCourses? = null

    /** 앱 시작 시 번들을 미리 읽어 [demoOrigin]이 번들 출발지를 돌려주게 한다. */
    suspend fun warmUp() {
        withContext(workDispatcher) { bundled() }
    }

    /** [focus]는 서버에만 넘긴다. 캐시·번들 대체는 focus와 무관하게 그 단계 코스를 준다. */
    override suspend fun recommend(origin: LatLng, level: Int, focus: Set<Skill>): AppResult<List<Course>> = withContext(workDispatcher) {
        recommendInternal(origin, level, focus)
    }

    /** 목표 경로 분해는 서버 계산값이라 캐시·번들 대체가 없다. 서버를 안 쓰는 빌드면 [CourseRemote.SERVER_DISABLED]. */
    override suspend fun analyzeRoute(origin: LatLng, destination: LatLng): AppResult<Map<Skill, Int>> = withContext(workDispatcher) {
        if (!remote.isEnabled()) return@withContext AppResult.Err(AppError.Server(0, CourseRemote.SERVER_DISABLED, "코스 서버 미사용"))
        safely { remote.routeSkills(origin, destination) }
            .also { if (it is AppResult.Err) logger.w(TAG, "경로 분석 실패: ${it.error.debugMessage}") }
    }

    private suspend fun recommendInternal(origin: LatLng, level: Int, focus: Set<Skill>): AppResult<List<Course>> {
        if (!remote.isEnabled()) {
            loadCached(level)?.let { return remember(it) }
            loadBundled(level)?.let { return remember(it) }
            return AppResult.Err(AppError.NoCourse("코스 서버 미사용, 저장·번들 코스 없음"))
        }
        val serverError = when (val server = safely { remote.fetch(origin, level, focus) }) {
            is AppResult.Ok -> {
                val parsed = server.value.parsed
                if (parsed.dropped > 0) logger.w(TAG, "서버 응답에서 검증 실패 코스 ${parsed.dropped}개 버림")
                // 요청한 단계가 아닌 코스는 쓰지 않는다(서버를 믿지 않는다).
                val courses = parsed.courses.filter { it.level == level }
                if (courses.isNotEmpty()) {
                    safely { cache.write(level, CachedCourses(server.value.body, clock.nowMs())); AppResult.Ok(Unit) }
                        .let { if (it is AppResult.Err) logger.w(TAG, "코스 캐시 저장 실패: ${it.error.debugMessage}") }
                    return remember(courses)
                }
                AppError.NoCourse("서버 응답에 L$level 코스 없음")
            }
            is AppResult.Err -> server.error
        }
        logger.w(TAG, "서버 코스 실패, 대체 사용: ${serverError.debugMessage}")

        loadCached(level)?.let { return remember(it) }
        loadBundled(level)?.let { return remember(it) }
        return AppResult.Err(serverError)
    }

    override suspend fun get(courseId: String): Course? = withContext(workDispatcher) { find(courseId) }

    private suspend fun find(courseId: String): Course? {
        mutex.withLock { known[courseId] }?.let { return it }
        (1..4).forEach { level -> loadCached(level)?.firstOrNull { it.id == courseId }?.let { return it } }
        return bundled()?.courses?.firstOrNull { it.id == courseId }
    }

    override fun demoOrigin(): LatLng = bundledParsed?.origin ?: DEFAULT_DEMO_ORIGIN

    private suspend fun remember(courses: List<Course>): AppResult<List<Course>> {
        mutex.withLock { courses.forEach { known[it.id] = it } }
        return AppResult.Ok(courses)
    }

    private suspend fun loadCached(level: Int): List<Course>? {
        val entry = safely { AppResult.Ok(cache.read(level)) }.let { (it as? AppResult.Ok)?.value } ?: return null
        val ageMs = clock.nowMs() - entry.savedAtMs
        if (ageMs > CACHE_MAX_AGE_MS) {
            logger.d(TAG, "코스 캐시가 너무 오래돼 무시(L$level)")
            return null
        }
        if (ageMs !in 0..CACHE_FRESH_MS) logger.d(TAG, "유효기간 지난 캐시를 대체로 사용(L$level)")
        return when (val parsed = CourseJson.parse(entry.body, CourseSource.CACHE)) {
            is AppResult.Ok -> parsed.value.courses.filter { it.level == level }.takeIf { it.isNotEmpty() }
            is AppResult.Err -> {
                logger.w(TAG, "코스 캐시 손상, 무시: ${parsed.error.debugMessage}")
                null
            }
        }
    }

    /** 요청 단계가 번들에 없으면 시연이 막히지 않도록 번들 전체를 준다(각 Course.level로 구분 가능). */
    private suspend fun loadBundled(level: Int): List<Course>? {
        val all = bundled()?.courses?.takeIf { it.isNotEmpty() } ?: return null
        return all.filter { it.level == level }.ifEmpty { all }
    }

    private suspend fun bundled(): ParsedCourses? {
        bundledParsed?.let { return it }
        val text = safely { AppResult.Ok(bundled.read()) }.let { (it as? AppResult.Ok)?.value } ?: return null
        return when (val parsed = CourseJson.parse(text, CourseSource.BUNDLED)) {
            is AppResult.Ok -> parsed.value.also { bundledParsed = it }
            is AppResult.Err -> {
                logger.e(TAG, "번들 코스 해석 실패: ${parsed.error.debugMessage}")
                null
            }
        }
    }

    /** 저장소 경계: 구현체 예외를 결과로 바꾼다. 취소는 다시 던진다. */
    private suspend fun <T> safely(block: suspend () -> AppResult<T>): AppResult<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Err(AppError.Unexpected(e.javaClass.simpleName))
        }

    companion object {
        private const val TAG = "CourseRepo"
        const val CACHE_FRESH_MS = 24 * 60 * 60 * 1000L
        const val CACHE_MAX_AGE_MS = 7 * CACHE_FRESH_MS
        private const val MAX_REMEMBERED = 50
        /** 번들을 아직 못 읽었을 때 쓰는 부산대 정문 근처. */
        val DEFAULT_DEMO_ORIGIN = LatLng(35.2310, 129.0840)
    }
}
