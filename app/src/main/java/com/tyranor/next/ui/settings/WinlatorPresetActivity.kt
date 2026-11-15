package com.tyranor.next.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.WinlatorPresets
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.PageSecondaryTextColor
import com.tyranor.next.theme.PageTextColor
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppSecondaryTopBar
import com.tyranor.next.ui.common.BottomInsetSpacer
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.bounceOnChange
import com.tyranor.next.ui.common.motion.staggeredEntrance
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold

/** 全局 Winlator 预设选择页；点击即写入 EngineSettingsStore。 */
class WinlatorPresetActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreenContent { WinlatorPresetScreen() }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, WinlatorPresetActivity::class.java)
    }
}

private data class PresetRow(
    val preset: WinlatorPresets.Preset,
    @StringRes val title: Int,
    @StringRes val summary: Int,
    @DrawableRes val icon: Int,
)

private val PRESET_ROWS = listOf(
    PresetRow(
        WinlatorPresets.Preset.UNITY,
        R.string.winlator_preset_unity,
        R.string.winlator_preset_unity_summary,
        R.drawable.ic_engine_icon,
    ),
    PresetRow(
        WinlatorPresets.Preset.UNREAL,
        R.string.winlator_preset_unreal,
        R.string.winlator_preset_unreal_summary,
        R.drawable.ic_engine_icon,
    ),
    PresetRow(
        WinlatorPresets.Preset.DX9_11,
        R.string.winlator_preset_dx9_11,
        R.string.winlator_preset_dx9_11_summary,
        R.drawable.ic_engine_icon,
    ),
    PresetRow(
        WinlatorPresets.Preset.TWO_D,
        R.string.winlator_preset_2d,
        R.string.winlator_preset_2d_summary,
        R.drawable.ic_engine_icon,
    ),
    PresetRow(
        WinlatorPresets.Preset.COMPATIBILITY,
        R.string.winlator_preset_compatibility,
        R.string.winlator_preset_compatibility_summary,
        R.drawable.ic_engine_icon,
    ),
    PresetRow(
        WinlatorPresets.Preset.NONE,
        R.string.winlator_preset_none,
        R.string.winlator_preset_none_summary,
        R.drawable.ic_settings_app,
    ),
)

@Composable
internal fun WinlatorPresetScreen() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(EngineSettingsStore.loadWinlator(context)) }
    var selected by remember { mutableStateOf(WinlatorPresets.match(current)) }
    var message by remember { mutableStateOf<String?>(null) }

    fun apply(preset: WinlatorPresets.Preset) {
        current = WinlatorPresets.apply(context, preset)
        selected = WinlatorPresets.match(current)
        message = context.getString(
            if (preset == WinlatorPresets.Preset.NONE) {
                R.string.winlator_preset_cleared
            } else {
                R.string.winlator_preset_applied
            },
            if (preset == WinlatorPresets.Preset.NONE) {
                ""
            } else {
                context.getString(presetTitleRes(preset))
            },
        ).trim()
    }

    val activePreset = selected
    val currentSummary = when {
        activePreset == null -> stringResource(R.string.winlator_preset_current_manual)
        activePreset == WinlatorPresets.Preset.NONE -> stringResource(R.string.winlator_preset_current_none)
        else -> stringResource(
            R.string.winlator_preset_current_value,
            stringResource(presetTitleRes(activePreset)),
        )
    }

    MiuixSettingsTheme {
        ProvideEntrance {
            MiuixScaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                contentWindowInsets = WindowInsets(0.dp),
                topBar = {
                    AppSecondaryTopBar(
                        title = stringResource(R.string.winlator_preset_title),
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
                                    stringResource(R.string.winlator_preset_intro),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PageSecondaryTextColor,
                                )
                                Text(
                                    currentSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = PageTextColor,
                                )
                            }
                        }
                    }

                    item {
                        MiuixCard(
                            modifier = Modifier.staggeredEntrance(1).fillMaxWidth().glassBorder(),
                            cornerRadius = AppComponentCornerRadius,
                        ) {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                PRESET_ROWS.forEachIndexed { index, row ->
                                    if (index > 0) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                        )
                                    }
                                    PresetOption(
                                        title = stringResource(row.title),
                                        summary = stringResource(row.summary),
                                        iconRes = row.icon,
                                        selected = selected == row.preset,
                                        onClick = { apply(row.preset) },
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Text(
                            stringResource(R.string.winlator_preset_note),
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

    message?.let { text ->
        AppAlertDialog(
            onDismissRequest = { message = null },
            title = {
                Text(
                    stringResource(R.string.winlator_preset_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
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
private fun PresetOption(
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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.bounceOnChange(selected, depth = 0.2f)) {
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@StringRes
private fun presetTitleRes(preset: WinlatorPresets.Preset): Int = when (preset) {
    WinlatorPresets.Preset.UNITY -> R.string.winlator_preset_unity
    WinlatorPresets.Preset.UNREAL -> R.string.winlator_preset_unreal
    WinlatorPresets.Preset.DX9_11 -> R.string.winlator_preset_dx9_11
    WinlatorPresets.Preset.TWO_D -> R.string.winlator_preset_2d
    WinlatorPresets.Preset.COMPATIBILITY -> R.string.winlator_preset_compatibility
    WinlatorPresets.Preset.NONE -> R.string.winlator_preset_none
}
