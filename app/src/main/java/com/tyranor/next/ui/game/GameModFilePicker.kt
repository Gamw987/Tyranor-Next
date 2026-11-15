package com.tyranor.next.ui.game

import android.os.Build
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tyranor.next.R
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.AppThemeColors
import com.tyranor.next.theme.GlassPanel
import com.tyranor.next.theme.LocalAppDialogContent
import com.tyranor.next.theme.MilkGlassPanelBackground
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.TextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppDialogMaxWidth
import com.tyranor.next.ui.common.ByteFormats
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.TopBarIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.state.ToggleableState
import java.io.File
import java.util.Locale

internal enum class GameModPickMode {
    ARCHIVES,
    FOLDER,
}

/**
 * 内嵌文件选择卡片：不离开游戏模组页，直接在居中卡片里浏览共享存储。
 * ZIP 模式可多选；文件夹模式导入当前目录，递归扫描由 [GameModImporter] 完成。
 */
@Composable
internal fun GameModFilePickerDialog(
    mode: GameModPickMode,
    onDismiss: () -> Unit,
    onArchives: (List<File>) -> Unit,
    onFolder: (File) -> Unit,
) {
    val root = remember {
        runCatching { Environment.getExternalStorageDirectory().canonicalFile }.getOrNull()
    }
    if (root == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    var current by remember(mode) { mutableStateOf(root) }
    var entries by remember(mode, current) { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember(mode, current) { mutableStateOf(true) }
    var loadFailed by remember(mode, current) { mutableStateOf(false) }
    var selected by remember(mode) { mutableStateOf<Set<String>>(emptySet()) }

    val hasStorageAccess = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
    val internalStorage = stringResource(R.string.game_mods_picker_internal_storage)
    val emptyLabel = stringResource(R.string.game_mods_picker_empty)
    val loadFailedLabel = stringResource(R.string.game_mods_picker_load_failed)
    val noAccessLabel = stringResource(R.string.game_mods_picker_no_access)
    val directoryLabel = stringResource(R.string.game_mods_picker_directory)
    val archiveLabel = stringResource(R.string.game_mods_picker_archive)

    LaunchedEffect(current, mode, hasStorageAccess) {
        loading = true
        loadFailed = false
        entries = try {
            if (!hasStorageAccess) emptyList()
            else withContext(Dispatchers.IO) {
                current.listFiles().orEmpty()
                    .filter { !it.name.startsWith(".") }
                    .filter { file ->
                        when (mode) {
                            GameModPickMode.ARCHIVES -> file.isDirectory || file.isFile && file.extension.equals("zip", true)
                            GameModPickMode.FOLDER -> file.isDirectory
                        }
                    }
                    .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            loadFailed = true
            emptyList()
        } finally {
            loading = false
        }
    }

    fun navigateTo(target: File?) {
        val canonical = target?.let { runCatching { it.canonicalFile }.getOrNull() } ?: return
        if (!canonical.isDirectory) return
        if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) return
        current = canonical
    }

    fun toggle(file: File) {
        val key = file.canonicalPath
        selected = if (key in selected) selected - key else selected + key
    }

    val crumbs = remember(current, root) {
        buildList {
            var cursor: File? = current
            while (cursor != null) {
                add(cursor)
                if (cursor == root) break
                cursor = cursor.parentFile
            }
        }.asReversed()
    }

    BackHandler {
        if (current != root) navigateTo(current.parentFile) else onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val cardHeight = minOf(maxHeight * 0.78f, 620.dp)
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )
            Card(
                modifier = Modifier
                    .widthIn(max = AppDialogMaxWidth)
                    .fillMaxWidth(0.94f)
                    .height(cardHeight)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .glassBorder(),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        AppThemeColors.isGlass -> GlassPanel
                        AppThemeColors.isMilkGlass -> Color.Transparent
                        else -> NavWhite
                    },
                    contentColor = TextColor,
                ),
                shape = AppComponentShape,
            ) {
                Box(Modifier.fillMaxSize().clip(AppComponentShape)) {
                    if (AppThemeColors.isMilkGlass) {
                        MilkGlassPanelBackground(Modifier.matchParentSize())
                    }
                    CompositionLocalProvider(LocalAppDialogContent provides true) {
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 12.dp, end = 18.dp, top = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (current != root) {
                                    TopBarIcon(
                                        painter = painterResource(R.drawable.ic_top_bar_back),
                                        contentDescription = stringResource(R.string.game_mods_picker_parent),
                                        tint = TextColor,
                                        onClick = { navigateTo(current.parentFile) },
                                    )
                                } else {
                                    Spacer(Modifier.size(34.dp))
                                }
                                Text(
                                    text = stringResource(
                                        if (mode == GameModPickMode.ARCHIVES) R.string.game_mods_picker_title_zip
                                        else R.string.game_mods_picker_title_folder,
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 18.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                crumbs.forEachIndexed { index, crumb ->
                                    if (index > 0) {
                                        Text(
                                            "›",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                    Box(
                                        Modifier
                                            .clip(CircleShape)
                                            .background(
                                                if (crumb == current) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                                else Color.Transparent,
                                            )
                                            .clickable { navigateTo(crumb) }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            text = if (crumb == root) internalStorage else crumb.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (crumb == current) MaterialTheme.colorScheme.primary
                                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            Text(
                                text = when {
                                    !hasStorageAccess -> noAccessLabel
                                    mode == GameModPickMode.ARCHIVES -> stringResource(R.string.game_mods_picker_hint_zip)
                                    else -> stringResource(R.string.game_mods_picker_hint_folder)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (!hasStorageAccess) MaterialTheme.colorScheme.error
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
                            )
                            Box(Modifier.fillMaxWidth().weight(1f)) {
                                when {
                                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp))
                                    loadFailed -> Text(
                                        loadFailedLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.align(Alignment.Center),
                                    )
                                    entries.isEmpty() -> Text(
                                        if (!hasStorageAccess) noAccessLabel else emptyLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.align(Alignment.Center),
                                    )
                                    else -> LazyColumn(Modifier.fillMaxSize()) {
                                        items(entries, key = { it.absolutePath }) { file ->
                                            FileEntryRow(
                                                file = file,
                                                mode = mode,
                                                checked = file.canonicalPath in selected,
                                                directoryLabel = directoryLabel,
                                                archiveLabel = archiveLabel,
                                                onClick = {
                                                    if (file.isDirectory) navigateTo(file)
                                                    else if (mode == GameModPickMode.ARCHIVES) toggle(file)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    if (mode == GameModPickMode.ARCHIVES) {
                                        stringResource(R.string.game_mods_picker_selected_count, selected.size)
                                    } else {
                                        current.name.ifBlank { internalStorage }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                DialogTextButton(
                                    text = stringResource(R.string.common_cancel),
                                    onClick = onDismiss,
                                )
                                if (mode == GameModPickMode.ARCHIVES) {
                                    DialogTextButton(
                                        text = stringResource(R.string.game_mods_picker_import_count, selected.size),
                                        enabled = selected.isNotEmpty(),
                                        onClick = {
                                            val files = selected.map(::File).filter { it.isFile }
                                            if (files.isNotEmpty()) onArchives(files)
                                        },
                                    )
                                } else {
                                    DialogTextButton(
                                        text = stringResource(R.string.game_mods_picker_import_folder),
                                        enabled = !loading && !loadFailed && hasStorageAccess,
                                        onClick = { onFolder(current) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileEntryRow(
    file: File,
    mode: GameModPickMode,
    checked: Boolean,
    directoryLabel: String,
    archiveLabel: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(if (file.isDirectory) R.drawable.ic_sheet_folder else R.drawable.ic_sheet_archive),
            contentDescription = null,
            tint = if (file.isDirectory) MaterialTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (file.isDirectory) directoryLabel else "$archiveLabel · ${ByteFormats.formatBinarySize(file.length())}",
                style = MaterialTheme.typography.bodySmall,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        when {
            file.isDirectory -> Icon(
                painter = painterResource(R.drawable.ic_top_bar_back),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(18.dp).rotate(180f),
            )
            mode == GameModPickMode.ARCHIVES -> Checkbox(
                state = if (checked) ToggleableState.On else ToggleableState.Off,
                onClick = null,
            )
        }
    }
}
