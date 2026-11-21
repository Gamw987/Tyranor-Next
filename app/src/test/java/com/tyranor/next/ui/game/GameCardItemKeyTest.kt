package com.tyranor.next.ui.game

import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GameCardItemKeyTest {
    private val game = ScanGame(
        title = "测试游戏",
        uri = "content://games/test",
        engine = EngineType.KIRIKIRI,
        launchTarget = "data.xp3",
    )

    @Test
    fun changesWhenCoverChanges() {
        val covered = game.copy(coverUri = "file:///covers/new.jpg", coverSource = "vndb")

        assertNotEquals(gameCardItemKey(game), gameCardItemKey(covered))
    }

    @Test
    fun staysStableForNonVisualLibraryFields() {
        val recentlyOpened = game.copy(openTime = 1234L)

        assertEquals(gameCardItemKey(game), gameCardItemKey(recentlyOpened))
    }

    @Test
    fun showsSaveManagementForAllEngines() {
        // 存档管理对所有游戏开放；能否实际管理由存档页按引擎解析结果决定。
        assertEquals(true, shouldShowSaveManagement(EngineType.RENPY, sdkInt = 29))
        assertEquals(true, shouldShowSaveManagement(EngineType.RENPY, sdkInt = 30))
        assertEquals(true, shouldShowSaveManagement(EngineType.ETHORNELL))
        assertEquals(true, shouldShowSaveManagement(EngineType.RPGMAKER))
        assertEquals(true, shouldShowSaveManagement(EngineType.RUFFLE))
        assertEquals(true, shouldShowSaveManagement(EngineType.KIRIKIRI))
        assertEquals(true, shouldShowSaveManagement(EngineType.RPG_MV))
        assertEquals(true, shouldShowSaveManagement(EngineType.YURIS))
        assertEquals(true, shouldShowSaveManagement(EngineType.NINTENDO_SWITCH))
        assertEquals(true, shouldShowSaveManagement(EngineType.PC))
        assertEquals(true, shouldShowSaveManagement(EngineType.CATSYSTEM2))
        assertEquals(true, shouldShowSaveManagement(EngineType.ANDROID_APP))
        assertEquals(
            true,
            shouldShowSaveManagement(
                game.copy(uri = "androidapp://com.example.game", engine = EngineType.UNKNOWN),
            ),
        )
    }
}
