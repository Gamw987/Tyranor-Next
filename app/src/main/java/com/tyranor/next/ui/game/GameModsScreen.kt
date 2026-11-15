package com.tyranor.next.ui.game

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.tyranor.next.core.game.mods.GameModImporter
import com.tyranor.next.core.game.mods.GameModInfo
import com.tyranor.next.core.game.mods.GameModManager
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppBottomSheet
import com.tyranor.next.ui.common.AppNavItem
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.NoRippleTextButton
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

/** 单游戏 Mod 列表：读取 GameMods 插件，启用 / 禁用后原地刷新。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GameModsScreen(game: ScanGame) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mods by remember(game.uri) { mutableStateOf<List<GameModInfo>>(emptyList()) }
    var loading by remember(game.uri) { mutableStateOf(true) }
    var loadFailed by remember(game.uri) { mutableStateOf(false) }
    var busyId by remember(game.uri) { mutableStateOf<String?>(null) }
    var pendingDelete by remember(game.uri) { mutableStateOf<GameModInfo?>(null) }
    var showImportSheet by remember(game.uri) { mutableStateOf(false) }
    var importing by remember(game.uri) { mutableStateOf(false) }
    var importSummary by remember(game.uri) { mutableStateOf<GameModImporter.Summary?>(null) }
    val importer = remember { GameModImporter(context.applicationContext) }

    val versionFormat = stringResource(R.string.game_mods_version)
    val enabledLabel = stringResource(R.string.game_mods_installed)
    val disabledLabel = stringResource(R.string.game_mods_not_installed)
    val enableLabel = stringResource(R.string.game_mods_install)
    val disableLabel = stringResource(R.string.game_mods_remove)
    val deleteLabel = stringResource(R.string.game_mods_delete)
    val busyLabel = stringResource(R.string.game_mods_busy)
    val enableSuccessFormat = stringResource(R.string.game_mods_install_success)
    val disableSuccessFormat = stringResource(R.string.game_mods_remove_success)
    val deleteSuccessFormat = stringResource(R.string.game_mods_delete_success)
    val errorMessages = mapOf(
        GameModError.ROOT_MISSING to stringResource(R.string.game_mods_root_missing),
        GameModError.INVALID_GAME to stringResource(R.string.game_mods_invalid_game),
        GameModError.PLUGIN_NOT_FOUND to stringResource(R.string.game_mods_plugin_missing),
        GameModError.INVALID_MANIFEST to stringResource(R.string.game_mods_invalid_manifest),
        GameModError.UNSUPPORTED_ENGINE to stringResource(R.string.game_mods_unsupported_engine),
        GameModError.ALREADY_ENABLED to stringResource(R.string.game_mods_already_installed),
        GameModError.NOT_ENABLED to stringResource(R.string.game_mods_not_installed_error),
        GameModError.IN_USE to stringResource(R.string.game_mods_delete_in_use),
        GameModError.PLUGIN_EXISTS to stringResource(R.string.game_mods_import_duplicate),
        GameModError.INVALID_ARCHIVE to stringResource(R.string.game_mods_import_invalid_archive),
        GameModError.TOO_MANY to stringResource(R.string.game_mods_import_too_many),
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

    fun runAction(mod: GameModInfo, successFormat: String, block: suspend () -> Unit) {
        if (busyId != null) return
        busyId = mod.id
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                AppNotice.makeText(
                    context,
                    String.format(successFormat, mod.name),
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

    fun runToggle(mod: GameModInfo) {
        val enable = !mod.enabled
        runAction(mod, if (enable) enableSuccessFormat else disableSuccessFormat) {
            if (enable) GameModManager.enable(game.uri, mod.id)
            else GameModManager.disable(game.uri, mod.id)
        }
    }

    fun runDelete(mod: GameModInfo) {
        runAction(mod, deleteSuccessFormat) {
            GameModManager.delete(game.uri, mod.id)
        }
    }

    fun runImport(block: () -> GameModImporter.Summary) {
        if (importing || busyId != null) return
        importing = true
        scope.launch {
            try {
                val summary = withContext(Dispatchers.IO) { block() }
                refresh()
                importSummary = summary
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
                importing = false
            }
        }
    }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            showImportSheet = false
            runImport { importer.importArchives(uris) }
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            showImportSheet = false
            runImport { importer.importTree(uri) }
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
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TopBarIcon(
                                painterResource(R.drawable.ic_game_add),
                                stringResource(R.string.game_mods_add),
                                MiuixTheme.colorScheme.primary,
                            ) {
                                if (!importing) showImportSheet = true
                            }
                            TopBarIcon(
                                painterResource(R.drawable.ic_game_scan),
                                stringResource(R.string.game_mods_refresh),
                                MiuixTheme.colorScheme.primary,
                                spin = true,
                            ) {
                                if (!importing) scope.launch { refresh() }
                            }
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
                        enableLabel = enableLabel,
                        disableLabel = disableLabel,
                        deleteLabel = deleteLabel,
                        busyLabel = busyLabel,
                        enabledLabel = enabledLabel,
                        disabledLabel = disabledLabel,
                        versionFormat = versionFormat,
                        modifier = Modifier
                            .staggeredEntrance(index + 1)
                            .then(listItemMotion()),
                        onToggle = { runToggle(mod) },
                        onDelete = { pendingDelete = mod },
                    )
                }
            }
        }
    }

    if (showImportSheet) {
        AppBottomSheet(onDismissRequest = { showImportSheet = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.game_mods_import_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Text(
                    stringResource(R.string.game_mods_import_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
                AppNavItem(
                    title = stringResource(R.string.game_mods_import_zip),
                    summary = stringResource(R.string.game_mods_import_zip_hint),
                    leadingIcon = R.drawable.ic_sheet_archive,
                    onClick = {
                        showImportSheet = false
                        zipPicker.launch(arrayOf("*/*"))
                    },
                )
                AppNavItem(
                    title = stringResource(R.string.game_mods_import_folder),
                    summary = stringResource(R.string.game_mods_import_folder_hint),
                    leadingIcon = R.drawable.ic_sheet_folder,
                    onClick = {
                        showImportSheet = false
                        folderPicker.launch(null)
                    },
                )
            }
        }
    }

    importSummary?.let { summary ->
        val importedText = stringResource(R.string.game_mods_import_imported, summary.importedCount)
        val skippedText = stringResource(R.string.game_mods_import_skipped, summary.skippedCount)
        val failedText = stringResource(R.string.game_mods_import_failed, summary.failedCount)
        val detail = summary.failed.take(6).joinToString("\n") { failure ->
            val reason = errorMessages[failure.error] ?: errorMessages.getValue(GameModError.IO)
            "${failure.source}: $reason"
        }
        AppAlertDialog(
            onDismissRequest = { importSummary = null },
            title = {
                Text(
                    stringResource(R.string.game_mods_import_result),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(importedText, style = MaterialTheme.typography.bodyMedium)
                    Text(skippedText, style = MaterialTheme.typography.bodyMedium)
                    Text(failedText, style = MaterialTheme.typography.bodyMedium)
                    if (detail.isNotBlank()) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_confirm),
                    onClick = { importSummary = null },
                )
            },
        )
    }

    pendingDelete?.let { mod ->
        AppAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = {
                Text(
                    stringResource(R.string.game_mods_delete_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    stringResource(R.string.game_mods_delete_message, mod.name),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                DialogTextButton(
                    text = deleteLabel,
                    contentColor = MaterialTheme.colorScheme.error,
                    onClick = {
                        pendingDelete = null
                        runDelete(mod)
                    },
                )
            },
            dismissButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { pendingDelete = null },
                )
            },
        )
    }
}

@Composable
private fun GameModCard(
    mod: GameModInfo,
    busy: Boolean,
    enableLabel: String,
    disableLabel: String,
    deleteLabel: String,
    busyLabel: String,
    enabledLabel: String,
    disabledLabel: String,
    versionFormat: String,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    MiuixCard(
        modifier = modifier.fillMaxWidth().glassBorder(),
        cornerRadius = AppComponentCornerRadius,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NoRippleTextButton(
                    text = if (mod.enabled) disableLabel else enableLabel,
                    enabled = !busy,
                    contentColor = if (mod.enabled) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    onClick = onToggle,
                )
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
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
                    Text(
                        when {
                            busy -> busyLabel
                            mod.enabled -> enabledLabel
                            else -> disabledLabel
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            busy -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                            mod.enabled -> MaterialTheme.colorScheme.primary
                            else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                }
                NoRippleTextButton(
                    text = deleteLabel,
                    enabled = !busy,
                    contentColor = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
            if (mod.description.isNotBlank()) {
                Text(
                    mod.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                )
            }
        }
    }
}
