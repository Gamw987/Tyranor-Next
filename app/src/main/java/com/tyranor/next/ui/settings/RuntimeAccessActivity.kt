package com.tyranor.next.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.system.RuntimeAccess
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppSearchField
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.BottomInsetSpacer
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.bounceOnChange
import com.tyranor.next.ui.common.motion.staggeredEntrance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold

/** 可用特权选择页：无特权 / Root / Shizuku / 自定义命令。 */
class RuntimeAccessActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreenContent { RuntimeAccessScreen() }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, RuntimeAccessActivity::class.java)
    }
}

@Composable
internal fun RuntimeAccessScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(RuntimeAccess.Status()) }
    var selected by remember { mutableStateOf(RuntimeAccess.getAuthorizer(context)) }
    var checkingRoot by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editingCustom by remember { mutableStateOf(false) }
    var customDraft by remember { mutableStateOf("") }
    val requestCode = remember { 0x5A12 }

    fun refresh(includeRoot: Boolean = false) {
        scope.launch {
            status = withContext(Dispatchers.IO) { RuntimeAccess.probe(context, includeRoot) }
        }
    }

    val shizukuUnavailable = stringResource(R.string.settings_privilege_shizuku_unavailable)
    val rootUnavailable = stringResource(R.string.settings_privilege_root_unavailable)
    val shizukuDenied = stringResource(R.string.settings_privilege_shizuku_denied)
    val customCleared = stringResource(R.string.settings_privilege_custom_cleared)
    val customSaved = stringResource(R.string.settings_privilege_custom_saved)

    DisposableEffect(Unit) {
        val binderListener = Shizuku.OnBinderReceivedListener { refresh() }
        val deadListener = Shizuku.OnBinderDeadListener {
            status = status.copy(shizukuAvailable = false, shizukuAuthorized = false, shizukuUid = null)
        }
        val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
            if (code == requestCode) {
                selected = RuntimeAccess.Authorizer.SHIZUKU
                RuntimeAccess.setAuthorizer(context, selected)
                if (result != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    message = shizukuDenied
                }
                refresh()
            }
        }
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        onDispose {
            Shizuku.removeBinderReceivedListener(binderListener)
            Shizuku.removeBinderDeadListener(deadListener)
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val shizukuSummary = when (status.shizukuMode) {
        RuntimeAccess.ShizukuMode.NONE -> if (!status.shizukuAvailable) {
            shizukuUnavailable
        } else {
            stringResource(R.string.settings_privilege_shizuku_request)
        }
        RuntimeAccess.ShizukuMode.ROOT -> stringResource(R.string.settings_privilege_shizuku_root)
        RuntimeAccess.ShizukuMode.ADB -> stringResource(R.string.settings_privilege_shizuku_shell)
    }
    val rootSummary = when {
        checkingRoot -> stringResource(R.string.settings_privilege_root_checking)
        status.rootReady == null -> stringResource(R.string.settings_privilege_root_unchecked)
        status.rootReady == true -> stringResource(
            R.string.settings_privilege_root_mode_available,
            rootModeLabel(status.rootMode),
        )
        else -> rootUnavailable
    }
    val customSummary = if (status.customCommand.isBlank()) {
        stringResource(R.string.settings_privilege_custom_unset)
    } else {
        stringResource(R.string.settings_privilege_custom_current, status.customCommand)
    }

    MiuixSettingsTheme {
        ProvideEntrance {
            MiuixScaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                contentWindowInsets = WindowInsets(0.dp),
                topBar = {
                    AppSecondaryTopBar(
                        title = stringResource(R.string.settings_privilege_title),
                        background = androidx.compose.ui.graphics.Color.Transparent,
                        contentColor = PageTextColor,
                    )
                },
            ) { innerPadding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(
                        top = innerPadding.calculateTopPadding(),
                    ),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        MiuixCard(
                            modifier = Modifier.staggeredEntrance(0).fillMaxWidth().glassBorder(),
                            cornerRadius = AppComponentCornerRadius,
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    stringResource(R.string.settings_privilege_what_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = PageTextColor,
                                )
                                Text(
                                    stringResource(R.string.settings_privilege_what_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PageSecondaryTextColor,
                                )
                            }
                        }
                    }

                    item {
                        MiuixCard(
                            modifier = Modifier.staggeredEntrance(1).fillMaxWidth().glassBorder(),
                            cornerRadius = AppComponentCornerRadius,
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    stringResource(R.string.settings_privilege_relation_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = PageTextColor,
                                )
                                Text(
                                    stringResource(R.string.settings_privilege_relation_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PageSecondaryTextColor,
                                )
                            }
                        }
                    }

                    item {
                        Text(
                            stringResource(R.string.settings_privilege_section),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = PageTextColor,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }

                    item {
                        MiuixCard(
                            modifier = Modifier.staggeredEntrance(2).fillMaxWidth().glassBorder(),
                            cornerRadius = AppComponentCornerRadius,
                        ) {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                PrivilegeOption(
                                    title = stringResource(R.string.settings_privilege_none),
                                    summary = stringResource(R.string.settings_privilege_none_summary),
                                    iconRes = R.drawable.ic_settings_app,
                                    selected = selected == RuntimeAccess.Authorizer.NONE,
                                    onClick = {
                                        selected = RuntimeAccess.Authorizer.NONE
                                        RuntimeAccess.setAuthorizer(context, selected)
                                    },
                                )
                                PrivilegeDivider()
                                PrivilegeOption(
                                    title = stringResource(R.string.settings_privilege_root),
                                    summary = rootSummary,
                                    iconRes = R.drawable.ic_settings_engine,
                                    selected = selected == RuntimeAccess.Authorizer.ROOT,
                                    onClick = {
                                        selected = RuntimeAccess.Authorizer.ROOT
                                        RuntimeAccess.setAuthorizer(context, selected)
                                        if (!checkingRoot) {
                                            checkingRoot = true
                                            scope.launch {
                                                val result = withContext(Dispatchers.IO) { RuntimeAccess.probeRoot() }
                                                status = status.copy(
                                                    rootReady = result.ready,
                                                    rootMode = result.mode,
                                                    rootDetail = result.detail,
                                                )
                                                checkingRoot = false
                                                if (!result.ready) message = rootUnavailable
                                            }
                                        }
                                    },
                                )
                                PrivilegeDivider()
                                PrivilegeOption(
                                    title = stringResource(R.string.settings_privilege_shizuku),
                                    summary = shizukuSummary,
                                    iconRes = R.drawable.ic_shizuku,
                                    selected = selected == RuntimeAccess.Authorizer.SHIZUKU,
                                    onClick = {
                                        selected = RuntimeAccess.Authorizer.SHIZUKU
                                        RuntimeAccess.setAuthorizer(context, selected)
                                        when {
                                            !status.shizukuAvailable -> message = shizukuUnavailable
                                            !status.shizukuAuthorized -> {
                                                RuntimeAccess.requestShizukuPermission(requestCode)
                                            }
                                        }
                                    },
                                )
                                PrivilegeDivider()
                                PrivilegeOption(
                                    title = stringResource(R.string.settings_privilege_custom),
                                    summary = customSummary,
                                    iconRes = R.drawable.ic_artemis_patch,
                                    selected = selected == RuntimeAccess.Authorizer.CUSTOM,
                                    onClick = {
                                        selected = RuntimeAccess.Authorizer.CUSTOM
                                        RuntimeAccess.setAuthorizer(context, selected)
                                        customDraft = status.customCommand
                                        editingCustom = true
                                    },
                                )
                            }
                        }
                    }

                    item {
                        Text(
                            stringResource(R.string.settings_privilege_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = PageSecondaryTextColor,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }

                    item { BottomInsetSpacer() }
                }
            }
        }
    }

    if (editingCustom) {
        AppAlertDialog(
            onDismissRequest = { editingCustom = false },
            title = {
                Text(
                    stringResource(R.string.settings_privilege_custom_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.settings_privilege_custom_dialog_message, "%s"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    AppSearchField(
                        query = customDraft,
                        onQueryChange = { customDraft = it.take(512) },
                        onSearch = {
                            RuntimeAccess.setCustomCommand(context, customDraft)
                            status = status.copy(customCommand = customDraft.trim())
                            editingCustom = false
                            message = if (customDraft.isBlank()) customCleared else customSaved
                        },
                        leadingIcon = painterResource(R.drawable.ic_artemis_patch),
                        iconContentDescription = stringResource(R.string.settings_privilege_custom),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        imeAction = ImeAction.Done,
                        keyboardType = KeyboardType.Text,
                    )
                    Text(
                        stringResource(R.string.settings_privilege_custom_hint, "%s"),
                        style = MaterialTheme.typography.bodySmall,
                        color = PageSecondaryTextColor,
                    )
                }
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_confirm),
                    onClick = {
                        RuntimeAccess.setCustomCommand(context, customDraft)
                        status = status.copy(customCommand = customDraft.trim())
                        editingCustom = false
                        message = if (customDraft.isBlank()) customCleared else customSaved
                    },
                )
            },
            dismissButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { editingCustom = false },
                )
            },
        )
    }

    message?.let { text ->
        AppAlertDialog(
            onDismissRequest = { message = null },
            title = { Text(stringResource(R.string.settings_privilege_title), style = MaterialTheme.typography.titleMedium) },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_confirm),
                    onClick = { message = null },
                )
            },
        )
    }
}

@Composable
private fun rootModeLabel(mode: RuntimeAccess.RootMode): String = when (mode) {
    RuntimeAccess.RootMode.MAGISK -> stringResource(R.string.settings_privilege_root_magisk)
    RuntimeAccess.RootMode.KERNELSU -> stringResource(R.string.settings_privilege_root_kernelsu)
    RuntimeAccess.RootMode.APATCH -> stringResource(R.string.settings_privilege_root_apatch)
    RuntimeAccess.RootMode.OTHER -> stringResource(R.string.settings_privilege_root_other)
    RuntimeAccess.RootMode.NONE -> stringResource(R.string.settings_privilege_root_unavailable)
}

@Composable
private fun PrivilegeDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
private fun PrivilegeOption(
    title: String,
    summary: String,
    iconRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = PageTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = PageSecondaryTextColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.bounceOnChange(selected, depth = 0.2f)) {
            RadioButton(selected = selected, onClick = null)
        }
    }
}
