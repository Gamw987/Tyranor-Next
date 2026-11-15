package com.tyranor.next.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WinlatorPresetsTest {
    private val base = EngineSettingsStore.Winlator(
        containerId = 7,
        containerName = "Game",
        graphicsDriver = "old",
        dxwrapper = "old",
        screenSize = "old",
        lcAll = "zh_CN.utf8",
        tz = "Asia/Shanghai",
        box64Preset = "old",
        save = true,
    )

    @Test
    fun unityPresetWritesOnlyManagedFieldsAndDisablesContainerSave() {
        val next = WinlatorPresets.build(
            current = base,
            preset = WinlatorPresets.Preset.UNITY,
            graphicsDriver = "turnip,zink",
        )
        assertEquals("turnip,zink", next.graphicsDriver)
        assertEquals("dxvk", next.dxwrapper)
        assertEquals("1280x720", next.screenSize)
        assertEquals("STABILITY", next.box64Preset)
        assertFalse(next.save)
        assertEquals(7, next.containerId)
        assertEquals("Game", next.containerName)
        assertEquals("zh_CN.utf8", next.lcAll)
        assertEquals("Asia/Shanghai", next.tz)
    }

    @Test
    fun compatibilityPresetUsesLowerResolutionAndWineD3dGl() {
        val next = WinlatorPresets.build(
            current = base,
            preset = WinlatorPresets.Preset.COMPATIBILITY,
            graphicsDriver = "vortek,virgl",
        )
        assertEquals("wined3d_gl", next.dxwrapper)
        assertEquals("1024x768", next.screenSize)
        assertEquals("STABILITY", next.box64Preset)
    }

    @Test
    fun nonePresetClearsManagedFieldsButKeepsContainerAndLocale() {
        val next = WinlatorPresets.build(
            current = base,
            preset = WinlatorPresets.Preset.NONE,
            graphicsDriver = "turnip,zink",
        )
        assertEquals("", next.graphicsDriver)
        assertEquals("", next.dxwrapper)
        assertEquals("", next.screenSize)
        assertEquals("", next.box64Preset)
        assertFalse(next.save)
        assertEquals(7, next.containerId)
        assertEquals("zh_CN.utf8", next.lcAll)
    }

    @Test
    fun qualcommGetsTurnipZinkAndOthersGetVortekVirgl() {
        assertEquals(
            "turnip,zink",
            WinlatorPresets.preferredGraphicsDriver("Qualcomm", "taro", "qcom"),
        )
        assertEquals(
            "vortek,virgl",
            WinlatorPresets.preferredGraphicsDriver("MediaTek", "mt6989", "mediatek"),
        )
    }
}
