package com.taraang.app.data.course

import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.Clock
import com.taraang.app.core.LatLng
import com.taraang.app.core.Logger
import com.taraang.app.domain.model.CourseSource
import com.taraang.app.domain.model.Skill
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseRepositoryImplTest {

    private val origin = LatLng(35.2310, 129.0840)
    private val sample = fixture("courses/sample.json")
    private var now = 1_000_000_000L
    private val clock = Clock { now }

    private class MemoryCache(val stored: MutableMap<Int, CachedCourses> = mutableMapOf(), val failRead: Boolean = false) : CourseCacheStore {
        override suspend fun read(level: Int): CachedCourses? = if (failRead) error("디스크 오류") else stored[level]
        override suspend fun write(level: Int, entry: CachedCourses) {
            stored[level] = entry
        }
    }

    private fun serverOk(body: String = sample) = CourseRemote { _, _, _ ->
        AppResult.Ok(CourseFetch(body, (CourseJson.parse(body, CourseSource.SERVER) as AppResult.Ok).value))
    }

    private val serverDown = CourseRemote { _, _, _ -> AppResult.Err(AppError.Network("down")) }

    private fun cached(body: String = sample, ageMs: Long = 0) = CachedCourses(body, now - ageMs)

    private fun repo(remote: CourseRemote, cache: CourseCacheStore = MemoryCache(), bundled: String? = sample) =
        CourseRepositoryImpl(remote, cache, { bundled }, Logger.Noop, clock)

    @Test
    fun `disabled server is never called and cache or bundle is used`() = runBlocking {
        var calls = 0
        val disabled = object : CourseRemote {
            override suspend fun fetch(origin: LatLng, level: Int, focus: Set<Skill>): AppResult<CourseFetch> {
                calls++
                return AppResult.Err(AppError.Network("should not be called"))
            }
            override suspend fun isEnabled() = false
        }
        assertTrue((repo(disabled).recommend(origin, 2) as AppResult.Ok).value.all { it.source == CourseSource.BUNDLED })
        val fromCache = (repo(disabled, MemoryCache(mutableMapOf(1 to cached()))).recommend(origin, 1) as AppResult.Ok).value
        assertTrue(fromCache.all { it.source == CourseSource.CACHE })
        assertTrue((repo(disabled, bundled = null).recommend(origin, 1) as AppResult.Err).error is AppError.NoCourse)
        assertEquals(0, calls)
    }

    @Test
    fun `focus reaches the server, fallbacks ignore it`() = runBlocking {
        var sentFocus: Set<Skill>? = null
        val remote = CourseRemote { _, _, focus ->
            sentFocus = focus
            AppResult.Err(AppError.Network("down"))
        }
        val courses = (repo(remote).recommend(origin, 2, setOf(Skill.U_TURN)) as AppResult.Ok).value
        assertEquals(setOf(Skill.U_TURN), sentFocus)
        assertTrue(courses.all { it.source == CourseSource.BUNDLED && it.level == 2 })
    }

    @Test
    fun `route analysis without a server is a clear SERVER_DISABLED error, with a server it passes through`() = runBlocking {
        val disabled = object : CourseRemote {
            override suspend fun fetch(origin: LatLng, level: Int, focus: Set<Skill>): AppResult<CourseFetch> = error("not called")
            override suspend fun routeSkills(origin: LatLng, destination: LatLng): AppResult<Map<Skill, Int>> = error("not called")
            override suspend fun isEnabled() = false
        }
        val err = (repo(disabled).analyzeRoute(origin, LatLng(35.25, 129.1)) as AppResult.Err).error
        assertEquals(CourseRemote.SERVER_DISABLED, (err as AppError.Server).code)

        val enabled = object : CourseRemote {
            override suspend fun fetch(origin: LatLng, level: Int, focus: Set<Skill>): AppResult<CourseFetch> = error("not called")
            override suspend fun routeSkills(origin: LatLng, destination: LatLng): AppResult<Map<Skill, Int>> = AppResult.Ok(mapOf(Skill.U_TURN to 1))
        }
        assertEquals(AppResult.Ok(mapOf(Skill.U_TURN to 1)), repo(enabled).analyzeRoute(origin, LatLng(35.25, 129.1)))
        val throwing = object : CourseRemote {
            override suspend fun fetch(origin: LatLng, level: Int, focus: Set<Skill>): AppResult<CourseFetch> = error("not called")
            override suspend fun routeSkills(origin: LatLng, destination: LatLng): AppResult<Map<Skill, Int>> = error("boom")
        }
        assertTrue(repo(throwing).analyzeRoute(origin, LatLng(35.25, 129.1)) is AppResult.Err)
    }

    @Test
    fun `server courses of another level are not returned or cached`() = runBlocking {
        val cache = MemoryCache()
        // sample: L1 1개 + L2 1개. L2를 요청하면 L2만.
        val l2 = (repo(serverOk(), cache).recommend(origin, 2) as AppResult.Ok).value
        assertEquals(listOf("L2-35.2310-129.0840-s7"), l2.map { it.id })
        // L3 요청에 L1·L2만 오면 서버 결과를 버리고 캐시하지 않는다(번들 대체).
        val l3 = (repo(serverOk(), cache).recommend(origin, 3) as AppResult.Ok).value
        assertTrue(l3.all { it.source == CourseSource.BUNDLED })
        assertNull(cache.stored[3])
    }

    @Test
    fun `server success is used and cached with timestamp`() = runBlocking {
        val cache = MemoryCache()
        val courses = (repo(serverOk(), cache).recommend(origin, 1) as AppResult.Ok).value
        assertTrue(courses.all { it.source == CourseSource.SERVER })
        assertEquals(CachedCourses(sample, now), cache.stored[1])
    }

    @Test
    fun `server failure falls back to last success cache`() = runBlocking {
        val courses = (repo(serverDown, MemoryCache(mutableMapOf(1 to cached()))).recommend(origin, 1) as AppResult.Ok).value
        assertEquals(listOf("L1-35.2310-129.0840-s3"), courses.map { it.id })
        assertTrue(courses.all { it.source == CourseSource.CACHE })
    }

    @Test
    fun `cache older than 24h is still a fallback, older than 7 days is dropped`() = runBlocking {
        val stale = MemoryCache(mutableMapOf(1 to cached(ageMs = CourseRepositoryImpl.CACHE_FRESH_MS + 1)))
        assertTrue((repo(serverDown, stale).recommend(origin, 1) as AppResult.Ok).value.all { it.source == CourseSource.CACHE })
        val expired = MemoryCache(mutableMapOf(1 to cached(ageMs = CourseRepositoryImpl.CACHE_MAX_AGE_MS + 1)))
        assertTrue((repo(serverDown, expired).recommend(origin, 1) as AppResult.Ok).value.all { it.source == CourseSource.BUNDLED })
    }

    @Test
    fun `no cache falls back to bundled filtered by level`() = runBlocking {
        val courses = (repo(serverDown).recommend(origin, 2) as AppResult.Ok).value
        assertEquals(listOf("L2-35.2310-129.0840-s7"), courses.map { it.id })
        assertTrue(courses.all { it.source == CourseSource.BUNDLED })
    }

    @Test
    fun `bundled without requested level returns all bundled courses`() = runBlocking {
        assertEquals(2, (repo(serverDown).recommend(origin, 4) as AppResult.Ok).value.size)
    }

    @Test
    fun `corrupt or unreadable cache is skipped`() = runBlocking {
        val corrupt = (repo(serverDown, MemoryCache(mutableMapOf(1 to cached("{broken")))).recommend(origin, 1) as AppResult.Ok).value
        assertTrue(corrupt.all { it.source == CourseSource.BUNDLED })
        val unreadable = (repo(serverDown, MemoryCache(failRead = true)).recommend(origin, 1) as AppResult.Ok).value
        assertTrue(unreadable.all { it.source == CourseSource.BUNDLED })
    }

    @Test
    fun `server returning zero courses falls back`() = runBlocking {
        val empty = """{"origin":{"lat":35.23,"lon":129.08},"courses":[]}"""
        assertTrue((repo(serverOk(empty)).recommend(origin, 1) as AppResult.Ok).value.all { it.source == CourseSource.BUNDLED })
    }

    @Test
    fun `everything failing returns the server error`() = runBlocking {
        assertTrue((repo(serverDown, bundled = null).recommend(origin, 1) as AppResult.Err).error is AppError.Network)
    }

    @Test
    fun `throwing remote never escapes`() = runBlocking {
        val throwing = CourseRemote { _, _, _ -> throw IllegalStateException("boom") }
        assertTrue((repo(throwing).recommend(origin, 1) as AppResult.Ok).value.all { it.source == CourseSource.BUNDLED })
    }

    @Test
    fun `get returns server results of the latest recommend, keeping their source`() = runBlocking {
        // 번들에 없는 id만 가진 서버 응답: 번들에서 찾았다면 실패한다.
        val serverOnly = sample.replace("L1-35.2310-129.0840-s3", "L1-server-only")
        val r = repo(serverOk(serverOnly))
        r.recommend(origin, 1)
        val found = r.get("L1-server-only")
        assertEquals(CourseSource.SERVER, found?.source)
    }

    @Test
    fun `get returns cache results of the latest recommend, keeping their source`() = runBlocking {
        val cacheOnly = sample.replace("L1-35.2310-129.0840-s3", "L1-cache-only")
        val r = repo(serverDown, MemoryCache(mutableMapOf(1 to cached(cacheOnly))))
        r.recommend(origin, 1)
        assertEquals(CourseSource.CACHE, r.get("L1-cache-only")?.source)
    }

    @Test
    fun `get without recommend falls back to cache and bundled, unknown is null`() = runBlocking {
        assertEquals(CourseSource.BUNDLED, repo(serverDown).get("L2-35.2310-129.0840-s7")?.source)
        val cacheOnly = sample.replace("L1-35.2310-129.0840-s3", "L1-cache-only")
        assertEquals(CourseSource.CACHE, repo(serverDown, MemoryCache(mutableMapOf(1 to cached(cacheOnly)))).get("L1-cache-only")?.source)
        assertNull(repo(serverDown).get("nope"))
    }

    @Test
    fun `demo origin comes from bundle after warm up`() = runBlocking {
        val r = repo(serverDown, bundled = """{"origin":{"lat":35.5,"lon":129.5},"courses":[]}""")
        assertEquals(CourseRepositoryImpl.DEFAULT_DEMO_ORIGIN, r.demoOrigin())
        r.warmUp()
        assertEquals(LatLng(35.5, 129.5), r.demoOrigin())
    }
}
