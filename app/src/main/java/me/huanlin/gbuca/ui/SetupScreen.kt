package me.huanlin.gbuca.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import me.huanlin.gbuca.R
import me.huanlin.gbuca.data.remote.HostNormalizer

/** OOBE 首步：填写教务系统与统一认证（iAAA）地址。仅裸主机名，强制 https。 */
@Composable
fun SetupScreen(
    vm: AppViewModel,
    onDone: () -> Unit,
) {
    var jwxt by rememberSaveable { mutableStateOf(vm.jwxtHost) }
    var iaaa by rememberSaveable { mutableStateOf(vm.iaaaHost) }
    val focus = LocalFocusManager.current
    val jHost = remember(jwxt) { HostNormalizer.normalize(jwxt) }
    val iHost = remember(iaaa) { HostNormalizer.normalize(iaaa) }

    fun submit() {
        val j = jHost ?: return
        val i = iHost ?: return
        focus.clearFocus()
        vm.completeSetup(j, i) { onDone() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(88.dp))
        Text(
            stringResource(R.string.setup_title),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.setup_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(48.dp))
        OutlinedTextField(
            value = jwxt,
            onValueChange = { jwxt = it },
            label = { Text(stringResource(R.string.setup_jwxt_label)) },
            placeholder = { Text(stringResource(R.string.setup_host_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            isError = jwxt.isNotBlank() && jHost == null,
            supportingText = {
                if (jwxt.isNotBlank() && jHost == null) {
                    Text(stringResource(R.string.setup_host_invalid))
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = iaaa,
            onValueChange = { iaaa = it },
            label = { Text(stringResource(R.string.setup_iaaa_label)) },
            placeholder = { Text(stringResource(R.string.setup_host_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            isError = iaaa.isNotBlank() && iHost == null,
            supportingText = {
                if (iaaa.isNotBlank() && iHost == null) {
                    Text(stringResource(R.string.setup_host_invalid))
                }
            },
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { submit() },
            enabled = jHost != null && iHost != null,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(stringResource(R.string.setup_next))
        }
    }
}
