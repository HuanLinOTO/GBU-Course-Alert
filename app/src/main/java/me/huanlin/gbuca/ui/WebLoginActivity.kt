package me.huanlin.gbuca.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import me.huanlin.gbuca.GbuCaApp

/**
 * WebView SSO 兜底登录：加载认证中心 oauth.jsp，用户完成登录（可含验证码）后，
 * CookieManager 中出现教务会话 Cookie → 注入持久 CookieJar → 返回。
 * 地址取自用户配置（app.client.endpoints），不内置任何学校域名。
 */
class WebLoginActivity : ComponentActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, WebLoginActivity::class.java))
        }
    }

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = GbuCaApp.instance
        webView = WebView(this)
        setContentView(webView)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // 硬化：SSO 登录页无需本地文件/内容提供器访问
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false

        // 系统返回键优先回退网页历史，而非直接退出
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                val host = GbuCaApp.instance.client.endpoints.jwxtHost
                if (Uri.parse(url).host != host) return
                val cm = CookieManager.getInstance()
                val cookies = cm.getCookie("https://$host") ?: return
                if (Regex("(SESSION|JSESSIONID)=", RegexOption.IGNORE_CASE).containsMatchIn(cookies)) {
                    // 系统 Cookie → okhttp CookieJar
                    app.cookieJar.inject("https://$host/", cookies.split(";"))
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(app.client.webLoginUrl())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
