package com.tyranor.next.ui.game

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.model.ScanGameIntents
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.motion.ProvideEntrance

/** 单游戏 Mod 页：列出 GameMods 插件并启用 / 禁用。 */
class GameModsActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val game = ScanGameIntents.getGame(intent)
        if (game == null) {
            finish()
            return
        }
        setAppScreenContent {
            ProvideEntrance {
                GameModsScreen(game = game)
            }
        }
    }

    companion object {
        fun createIntent(context: Context, game: ScanGame): Intent =
            ScanGameIntents.putGame(Intent(context, GameModsActivity::class.java), game)
    }
}
