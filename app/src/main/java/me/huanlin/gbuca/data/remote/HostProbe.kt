package me.huanlin.gbuca.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** 探测失败的归类；文案在 UI 层映射为 strings.xml 资源。 */
enum class ProbeFailure { HostNotFound, Timeout, Tls, Unreachable }

/** 地址可达性探测结果。 */
sealed interface ProbeResult {
    data object Reachable : ProbeResult
    data class Unreachable(val failure: ProbeFailure) : ProbeResult
}

/**
 * OOBE 地址步的轻量连通性探测。
 *
 * 判定口径刻意宽松：只要拿到任何 HTTP 响应（含 302/401/403/405）就算可达，
 * 只有网络层异常才算不可达 —— 避免认证失败、HEAD 被拒、重定向造成假阴性。
 * 独立 OkHttpClient，不复用 GbuClient 的 apiClient（避免 Referer 拦截器与 CookieJar 干扰）。
 */
object HostProbe {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    /** 探测 `https://{host}/` 是否可达。永不抛异常。 */
    suspend fun probe(host: String): ProbeResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://$host/").head().build()
        try {
            client.newCall(request).execute().use { ProbeResult.Reachable }
        } catch (e: IOException) {
            ProbeResult.Unreachable(failureOf(e))
        }
    }

    /** 网络异常 → 失败归类（纯函数，可单测）。 */
    fun failureOf(e: IOException): ProbeFailure = when (e) {
        is UnknownHostException -> ProbeFailure.HostNotFound
        is SocketTimeoutException -> ProbeFailure.Timeout
        is SSLException -> ProbeFailure.Tls
        else -> ProbeFailure.Unreachable
    }
}
