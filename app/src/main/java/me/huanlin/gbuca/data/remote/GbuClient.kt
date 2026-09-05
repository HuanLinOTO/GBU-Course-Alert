package me.huanlin.gbuca.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.huanlin.gbuca.data.GbuException
import me.huanlin.gbuca.data.local.PersistentCookieJar
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * GBU 教务客户端：iAAA 统一认证登录 + 教务接口。
 *
 * 登录链路：
 *  ① POST https://iaaa.gbu.edu.cn/iaaa/oauthlogin.do （明文表单）→ token
 *  ② GET  https://jwxt.gbu.edu.cn/oauth/login/code?_rand&token → 302 → 建立教务会话 Cookie
 */
class GbuClient(private val cookieJar: PersistentCookieJar) {

    companion object {
        const val IAAA_BASE = "https://iaaa.gbu.edu.cn/iaaa/"
        const val JWXT_BASE = "https://jwxt.gbu.edu.cn"
        const val APP_ID = "gbu_jwxt"
        const val REDIR_URL = "https://jwxt.gbu.edu.cn/oauth/login/code"

        /** WebView 兜底登录入口。 */
        fun webLoginUrl(): String =
            "https://iaaa.gbu.edu.cn/iaaa/oauth.jsp?appID=$APP_ID&redirectURL=" +
                java.net.URLEncoder.encode(REDIR_URL, "UTF-8") + "&appName=%E6%95%99%E5%8A%A1%E7%B3%BB%E7%BB%9F"

        private val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** API 客户端：不自动跟随重定向，以便识别会话过期（302 → 登录页）。 */
    private val apiClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", UA)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header("Referer", "$JWXT_BASE/xsxk/zyxk")
                    .build()
            )
        }
        .build()

    /** 登录客户端：跟随重定向以完成 oauth/code → 302 → main 的 Cookie 建立。 */
    private val loginClient: OkHttpClient = apiClient.newBuilder()
        .followRedirects(true)
        .build()

    val hasSession: Boolean get() = cookieJar.hasJwxtSession()

    suspend fun login(username: String, password: String): Unit = withContext(Dispatchers.IO) {
        // 旧 iAAA 会话状态会导致服务端 NPE（"操作失败:null"），登录前先清
        cookieJar.clearIaaa()
        val u = username.trim().trim('\u3000')
        val p = password.trim().trim('\u3000')
        val form = FormBody.Builder()
            .add("appid", APP_ID)
            .add("userName", u)
            .add("password", p)
            .add("randCode", "")
            .add("smsCode", "")
            .add("otpCode", "")
            .add("redirUrl", REDIR_URL)
            .build()
        val req = Request.Builder().url("${IAAA_BASE}oauthlogin.do").post(form).build()

        val body = apiClient.newCall(req).execute().use { resp ->
            resp.body?.string() ?: throw GbuException.Network(java.io.IOException("空响应"))
        }
        // 隐私约定：不记录响应内容（含 token / 密码相关字段）到日志
        val obj: JsonObject = runCatching { json.parseToJsonElement(body) as? JsonObject }
            .getOrElse { throw GbuException.ApiError("iAAA 响应异常: ${body.take(120)}") }
            ?: throw GbuException.ApiError("iAAA 响应异常: ${body.take(120)}")

        val success = (obj["success"] as? JsonPrimitive)?.content == "true"
        if (!success) {
            val errors = obj["errors"] as? JsonObject
            val code = (errors?.get("code") as? JsonPrimitive)?.content ?: ""
            val msg = (errors?.get("msg") as? JsonPrimitive)?.content
                ?: (obj["msg"] as? JsonPrimitive)?.content
                ?: "登录失败（未知错误）"
            val showCode = (obj["showCode"] as? JsonPrimitive)?.content == "true"
            when {
                code == "E21" -> throw GbuException.BadCredentials("尝试次数过多，账号已临时锁定，请半小时后再试")
                code == "E02" -> throw GbuException.BadCredentials("账号未激活，请先在 iAAA 网页激活")
                showCode -> throw GbuException.NeedCaptcha()
                else -> throw GbuException.BadCredentials(msg)
            }
        }
        val token = (obj["token"] as? JsonPrimitive)?.content
            ?: throw GbuException.ApiError("iAAA 未返回 token")

        val rand = Math.random()
        val codeReq = Request.Builder()
            .url("$JWXT_BASE/oauth/login/code?_rand=$rand&token=$token")
            .get()
            .build()
        loginClient.newCall(codeReq).execute().use { resp ->
            if (!cookieJar.hasJwxtSession() && !resp.request.url.encodedPath.contains("authentication")) {
                throw GbuException.ApiError("教务系统会话建立失败")
            }
        }
    }

    /** queryYxkc 单页。会话过期时抛 [GbuException.SessionExpired]。 */
    suspend fun queryYxkc(xn: String, xq: String, xnxq: String, pageNum: Int, pageSize: Int = 200): YxkcResponse =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("p_xn", xn)
                .add("p_xq", xq)
                .add("p_xnxq", xnxq)
                .add("p_dqxnxq", xnxq)
                .add("p_xkfsdm", "yixuan")
                .add("p_sfxsgwckb", "1")
                .add("pageNum", pageNum.toString())
                .add("pageSize", pageSize.toString())
                .build()
            val req = Request.Builder().url("$JWXT_BASE/Xsxk/queryYxkc").post(form).build()
            apiClient.newCall(req).execute().use { resp -> parseApiResponse(resp) { json.decodeFromString(it) } }
        }

    suspend fun queryXnxq(): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$JWXT_BASE/component/queryXnxq")
            .post(FormBody.Builder().build())
            .build()
        apiClient.newCall(req).execute().use { resp -> parseApiResponse(resp) { it } }
    }

    /** 日期 → 学期周次。返回 (xn, xq, zc)；zc：未开学为 0，第 1 周 = 1。 */
    suspend fun queryXnxqZc(rq: java.time.LocalDate): Triple<String, String, Int>? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$JWXT_BASE/component/getXnxqByRq?rq=$rq")
            .get()
            .build()
        apiClient.newCall(req).execute().use { resp ->
            val body = parseApiResponse(resp) { json.decodeFromString<XnxqByRqResponse>(it) }
            val r = body.content?.rqxnxq ?: return@use null
            val xn = r.xn ?: return@use null
            val xq = r.xq ?: return@use null
            val zc = r.zc?.toIntOrNull() ?: return@use null
            Triple(xn, xq, zc)
        }
    }

    private fun <T> parseApiResponse(resp: Response, parse: (String) -> T): T {
        if (resp.isRedirect) {
            android.util.Log.w("GbuClient", "302 redirect → 会话过期")
            throw GbuException.SessionExpired()
        }
        val text = resp.body?.string() ?: throw GbuException.Network(java.io.IOException("空响应"))
        if (!resp.isSuccessful) {
            android.util.Log.w("GbuClient", "HTTP ${resp.code}")
            throw GbuException.ApiError("HTTP ${resp.code}")
        }
        val trimmed = text.trimStart()
        if (trimmed.startsWith("<")) {
            android.util.Log.w("GbuClient", "返回 HTML（登录页）→ 会话过期")
            throw GbuException.SessionExpired()
        }
        return runCatching { parse(text) }.getOrElse {
            android.util.Log.w("GbuClient", "响应解析失败")
            throw GbuException.ApiError("响应解析失败")
        }
    }

    fun parseXnxqTerms(body: String): List<XnxqParser.Term> = XnxqParser.parse(body)

    /** 从日期推算当前学期（queryXnxq 失败时的兜底）。 */
    fun fallbackXnxq(now: java.time.LocalDate = java.time.LocalDate.now()): Triple<String, String, String> {
        val y = now.year
        return when (now.monthValue) {
            in 9..12 -> Triple("$y-${y + 1}", "1", "$y-${y + 1}1")
            in 1..2 -> Triple("${y - 1}-$y", "1", "${y - 1}-${y}1")
            else -> Triple("${y - 1}-$y", "2", "${y - 1}-${y}2")
        }
    }
}
