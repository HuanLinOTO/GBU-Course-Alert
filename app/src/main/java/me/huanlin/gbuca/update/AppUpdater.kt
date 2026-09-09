package me.huanlin.gbuca.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * GitHub Release 自更新的 Android 适配层：查询最新 release、流式下载 APK、
 * 校验 GitHub 官方 digest、经 FileProvider 转交系统安装器。
 * 手动触发（设置页），无后台轮询，不访问 GitHub 之外的任何服务器。
 */
class AppUpdater(private val context: Context) {

    companion object {
        const val REPO = "HuanLinOTO/GBU-Course-Alert"
        private const val UA = "GBU-Course-Alert (https://github.com/$REPO)"
    }

    sealed interface InstallResult {
        /** 已拉起系统安装器，安装确认交给系统界面。 */
        data object Launched : InstallResult

        /** 缺「安装未知应用」授权，需先跳系统设置，授权后可点「安装」重试。 */
        data object NeedPermission : InstallResult

        /** 设备上没有能处理 APK 安装的组件。 */
        data object NoInstaller : InstallResult
    }

    /** 下载内容与 GitHub 官方 SHA-256 不符（网络劫持/文件损坏）。 */
    class ChecksumMismatchException : IOException("SHA-256 校验不符")

    /** 安装包缓存目录（与 file_paths.xml 的 cache-path "update/" 对应）。 */
    val updateDir: File get() = File(context.cacheDir, "update")

    /** 专用客户端：不带教务 CookieJar，默认跟随重定向（release 附件 302 到 CDN）。 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 查询最新 release（不含 prerelease/draft）。
     * 仓库没有任何 release 时返回 null（HTTP 404）；其余非 2xx 抛 [IOException]。
     */
    suspend fun checkLatest(): GitHubRelease? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", UA)
            .build()
        client.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 -> null
                !resp.isSuccessful -> throw IOException("GitHub API HTTP ${resp.code}")
                else -> AppUpdateChecks.parseRelease(resp.body.string())
            }
        }
    }

    /**
     * 流式下载安装包到 [updateDir]，进度经 [onProgress]（0..100）回报；
     * 响应无 content-length 时先回调 -1，UI 显示不确定进度。
     * 资产带官方 digest 时校验 SHA-256，不符删除并抛 [ChecksumMismatchException]。
     */
    suspend fun downloadApk(asset: GitHubAsset, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = updateDir.apply { mkdirs() }
            // 只保留一份安装包：开下前先清旧文件
            dir.listFiles()?.forEach { it.delete() }
            val tmp = File(dir, "${asset.name}.part")
            val req = Request.Builder().url(asset.downloadUrl)
                .header("User-Agent", UA)
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("下载失败：HTTP ${resp.code}")
                    val body = resp.body   // OkHttp 5：body 恒非空
                    val total = body.contentLength()
                    if (total <= 0) onProgress(-1)
                    var downloaded = 0L
                    var lastPct = -1
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()   // 取消下载即刻中止
                                val n = input.read(buf)
                                if (n == -1) break
                                out.write(buf, 0, n)
                                downloaded += n
                                if (total > 0) {
                                    val pct = ((downloaded * 100) / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        onProgress(pct)
                                    }
                                }
                            }
                        }
                    }
                }
                verifyDigest(tmp, asset.digest)
                val final = File(dir, asset.name)
                if (!tmp.renameTo(final)) tmp.copyTo(final, overwrite = true).also { tmp.delete() }
                final
            } catch (t: Throwable) {
                tmp.delete()   // 失败/取消都不留半截文件
                throw t
            }
        }

    /** 有官方 digest 时强制校验；不符删除文件并抛 [ChecksumMismatchException]。 */
    private fun verifyDigest(file: File, digestHeader: String?) {
        val expected = digestHeader
            ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.lowercase()
            ?: return
        if (sha256Hex(file) != expected) {
            file.delete()
            throw ChecksumMismatchException()
        }
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 转交系统安装器。缺「安装未知应用」授权返回 [InstallResult.NeedPermission]，
     * 设置页引导授权后可点「安装」重试（文件仍在）。
     */
    fun install(apk: File): InstallResult {
        if (!context.packageManager.canRequestPackageInstalls()) return InstallResult.NeedPermission
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            InstallResult.Launched
        } catch (e: ActivityNotFoundException) {
            InstallResult.NoInstaller
        }
    }

    /** 「安装未知应用」授权页（仅本应用入口）。 */
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 清理已下载的安装包（离开更新流程时调用）。 */
    fun clearDownloads() {
        updateDir.listFiles()?.forEach { it.delete() }
    }
}
