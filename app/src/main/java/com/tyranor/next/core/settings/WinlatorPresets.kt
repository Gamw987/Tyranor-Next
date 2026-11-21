package com.tyranor.next.core.settings

import android.content.Context
import android.os.Build
import com.tyranor.next.core.engine.external.WinlatorContract

/**
 * Winlator 外置启动的全局推荐预设。
 *
 * 预设只改 graphicsDriver / dxwrapper / screenSize / box64Preset 四项，
 * 容器匹配、LC_ALL、时区和 save 语义留给现有全局设置；应用时始终 save=false。
 */
object WinlatorPresets {
    enum class Preset {
        UNITY,
        UNREAL,
        DX9_11,
        TWO_D,
        COMPATIBILITY,
        NONE,
    }

    data class Definition(
        val dxwrapper: String,
        val screenSize: String,
        val box64Preset: String,
    )

    private val DEFINITIONS = mapOf(
        Preset.UNITY to Definition(
            dxwrapper = "dxvk",
            screenSize = "1280x720",
            box64Preset = "STABILITY",
        ),
        Preset.UNREAL to Definition(
            dxwrapper = "dxvk",
            screenSize = "1280x720",
            box64Preset = "PERFORMANCE",
        ),
        Preset.DX9_11 to Definition(
            dxwrapper = "dxvk",
            screenSize = "1280x720",
            box64Preset = "PERFORMANCE",
        ),
        Preset.TWO_D to Definition(
            dxwrapper = WinlatorContract.DXWRAPPER_WINED3D_GL,
            screenSize = "1280x720",
            box64Preset = "INTERMEDIATE",
        ),
        Preset.COMPATIBILITY to Definition(
            dxwrapper = WinlatorContract.DXWRAPPER_WINED3D_GL,
            screenSize = "1024x768",
            box64Preset = "STABILITY",
        ),
        Preset.NONE to Definition(
            dxwrapper = "",
            screenSize = "",
            box64Preset = "",
        ),
    )

    fun definition(preset: Preset): Definition = DEFINITIONS.getValue(preset)

    fun apply(context: Context, preset: Preset): EngineSettingsStore.Winlator {
        val current = EngineSettingsStore.loadWinlator(context)
        val next = build(current, preset, preferredGraphicsDriver())
        EngineSettingsStore.saveWinlator(context, next)
        return next
    }

    fun match(settings: EngineSettingsStore.Winlator): Preset? {
        if (settings.graphicsDriver.isBlank() &&
            settings.dxwrapper.isBlank() &&
            settings.screenSize.isBlank() &&
            settings.box64Preset.isBlank()
        ) {
            return Preset.NONE
        }
        val driver = settings.graphicsDriver
        return Preset.entries.firstOrNull { preset ->
            if (preset == Preset.NONE) return@firstOrNull false
            val definition = definition(preset)
            driver == preferredGraphicsDriver() &&
                settings.dxwrapper == definition.dxwrapper &&
                settings.screenSize == definition.screenSize &&
                settings.box64Preset == definition.box64Preset
        }
    }

    fun preferredGraphicsDriver(): String = preferredGraphicsDriver(
        socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER
        } else {
            null
        },
        hardware = Build.HARDWARE,
        board = Build.BOARD,
    )

    internal fun preferredGraphicsDriver(
        socManufacturer: String?,
        hardware: String?,
        board: String?,
    ): String {
        val fingerprint = listOf(socManufacturer, hardware, board)
            .filterNotNull()
            .joinToString(" ")
            .lowercase()
        val qualcomm = fingerprint.contains("qcom") ||
            fingerprint.contains("qualcomm") ||
            fingerprint.contains("adreno") ||
            fingerprint.contains("taro") ||
            fingerprint.contains("sm8")
        return if (qualcomm) "turnip,zink" else "vortek,virgl"
    }

    internal fun build(
        current: EngineSettingsStore.Winlator,
        preset: Preset,
        graphicsDriver: String,
    ): EngineSettingsStore.Winlator {
        val definition = definition(preset)
        return current.copy(
            graphicsDriver = if (preset == Preset.NONE) "" else graphicsDriver,
            dxwrapper = definition.dxwrapper,
            screenSize = definition.screenSize,
            box64Preset = definition.box64Preset,
            save = false,
        )
    }
}
