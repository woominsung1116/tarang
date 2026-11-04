package com.taraang.app.data.course

import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.Geo
import com.taraang.app.core.LatLng
import com.taraang.app.core.Logger
import com.taraang.app.domain.model.CourseSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import com.taraang.app.core.map
import com.taraang.app.domain.model.Skill
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeUnit

/** 서버 응답 원문과 해석 결과. 원문은 마지막 성공 캐시에 그대로 쓴다. */
data class CourseFetch(val body: String, val parsed: ParsedCourses)

/**
 * 코스 서버 클라이언트(ARCHITECTURE.md 4.2): `/v1/courses`(선택 `focus`)와 `/v1/route-skills`.
 * 연결 2초·시도당 8초 제한. GET만 재시도하며, 네트워크 오류(IOException)와 5xx에서만 최대 2회 지수 백오프로 다시 시도한다.
 * 4xx는 다시 보내도 결과가 같으므로 바로 돌려준다.
 *
 * 메인 안전: 요청 실행·본문 읽기·JSON 해석을 모두 [ioDispatcher]에서 한다. 어느 스레드에서 불러도 된다.
 * 개인정보: 출발지·도착지는 소수점 3자리(약 100m)로 반올림해 보낸다.
 * 본문은 [MAX_BODY_BYTES]까지만 읽는다. 넘으면 "TOO_LARGE"로 거절해 메모리를 지킨다(재시도 안 함).
 */
class CourseApiClient(
    private val client: OkHttpClient,
    private val baseUrl: suspend () -> String,
    private val logger: Logger,
    private val maxRetries: Int = 2,
    private val backoffBaseMs: Long = 500,
    private val allowCleartext: Boolean = false,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CourseRemote {
    override suspend fun fetch(origin: LatLng, level: Int, focus: Set<Skill>): AppResult<CourseFetch> =
        fetchCourses(origin, level, focus = focus)

    override suspend fun routeSkills(origin: LatLng, destination: LatLng): AppResult<Map<Skill, Int>> {
        if (!isValidPoint(origin) || !isValidPoint(destination)) return AppResult.Err(AppError.InvalidInput("출발지·도착지가 한국 범위 밖"))
        val url = endpoint("/v1/route-skills") {
            addQueryParameter("fromLat", round3(origin.lat))
            addQueryParameter("fromLon", round3(origin.lon))
            addQueryParameter("toLat", round3(destination.lat))
            addQueryParameter("toLon", round3(destination.lon))
        } ?: return AppResult.Err(AppError.InvalidInput("허용되지 않는 서버 주소"))
        return getWithRetry(url) { body -> CourseJson.parseRouteSkills(body) }
    }

    /** 서버 주소가 비어 있으면(release 기본값) 서버를 쓰지 않는다. */
    override suspend fun isEnabled(): Boolean = baseUrl().isNotBlank()

    /** [focus]는 목표 경로에서 뽑은 기술. 비어 있으면 보내지 않는다(하위 호환 추가 파라미터). */
    suspend fun fetchCourses(origin: LatLng, level: Int, count: Int = DEFAULT_COUNT, focus: Set<Skill> = emptySet()): AppResult<CourseFetch> {
        if (!isValidPoint(origin)) return AppResult.Err(AppError.InvalidInput("출발지가 한국 범위 밖"))
        if (level !in 1..4 || count !in 1..5) return AppResult.Err(AppError.InvalidInput("level=$level count=$count"))
        val url = endpoint("/v1/courses") {
            addQueryParameter("lat", round3(origin.lat))
            addQueryParameter("lon", round3(origin.lon))
            addQueryParameter("level", level.toString())
            addQueryParameter("count", count.toString())
            // 순서를 고정해 같은 요청이 같은 URL이 되게 한다.
            if (focus.isNotEmpty()) addQueryParameter("focus", focus.map { it.name }.sorted().joinToString(","))
        } ?: return AppResult.Err(AppError.InvalidInput("허용되지 않는 서버 주소"))
        return getWithRetry(url) { body -> CourseJson.parse(body, CourseSource.SERVER).map { CourseFetch(body, it) } }
    }

    private fun isValidPoint(p: LatLng) = !p.lat.isNaN() && !p.lon.isNaN() && Geo.isInKorea(p)

    /** 검증된 서버 주소 + [path] + 쿼리. 주소가 정책에 맞지 않으면 null. */
    private suspend fun endpoint(path: String, query: HttpUrl.Builder.() -> Unit): HttpUrl? {
        val base = ServerUrlPolicy.validate(baseUrl(), allowCleartext) ?: return null
        return "$base$path".toHttpUrlOrNull()?.newBuilder()?.apply(query)?.build()
    }

    private suspend fun <T> getWithRetry(url: HttpUrl, parse: (String) -> AppResult<T>): AppResult<T> {
        val request = Request.Builder().url(url).get().header("Accept", "application/json").build()
        var attempt = 0
        while (true) {
            val result = withContext(ioDispatcher) { executeOnce(request, parse) }
            val retryable = result is Attempt.Failed && result.retryable
            if (!retryable || attempt >= maxRetries) return result.toAppResult()
            val waitMs = backoffBaseMs shl attempt
            logger.w(TAG, "코스 서버 재시도 ${attempt + 1}/$maxRetries (${(result as Attempt.Failed).error.debugMessage}), ${waitMs}ms 후")
            delay(waitMs)
            attempt++
        }
    }

    private sealed interface Attempt<out T> {
        data class Success<T>(val value: T) : Attempt<T>
        data class Failed(val error: AppError, val retryable: Boolean) : Attempt<Nothing>

        fun toAppResult(): AppResult<T> = when (this) {
            is Success -> AppResult.Ok(value)
            is Failed -> AppResult.Err(error)
        }
    }

    /** [ioDispatcher] 안에서만 부른다. 취소되면 스레드 인터럽트로 블로킹 호출을 끊는다. */
    private suspend fun <T> executeOnce(request: Request, parse: (String) -> AppResult<T>): Attempt<T> {
        val call = client.newCall(request)
        val response = try {
            runInterruptible { call.execute() }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } catch (e: InterruptedIOException) {
            // OkHttp의 callTimeout/readTimeout은 InterruptedIOException(SocketTimeoutException 포함)으로 온다.
            return Attempt.Failed(AppError.Timeout("코스 요청 시간 초과: ${e.javaClass.simpleName}"), retryable = true)
        } catch (e: IOException) {
            return Attempt.Failed(AppError.Network("코스 요청 실패: ${e.javaClass.simpleName}"), retryable = true)
        }
        return response.use { res ->
            val body = try {
                val source = res.body?.source()
                when {
                    source == null -> ""
                    // MAX+1바이트를 버퍼에 채울 수 있으면 한도를 넘은 것이다. 못 채우면 본문 전체가 버퍼에 있다.
                    source.request(MAX_BODY_BYTES + 1) ->
                        return Attempt.Failed(AppError.Server(res.code, "TOO_LARGE", "응답 본문이 ${MAX_BODY_BYTES}바이트 초과"), retryable = false)
                    else -> source.buffer.readUtf8()
                }
            } catch (e: InterruptedIOException) {
                return Attempt.Failed(AppError.Timeout("응답 본문 시간 초과: ${e.javaClass.simpleName}"), retryable = true)
            } catch (e: IOException) {
                return Attempt.Failed(AppError.Network("응답 본문 읽기 실패: ${e.javaClass.simpleName}"), retryable = true)
            }
            if (res.isSuccessful) {
                when (val parsed = parse(body)) {
                    is AppResult.Ok -> Attempt.Success(parsed.value)
                    is AppResult.Err -> Attempt.Failed(parsed.error, retryable = false)
                }
            } else {
                Attempt.Failed(httpError(res.code, body), retryable = res.code >= 500)
            }
        }
    }

    private fun httpError(status: Int, body: String): AppError {
        val err = CourseJson.parseError(body)
        val code = err?.code ?: "HTTP_$status"
        return when (code) {
            "NO_COURSE" -> AppError.NoCourse(err?.message.orEmpty())
            "INVALID_ARGUMENT" -> AppError.InvalidInput(err?.message.orEmpty())
            else -> AppError.Server(status, code, err?.message ?: "HTTP $status")
        }
    }

    companion object {
        const val DEFAULT_COUNT = 3
        const val TIMEOUT_S = 8L
        const val CONNECT_TIMEOUT_S = 2L
        /** 코스 5개 응답은 수십 KB. 그보다 훨씬 큰 한도로 정상 응답은 막지 않는다. */
        const val MAX_BODY_BYTES = 2L * 1024 * 1024
        private const val TAG = "CourseApi"

        /** 서버로 보내는 좌표는 소수점 3자리(약 100m)로 줄인다(ARCHITECTURE.md 7장 19번). */
        internal fun round3(value: Double): String = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).toPlainString()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            // 시도당 전체 8초. 연결은 2초면 충분하다(서버가 꺼져 있으면 빨리 대체로 넘어간다).
            .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            // 재시도는 위에서 직접 통제한다.
            .retryOnConnectionFailure(false)
            .build()
    }
}
