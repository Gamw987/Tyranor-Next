package com.tyranor.next.core.game.mods

import android.os.Environment
import com.tyranor.next.core.game.model.GamePathUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一个可安装的游戏 Mod 的展示信息。 */
data class GameModInfo(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val installed: Boolean,
)

/** 游戏 Mod 操作的可预期失败类型；文案由 UI 层映射。 */
enum class GameModError {
    ROOT_MISSING,
    INVALID_GAME,
    PLUGIN_NOT_FOUND,
    INVALID_MANIFEST,
    UNSUPPORTED_ENGINE,
    ALREADY_INSTALLED,
    NOT_INSTALLED,
    IO,
}

class GameModException(
    val error: GameModError,
    cause: Throwable? = null,
) : Exception(error.name, cause)

/**
 * 文件系统层的游戏 Mod 管理器，兼容 `GameMods` 的 manifest/state/backups 约定。
 *
 * 模块根目录固定为共享存储下的 `GameMods/`：
 * - `plugins/<id>/plugin.json`：模块清单；
 * - `state/<game-key>.<id>.json`：每个游戏/模块的安装记录与备份（兼容旧的 `<game-key>.json`）；
 * - `backups/<game-key>/<id>-<time>/`：安装前文件备份。
 *
 * 这里不依赖 Compose，也不把本地化文案作为异常内容。
 */
object GameModManager {
    private const val ROOT_DIR = "GameMods"
    private const val ENGINE = "renpy"
    private val SAFE_ID = Regex("^[a-z0-9_-]+$")

    private data class ModFile(
        val src: String,
        val dst: String,
        val optional: Boolean,
    )

    private data class ModManifest(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val engine: String,
        val files: List<ModFile>,
        val creates: List<String>,
        val cleanup: List<String>,
    )

    private fun modsRoot(): File = File(Environment.getExternalStorageDirectory(), ROOT_DIR)

    private fun pluginsRoot(): File = File(modsRoot(), "plugins")

    private fun stateRoot(): File = File(modsRoot(), "state")

    private fun backupRoot(): File = File(modsRoot(), "backups")

    fun gameDirectory(gameUri: String): File? =
        GamePathUtils.safUriToPath(gameUri)
            ?.let(::File)
            ?.takeIf { it.isDirectory }

    fun list(gameUri: String): List<GameModInfo> {
        val game = gameDirectory(gameUri)?.canonicalFile ?: return emptyList()
        val plugins = pluginsRoot()
        if (!plugins.isDirectory) return emptyList()
        return plugins.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory }
            .mapNotNull { dir -> runCatching { readManifest(dir) }.getOrNull() }
            .filter { it.engine == ENGINE && it.files.isNotEmpty() }
            .map { manifest ->
                GameModInfo(
                    id = manifest.id,
                    name = manifest.name,
                    version = manifest.version,
                    description = manifest.description,
                    installed = findState(game, manifest.id) != null,
                )
            }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .toList()
    }

    fun install(gameUri: String, pluginId: String) {
        if (!SAFE_ID.matches(pluginId)) throw GameModException(GameModError.INVALID_MANIFEST)
        val game = requireGame(gameUri)
        val pluginDir = File(pluginsRoot(), pluginId)
        val manifest = readManifest(pluginDir)
        if (manifest.engine != ENGINE) throw GameModException(GameModError.UNSUPPORTED_ENGINE)
        if (findState(game, pluginId) != null) throw GameModException(GameModError.ALREADY_INSTALLED)

        val key = stateKey(game)
        val installed = JSONArray()
        val created = JSONArray()
        val cleaned = JSONArray()
        var backupDir: File? = null

        try {
            for (file in manifest.files) {
                val src = resolveInside(pluginDir, file.src)
                if (!src.isFile) {
                    if (file.optional) continue
                    throw GameModException(GameModError.INVALID_MANIFEST)
                }
                val dst = resolveInside(game, file.dst)
                val existed = dst.isFile
                val backup = if (existed) {
                    backupFile(game, key, pluginId, file.dst).also {
                        backupDir = backupDir ?: it.parentFile
                    }
                } else {
                    null
                }
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                installed.put(
                    JSONObject()
                        .put("dst", file.dst)
                        .put("src", src.absolutePath)
                        .put("existed", existed)
                        .apply { backup?.let { put("backup", it.absolutePath) } }
                        .put("sha256", sha256(dst)),
                )
            }

            for (rel in manifest.creates) {
                val dst = resolveInside(game, rel)
                if (!dst.exists()) {
                    dst.parentFile?.mkdirs()
                    if (!dst.createNewFile()) throw GameModException(GameModError.IO)
                    created.put(rel)
                }
            }

            for (rel in manifest.cleanup) {
                val dst = resolveInside(game, rel)
                val backup = if (dst.exists()) {
                    backupFile(game, key, pluginId, rel).also {
                        backupDir = backupDir ?: it.parentFile
                    }
                } else {
                    null
                }
                if (dst.exists() && !dst.delete()) throw GameModException(GameModError.IO)
                cleaned.put(
                    JSONObject().put("dst", rel).apply {
                        backup?.let { put("backup", it.absolutePath) }
                    },
                )
            }

            val state = JSONObject()
                .put("plugin", manifest.id)
                .put("name", manifest.name)
                .put("version", manifest.version)
                .put("game", game.absolutePath)
                .put("installed_at", System.currentTimeMillis())
                .apply { backupDir?.let { put("backup_dir", it.absolutePath) } }
                .put("files", installed)
                .put("created", created)
                .put("cleanup", cleaned)
            writeState(stateFile(game, pluginId), state)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (e: GameModException) {
            throw e
        } catch (e: Exception) {
            throw GameModException(GameModError.IO, e)
        }
    }

    fun remove(gameUri: String, pluginId: String) {
        if (!SAFE_ID.matches(pluginId)) throw GameModException(GameModError.INVALID_MANIFEST)
        val game = requireGame(gameUri)
        val stateFile = findState(game, pluginId)
            ?: throw GameModException(GameModError.NOT_INSTALLED)
        val state = runCatching { JSONObject(stateFile.readText()) }
            .getOrElse { throw GameModException(GameModError.INVALID_MANIFEST, it) }
        if (state.optString("plugin") != pluginId) {
            throw GameModException(GameModError.NOT_INSTALLED)
        }

        try {
            val files = state.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                val item = files.optJSONObject(i) ?: continue
                val dst = resolveInside(game, item.optString("dst"))
                val backup = item.optString("backup").takeIf { it.isNotBlank() }?.let(::File)
                if (item.optBoolean("existed") && backup?.isFile == true) {
                    dst.parentFile?.mkdirs()
                    backup.copyTo(dst, overwrite = true)
                } else if (dst.exists()) {
                    dst.delete()
                }
            }

            val created = state.optJSONArray("created") ?: JSONArray()
            for (i in 0 until created.length()) {
                val dst = resolveInside(game, created.optString(i))
                if (dst.isFile && dst.length() == 0L) dst.delete()
            }

            val cleanup = state.optJSONArray("cleanup") ?: JSONArray()
            for (i in 0 until cleanup.length()) {
                val item = cleanup.optJSONObject(i) ?: continue
                val dst = resolveInside(game, item.optString("dst"))
                val backup = item.optString("backup").takeIf { it.isNotBlank() }?.let(::File)
                if (backup?.isFile == true) {
                    dst.parentFile?.mkdirs()
                    backup.copyTo(dst, overwrite = true)
                } else if (dst.exists()) {
                    dst.delete()
                }
            }

            if (!stateFile.delete()) throw GameModException(GameModError.IO)
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (e: GameModException) {
            throw e
        } catch (e: Exception) {
            throw GameModException(GameModError.IO, e)
        }
    }

    private fun requireGame(gameUri: String): File {
        val game = gameDirectory(gameUri) ?: throw GameModException(GameModError.INVALID_GAME)
        if (!File(game, "game").isDirectory) throw GameModException(GameModError.INVALID_GAME)
        return game.canonicalFile
    }

    private fun readManifest(pluginDir: File): ModManifest {
        if (!pluginDir.isDirectory) throw GameModException(GameModError.PLUGIN_NOT_FOUND)
        val path = File(pluginDir, "plugin.json")
        if (!path.isFile) throw GameModException(GameModError.PLUGIN_NOT_FOUND)
        val json = runCatching { JSONObject(path.readText()) }
            .getOrElse { throw GameModException(GameModError.INVALID_MANIFEST, it) }
        val id = json.optString("id")
        if (!SAFE_ID.matches(id) || id != pluginDir.name) {
            throw GameModException(GameModError.INVALID_MANIFEST)
        }
        val filesJson = json.optJSONArray("files") ?: JSONArray()
        val files = buildList {
            for (i in 0 until filesJson.length()) {
                val item = filesJson.optJSONObject(i) ?: continue
                val src = item.optString("src")
                val dst = item.optString("dst")
                if (src.isBlank() || dst.isBlank()) throw GameModException(GameModError.INVALID_MANIFEST)
                add(ModFile(src, dst, item.optBoolean("optional")))
            }
        }
        return ModManifest(
            id = id,
            name = json.optString("name", id),
            version = json.optString("version", ""),
            description = json.optString("description", ""),
            engine = json.optString("engine", ""),
            files = files,
            creates = json.optJSONArray("creates").toStringList(),
            cleanup = json.optJSONArray("cleanup").toStringList(),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun stateKey(game: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(game.canonicalPath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
            .take(16)
        return "$digest-${game.name}"
    }

    private fun legacyStateFile(game: File): File = File(stateRoot(), "${stateKey(game)}.json")

    private fun stateFile(game: File, pluginId: String): File =
        File(stateRoot(), "${stateKey(game)}.$pluginId.json")

    /**
     * 兼容 Python `modctl` 的单游戏 state（`<key>.json`），同时允许每个插件
     * 使用独立 state（`<key>.<plugin>.json`）以支持一个游戏安装多个 Mod。
     */
    private fun findState(game: File, pluginId: String): File? {
        val modern = stateFile(game, pluginId)
        if (modern.isFile) return modern
        val legacy = legacyStateFile(game)
        if (!legacy.isFile) return null
        val json = runCatching { JSONObject(legacy.readText()) }.getOrNull() ?: return null
        return legacy.takeIf { json.optString("plugin") == pluginId }
    }

    private fun backupFile(game: File, key: String, pluginId: String, rel: String): File {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val target = resolveInside(backupRoot(), "$key/$pluginId-$timestamp/$rel")
        target.parentFile?.mkdirs()
        val source = resolveInside(game, rel)
        source.copyTo(target, overwrite = true)
        return target
    }

    private fun resolveInside(root: File, rel: String): File {
        if (rel.isBlank() || rel.startsWith('/') || rel.startsWith('\\')) {
            throw GameModException(GameModError.INVALID_MANIFEST)
        }
        val rootPath = root.canonicalFile
        val target = File(rootPath, rel).canonicalFile
        if (target != rootPath && !target.path.startsWith(rootPath.path + File.separator)) {
            throw GameModException(GameModError.INVALID_MANIFEST)
        }
        return target
    }

    private fun writeState(path: File, state: JSONObject) {
        path.parentFile?.mkdirs()
        val tmp = File(path.parentFile, "${path.name}.tmp")
        tmp.writeText(state.toString(2) + "\n")
        if (!tmp.renameTo(path)) {
            tmp.copyTo(path, overwrite = true)
            tmp.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}
