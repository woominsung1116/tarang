package com.taraang.app.data.course

import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.LatLng
import com.taraang.app.core.Logger
import com.taraang.app.domain.model.Skill
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CourseApiClientTest {

    private lateinit var server: MockWebServer
    private val origin = LatLng(35.2310, 129.0840)
    private val sample = fixture("courses/sample.json")

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun client(
        http: OkHttpClient = CourseApiClient.defaultHttpClient(),
        retries: Int = 2,
        baseUrl: String? = null,
        io: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    ) = CourseApiClient(
        client = http,
        baseUrl = { baseUrl ?: server.url("/").toString() },
        logger = Logger.Noop,
        maxRetries = retries,
        backoffBaseMs = 1,
        ioDispatcher = io,
        // MockWebServer는 평문 localhost다(debug 빌드 조건).
        allowCleartext = true,
    )

    private fun error(status: Int, code: String) =
        MockResponse().setResponseCode(status).setBody("""{"error":{"code":"$code","message":"m"}}""")

    @Test
    fun `body over 2 MB is refused once without retry, 2 MB exactly is read`() = runBlocking {
        val max = CourseApiClient.MAX_BODY_BYTES.toInt()
        val padded = sample + " ".repeat(max - sample.toByteArray().size)
        server.enqueue(MockResponse().setBody(padded))
        assertTrue(client().fetchCourses(origin, 2, 3) is AppResult.Ok)

        server.enqueue(MockResponse().setBody("$padded "))
        val err = (client().fetchCourses(origin, 2, 3) as AppResult.Err).error
        assertEquals("TOO_LARGE", (err as AppError.Server).code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `blank server url means the server is disabled`() = runBlocking {
        assertFalse(client(baseUrl = "").isEnabled())
        assertFalse(client(baseUrl = "  ").isEnabled())
        assertTrue(client().isEnabled())
    }

    @Test
    fun `release client refuses a cleartext url without calling it`() = runBlocking {
        val release = CourseApiClient(
            client = CourseApiClient.defaultHttpClient(),
            baseUrl = { server.url("/").toString() },
            logger = Logger.Noop,
        )
        assertTrue((release.fetchCourses(origin, 2, 3) as AppResult.Err).error is AppError.InvalidInput)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `focus is sent sorted and comma separated, omitted when empty`() = runBlocking {
        server.enqueue(MockResponse().setBody(sample))
        server.enqueue(MockResponse().setBody(sample))
        client().fetchCourses(origin, 2, 3, focus = setOf(Skill.U_TURN, Skill.LEFT_TURN_SIGNALIZED))
        assertEquals("LEFT_TURN_SIGNALIZED,U_TURN", server.takeRequest().requestUrl?.queryParameter("focus"))
        client().fetchCourses(origin, 2, 3)
        assertEquals(null, server.takeRequest().requestUrl?.queryParameter("focus"))
    }

    @Test
    fun `route skills sends both points rounded and parses counts`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"schemaVersion":1,"distanceM":5000,"skillCounts":{"U_TURN":2}}"""))
        val counts = (client().routeSkills(LatLng(35.231049, 129.084372), LatLng(35.25051, 129.10049)) as AppResult.Ok).value
        assertEquals(mapOf(Skill.U_TURN to 2), counts)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/v1/route-skills", url.encodedPath)
        assertEquals(listOf("35.231", "129.084", "35.251", "129.100"), listOf("fromLat", "fromLon", "toLat", "toLon").map { url.queryParameter(it) })
    }

    @Test
    fun `route skills shares retry and error mapping with courses`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"UPSTREAM_UNAVAILABLE","message":"m"}}"""))
        server.enqueue(error(404, "NO_COURSE"))
        val result = client().routeSkills(origin, LatLng(35.25, 129.10))
        assertTrue((result as AppResult.Err).error is AppError.NoCourse)
        assertEquals(2, server.requestCount)
        // 한국 밖 도착지는 보내지도 않는다.
        assertTrue((client().routeSkills(origin, LatLng(0.0, 0.0)) as AppResult.Err).error is AppError.InvalidInput)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `connect timeout is short, whole attempt is 8 seconds`() {
        val http = CourseApiClient.defaultHttpClient()
        assertEquals(2_000, http.connectTimeoutMillis)
        assertEquals(8_000, http.callTimeoutMillis)
    }

    @Test
    fun `success parses courses and sends validated query`() = runBlocking {
        server.enqueue(MockResponse().setBody(sample))
        val result = client().fetchCourses(origin, 2, 3)
        val fetch = (result as AppResult.Ok).value
        assertEquals(2, fetch.parsed.courses.size)
        assertEquals(sample, fetch.body)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1/courses", req.requestUrl?.encodedPath)
        assertEquals("35.231", req.requestUrl?.queryParameter("lat"))
        assertEquals("2", req.requestUrl?.queryParameter("level"))
        assertEquals("3", req.requestUrl?.queryParameter("count"))
    }

    @Test
    fun `origin is rounded to 3 decimals before leaving the device`() = runBlocking {
        server.enqueue(MockResponse().setBody(sample))
        client().fetchCourses(LatLng(35.231049, 129.084372), 1)
        val url = server.takeRequest().requestUrl!!
        assertEquals("35.231", url.queryParameter("lat"))
        assertEquals("129.084", url.queryParameter("lon"))
        assertEquals("35.232", CourseApiClient.round3(35.2315))
    }

    @Test
    fun `network io and body reading run on the injected dispatcher, not the caller`() {
        val ioExecutor = Executors.newSingleThreadExecutor { Thread(it, "course-io") }
        val threads = Collections.synchronizedList(mutableListOf<String>())
        val recording = CourseApiClient.defaultHttpClient().newBuilder()
            .addInterceptor { chain ->
                threads += Thread.currentThread().name
                chain.proceed(chain.request()).also { threads += Thread.currentThread().name }
            }
            .build()
        server.enqueue(MockResponse().setBody(sample))
        try {
            val callerThread = Thread.currentThread().name
            val result = runBlocking { client(recording, io = ioExecutor.asCoroutineDispatcher()).fetchCourses(origin, 1) }
            assertTrue(result is AppResult.Ok)
            assertTrue(threads.isNotEmpty())
            assertTrue("threads=$threads", threads.all { it.startsWith("course-io") })
            assertTrue(threads.none { it == callerThread })
        } finally {
            ioExecutor.shutdownNow()
        }
    }

    @Test
    fun `5xx is retried twice then succeeds`() = runBlocking {
        server.enqueue(error(503, "UPSTREAM_UNAVAILABLE"))
        server.enqueue(error(500, "INTERNAL"))
        server.enqueue(MockResponse().setBody(sample))
        assertTrue(client().fetchCourses(origin, 1) is AppResult.Ok)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `persistent 5xx gives Server error after 3 attempts`() = runBlocking {
        repeat(3) { server.enqueue(error(503, "UPSTREAM_UNAVAILABLE")) }
        val err = (client().fetchCourses(origin, 1) as AppResult.Err).error
        assertEquals(AppError.Server(503, "UPSTREAM_UNAVAILABLE", "m"), err)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `4xx is not retried and mapped by code`() = runBlocking {
        server.enqueue(error(400, "INVALID_ARGUMENT"))
        assertTrue((client().fetchCourses(origin, 1) as AppResult.Err).error is AppError.InvalidInput)
        server.enqueue(error(404, "NO_COURSE"))
        assertTrue((client().fetchCourses(origin, 1) as AppResult.Err).error is AppError.NoCourse)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `non json error body still maps to Server`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))
        val err = (client(retries = 0).fetchCourses(origin, 1) as AppResult.Err).error
        assertEquals(502, (err as AppError.Server).httpStatus)
        assertEquals("HTTP_502", err.code)
    }

    @Test
    fun `malformed success body is not retried`() = runBlocking {
        server.enqueue(MockResponse().setBody("{oops"))
        val err = (client().fetchCourses(origin, 1) as AppResult.Err).error
        assertEquals("BAD_RESPONSE", (err as AppError.Server).code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `slow server times out and is retried`() = runBlocking {
        val fast = OkHttpClient.Builder().callTimeout(300, TimeUnit.MILLISECONDS).build()
        repeat(3) { server.enqueue(MockResponse().setBody(sample).setHeadersDelay(2, TimeUnit.SECONDS)) }
        val err = (client(fast).fetchCourses(origin, 1) as AppResult.Err).error
        assertTrue(err.toString(), err is AppError.Timeout)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `connection failure maps to Network and is retried`() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        // 요청을 읽기 전에 끊기면 MockWebServer가 세지 않으므로 호출 수는 인터셉터로 센다.
        var attempts = 0
        val counting = CourseApiClient.defaultHttpClient().newBuilder()
            .addInterceptor { chain -> attempts++; chain.proceed(chain.request()) }
            .build()
        val err = (client(counting).fetchCourses(origin, 1) as AppResult.Err).error
        assertTrue(err.toString(), err is AppError.Network)
        assertEquals(3, attempts)
    }

    @Test
    fun `input outside Korea, NaN or bad level is rejected without a request`() = runBlocking {
        assertTrue((client().fetchCourses(LatLng(37.42, -122.08), 1) as AppResult.Err).error is AppError.InvalidInput)
        assertTrue((client().fetchCourses(LatLng(Double.NaN, 129.0), 1) as AppResult.Err).error is AppError.InvalidInput)
        assertTrue((client().fetchCourses(origin, 5) as AppResult.Err).error is AppError.InvalidInput)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `disallowed server url is rejected without a request`() = runBlocking {
        val err = (client(baseUrl = "http://example.com").fetchCourses(origin, 1) as AppResult.Err).error
        assertTrue(err is AppError.InvalidInput)
        assertEquals(0, server.requestCount)
    }
}

class ServerUrlPolicyTest {

    @Test
    fun `debug allows https anywhere and http only to emulator host or localhost`() {
        assertEquals("http://10.0.2.2:8000", ServerUrlPolicy.validate(" http://10.0.2.2:8000/ ", allowCleartext = true))
        assertEquals("http://localhost:8000", ServerUrlPolicy.validate("http://localhost:8000", allowCleartext = true))
        assertEquals("https://courses.example.kr", ServerUrlPolicy.validate("https://courses.example.kr", allowCleartext = true))
    }

    @Test
    fun `release rejects all cleartext, including the dev hosts`() {
        assertEquals(null, ServerUrlPolicy.validate("http://10.0.2.2:8000"))
        assertEquals(null, ServerUrlPolicy.validate("http://localhost:8000", allowCleartext = false))
        assertEquals("https://courses.example.kr", ServerUrlPolicy.validate("https://courses.example.kr"))
    }

    @Test
    fun `rejects other schemes, cleartext hosts, credentials, queries and junk`() {
        listOf(
            "http://example.com",
            "http://192.168.0.10:8000",
            "ftp://10.0.2.2",
            "javascript:alert(1)",
            "https://user:pw@courses.example.kr",
            "https://courses.example.kr?x=1",
            "",
            "not a url",
            "https://" + "a".repeat(300) + ".kr",
        ).forEach { assertEquals(it, null, ServerUrlPolicy.validate(it, allowCleartext = true)) }
    }
}
