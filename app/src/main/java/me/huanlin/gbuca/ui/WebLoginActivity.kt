package me.huanlin.gbuca.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import me.huanlin.gbuca.GbuCaApp
import me.huanlin.gbuca.data.remote.GbuClient
import me.huanlin.gbuca.sync.SyncWorker
import me.huanlin.gbuca.widget.TodayWidgetReceiver
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * WebView SSO 兜底登录：加载 iAAA oauth.jsp，用户完成登录（可含验证码）后，
 * CookieManager 中出现 jwxt JSESSIONID → 注入持久 CookieJar → 返回。
 */
class WebLoginActivity : ComponentActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, WebLoginActivity::class.java))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = GbuCaApp.instance
        val wv = WebView(this)
        setContentView(wv)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!url.contains("jwxt.gbu.edu.cn")) return
                val cm = CookieManager.getInstance()
                val cookies = cm.getCookie("https://jwxt.gbu.edu.cn") ?: return
                if (Regex("(SESSION|JSESSIONID)=", RegexOption.IGNORE_CASE).containsMatchIn(cookies)) {
                    // 系统 Cookie → okhttp CookieJar
                    app.cookieJar.inject("https://jwxt.gbu.edu.cn/", cookies.split(";"))
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
        wv.loadUrl(GbuClient.webLoginUrl())
    }
}
