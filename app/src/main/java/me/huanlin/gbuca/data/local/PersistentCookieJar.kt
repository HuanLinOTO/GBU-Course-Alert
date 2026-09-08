package me.huanlin.gbuca.data.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 持久化 CookieJar：内存 + 磁盘（JSON 文件）。
 * 支持 WebView 登录后外部注入 Cookie（[inject]）。
 * 会话归属按用户配置的教务/认证域名判定（[jwxtHost] / [iaaaHost] 提供者）。
 */
class PersistentCookieJar(
    private val storeFile: File,
    private val jwxtHost: () -> String,
    private val iaaaHost: () -> String,
) : CookieJar {

    /** domain 是否属于当前配置的教务主机（Cookie domain 可能带前导点）。 */
    private fun matchesJwxt(domain: String): Boolean {
        val host = jwxtHost()
        if (host.isEmpty()) return false
        return domain.removePrefix(".").removeSuffix(".") == host
    }

    /** 存储键（"domain|path|name"）是否属于当前配置的认证主机。 */
    private fun matchesIaaaKey(key: String): Boolean {
        val host = iaaaHost()
        if (host.isEmpty()) return false
        return key.substringBefore('|').removePrefix(".").removeSuffix(".") == host
    }

    @Serializable
    private data class CookieDto(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val expiresAt: Long,
        val secure: Boolean,
        val httpOnly: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val storage = ConcurrentHashMap<String, MutableList<Cookie>>()

    @Volatile
    var webLoginListener: (() -> Unit)? = null

    init {
        runCatching {
            if (storeFile.exists()) {
                val list = json.decodeFromString<List<CookieDto>>(storeFile.readText())
                val now = System.currentTimeMillis()
                synchronized(storage) {
                    list.filter { it.expiresAt > now }.forEach { dto ->
                        val key = keyOf(dto.domain, dto.path, dto.name)
                        storage.getOrPut(key) { mutableListOf() }.add(dto.toCookie())
                    }
                }
            }
        }
    }

    private fun keyOf(cookie: Cookie) = keyOf(cookie.domain, cookie.path, cookie.name)

    private fun keyOf(domain: String, path: String, name: String) = "$domain|$path|$name"

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val out = mutableListOf<Cookie>()
        synchronized(storage) {
            for ((_, list) in storage) {
                val it = list.iterator()
                while (it.hasNext()) {
                    val c = it.next()
                    if (c.expiresAt < now) {
                        it.remove(); continue
                    }
                    if (c.matches(url)) out += c
                }
            }
        }
        return out
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(storage) {
            for (c in cookies) {
                val key = keyOf(c)
                val list = storage.getOrPut(key) { mutableListOf() }
                list.removeAll { it.value == c.value && it.name == c.name && it.domain == c.domain }
                list += c
            }
        }
        persist()
        if (cookies.any { matchesJwxt(it.domain) && (it.name.equals("SESSION", true) || it.name.equals("JSESSIONID", true)) }) {
            webLoginListener?.invoke()
        }
    }

    /** WebView 兜底登录后注入会话 Cookie（Set-Cookie 原始串）。 */
    fun inject(url: String, setCookieStrings: List<String>) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val cookies = setCookieStrings.mapNotNull { Cookie.parse(httpUrl, it) }
        if (cookies.isNotEmpty()) saveFromResponse(httpUrl, cookies)
    }

    fun hasJwxtSession(): Boolean {
        val now = System.currentTimeMillis()
        synchronized(storage) {
            return storage.values.flatten().any {
                matchesJwxt(it.domain) &&
                    (it.name.equals("SESSION", true) || it.name.equals("JSESSIONID", true)) &&
                    it.expiresAt > now
            }
        }
    }

    /** 登录前清除认证（iAAA）会话 Cookie：旧会话状态会导致服务端 "操作失败:null"。 */
    fun clearIaaa() {
        synchronized(storage) {
            storage.keys.filter { matchesIaaaKey(it) }.forEach { storage.remove(it) }
        }
        persist()
    }

    fun clear() {
        synchronized(storage) { storage.clear() }
        runCatching { storeFile.delete() }
    }

    private fun persist() {
        runCatching {
            val all = synchronized(storage) { storage.values.flatten().map { it.toDto() } }
            storeFile.parentFile?.mkdirs()
            storeFile.writeText(json.encodeToString(all))
        }
    }

    private fun Cookie.toDto() = CookieDto(name, value, domain, path, expiresAt, secure, httpOnly)
    private fun CookieDto.toCookie(): Cookie {
        val b = Cookie.Builder()
            .name(name).value(value).domain(domain).path(path).expiresAt(expiresAt)
        if (secure) b.secure()
        if (httpOnly) b.httpOnly()
        return b.build()
    }
}

/** 记录最近一次响应，用于调试与登录态诊断。 */
class LastResponseInterceptor : Interceptor {
    @Volatile
    var lastUrl: String? = null
        private set

    override fun intercept(chain: Interceptor.Chain): Response {
        val resp = chain.proceed(chain.request())
        lastUrl = resp.request.url.toString()
        return resp
    }
}
