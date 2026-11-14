package com.tyranor.next.ui.game

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.mods.GameModError
import com.tyranor.next.core.game.mods.GameModException
import com.tyranor.next.core.game.mods.GameModInfo
import com.tyranor.next.core.game.mods.GameModManager
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.TopBarIcon
import com.tyranor.next.ui.common.motion.DelayedProgress
import com.tyranor.next.ui.common.motion.listItemMotion
import com.tyranor.next.ui.common.motion.skipEntranceOnDrag
import com.tyranor.next.ui.common.motion.staggeredEntrance
import com.tyranor.next.ui.common.notice.AppNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 单游戏 Mod 列表：读取 GameMods 插件，安装 / 卸载后原地刷新。 */
@Composable
internal fun GameModsScreen(game: ScanGame) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mods by remember(game.uri) { mutableStateOf<List<GameModInfo>>(emptyList()) }
    var loading by remember(game.uri) { mutableStateOf(true) }
    var loadFailed by remember(game.uri) { mutableStateOf(false) }
    var busyId by remember(game.uri) { mutableStateOf<String?>(null) }
    var pendingRemove by remember(game.uri) { mutableStateOf<GameModInfo?>(null) }

    val versionFormat = stringResource(R.string.game_mods_version)
    val installedLabel = stringResource(R.string.game_mods_installed)
    val notInstalledLabel = stringResource(R.string.game_mods_not_installed)
    val installLabel = stringResource(R.string.game_mods_install)
    val removeLabel = stringResource(R.string.game_mods_remove)
    val busyLabel = stringResource(R.string.game_mods_busy)
    val installSuccessFormat = stringResource(R.string.game_mods_install_success)
    val removeSuccessFormat = stringResource(R.string.game_mods_remove_success)
    val errorMessages = mapOf(
        GameModError.ROOT_MISSING to stringResource(R.string.game_mods_root_missing),
        GameModError.INVALID_GAME to stringResource(R.string.game_mods_invalid_game),
        GameModError.PLUGIN_NOT_FOUND to stringResource(R.string.game_mods_plugin_missing),
        GameModError.INVALID_MANIFEST to stringResource(R.string.game_mods_invalid_manifest),
        GameModError.UNSUPPORTED_ENGINE to stringResource(R.string.game_mods_unsupported_engine),
        GameModError.ALREADY_INSTALLED to stringResource(R.string.game_mods_already_installed),
        GameModError.NOT_INSTALLED to stringResource(R.string.game_mods_not_installed_error),
        GameModError.IO to stringResource(R.string.game_mods_operation_failed),
    )

    suspend fun refresh() {
        loading = true
        try {
            mods = withContext(Dispatchers.IO) { GameModManager.list(game.uri) }
            loadFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            loadFailed = true
        } finally {
            loading = false
        }
    }

    fun runAction(mod: GameModInfo, install: Boolean) {
        if (busyId != null) return
        busyId = mod.id
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (install) {
                        GameModManager.install(game.uri, mod.id)
                    } else {
                        GameModManager.remove(game.uri, mod.id)
                    }
                }
                val pattern = if (install) installSuccessFormat else removeSuccessFormat
                AppNotice.makeText(
                    context,
                    String.format(pattern, mod.name),
                    Toast.LENGTH_SHORT,
                    AppNotice.Kind.Success,
                ).show()
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: GameModException) {
                AppNotice.makeText(
                    context,
                    errorMessages[e.error] ?: errorMessages.getValue(GameModError.IO),
                    Toast.LENGTH_LONG,
                    AppNotice.Kind.Failure,
                ).show()
            } catch (_: Throwable) {
                AppNotice.makeText(
                    context,
                    errorMessages.getValue(GameModError.IO),
                    Toast.LENGTH_LONG,
                    AppNotice.Kind.Failure,
                ).show()
            } finally {
                busyId = null
            }
        }
    }

    LaunchedEffect(game.uri) {
        refresh()
    }

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize().skipEntranceOnDrag(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppSecondaryTopBar(
                    title = stringResource(R.string.game_mods_title),
                    background = Color.Transparent,
                    contentColor = PageTextColor,
                    trailing = {
                        TopBarIcon(
                            painterResource(R.drawable.ic_game_scan),
                            stringResource(R.string.game_mods_refresh),
                            MiuixTheme.colorScheme.primary,
                        ) {
                            scope.launch { refresh() }
                        }
                    },
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp)
                    .padding(top = innerPadding.calculateTopPadding()),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (loading && mods.isEmpty()) {
                    item(key = "loading") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            DelayedProgress { CircularProgressIndicator() }
                        }
                    }
                }

                if (loadFailed && !loading) {
                    item(key = "load_error") {
                        MiuixCard(
                            modifier = Modifier.fillMaxWidth().glassBorder(),
                            cornerRadius = AppComponentCornerRadius,
                        ) {
                            Text(
                                stringResource(R.string.game_mods_load_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                }

                if (!loading && !loadFailed && mods.isEmpty()) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier.fillParentMaxHeight()
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_module),
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.size(40.dp),
                                )
                                Text(
                                    stringResource(R.string.game_mods_empty_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                )
                                Text(
                                    stringResource(R.string.game_mods_empty_message),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }

                itemsIndexed(
                    items = mods,
                    key = { _, mod -> mod.id },
                ) { index, mod ->
                    GameModCard(
                        mod = mod,
                        busy = busyId == mod.id,
                        installLabel = installLabel,
                        removeLabel = removeLabel,
                        busyLabel = busyLabel,
                        installedLabel = installedLabel,
                        notInstalledLabel = notInstalledLabel,
                        versionFormat = versionFormat,
                        modifier = Modifier
                            .staggeredEntrance(index + 1)
                            .then(listItemMotion()),
                        onInstall = { runAction(mod, install = true) },
                        onRemove = { pendingRemove = mod },
                    )
                }
            }
        }
    }

    pendingRemove?.let { mod ->
        AppAlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = {
                Text(
                    stringResource(R.string.game_mods_remove_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    stringResource(R.string.game_mods_remove_message, mod.name),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                DialogTextButton(
                    text = removeLabel,
                    contentColor = MaterialTheme.colorScheme.error,
                    onClick = {
                        pendingRemove = null
                        runAction(mod, install = false)
                    },
                )
            },
            dismissButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { pendingRemove = null },
                )
            },
        )
    }
}

@Composable
private fun GameModCard(
    mod: GameModInfo,
    busy: Boolean,
    installLabel: String,
    removeLabel: String,
    busyLabel: String,
    installedLabel: String,
    notInstalledLabel: String,
    versionFormat: String,
    modifier: Modifier = Modifier,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    MiuixCard(
        modifier = modifier.fillMaxWidth().glassBorder(),
        cornerRadius = AppComponentCornerRadius,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        mod.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (mod.version.isNotBlank()) {
                        Text(
                            String.format(versionFormat, mod.version),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                Text(
                    if (mod.installed) installedLabel else notInstalledLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (mod.installed) MaterialTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (mod.description.isNotBlank()) {
                Text(
                    mod.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            AppNavItem(
                title = when {
                    busy -> busyLabel
                    mod.installed -> removeLabel
                    else -> installLabel
                },
                showLeadingIcon = false,
                showArrow = false,
                drawSurface = false,
                verticalPadding = 10.dp,
                titleColor = if (mod.installed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                onClick = when {
                    busy -> null
                    mod.installed -> onRemove
                    else -> onInstall
                },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
