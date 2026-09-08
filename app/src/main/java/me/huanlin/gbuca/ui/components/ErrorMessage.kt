package me.huanlin.gbuca.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.huanlin.gbuca.R

/**
 * 统一的提示文本：默认错误色，[ok] == true 时用主色。
 *
 * [detail] 非空时提供「复制错误详情」按钮 —— 把可定位的环节/地址/HTTP 状态/响应片段
 * 一次性交给用户，便于反馈给开发者。使用框架 ClipboardManager（不依赖已弃用的
 * Compose 剪贴板 API）；Android 13+ 复制后由系统自行提示。
 */
@Composable
fun ErrorMessage(
    message: String?,
    modifier: Modifier = Modifier,
    detail: String? = null,
    ok: Boolean? = null,
) {
    if (message == null) return
    val context = LocalContext.current
    Column(modifier) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        if (detail != null) {
            TextButton(onClick = { context.copyToClipboard(detail) }) {
                Text(stringResource(R.string.common_copy_error_detail))
            }
        }
    }
}

private fun Context.copyToClipboard(text: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("gbuca-error-detail", text))
}
