package com.tyranor.next.core.game.mods

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * 从 SAF 选择的 ZIP 或文件夹导入 Ren'Py 模组。
 *
 * 导入只负责把插件复制到 `GameMods/plugins/<id>`，不会安装到任何游戏；
 * 同一个 ZIP/文件夹里的多个合法插件会按顺序分别导入。重复 ID 跳过，
 * 单个来源失败不会中断同批次的其它来源。
 */
class GameModImporter(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    data class Failure(
        val source: String,
        val error: GameModError,
    )

    data class Summary(
        val imported: List<String> = emptyList(),
        val skipped: List<String> = emptyList(),
        val failed: List<Failure> = emptyList(),
    ) {
        val importedCount: Int get() = imported.size
        val skippedCount: Int get() = skipped.size
        val failedCount: Int get() = failed.size
    }

    /** 导入一个或多个 ZIP；每个 ZIP 内允许一个插件根目录，也允许顶层直接是插件。 */
    fun importArchives(uris: List<Uri>): Summary {
        val imported = ArrayList<String>()
        val skipped = ArrayList<String>()
        val failed = ArrayList<Failure>()
        for (uri in uris.distinct().take(MAX_SOURCES)) {
            val source = displayName(uri)
            try {
                importArchive(uri, imported, skipped, failed, source)
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (error: GameModException) {
                failed += Failure(source, error.error)
            } catch (_: Throwable) {
                failed += Failure(source, GameModError.INVALID_ARCHIVE)
            }
        }
        if (uris.size > MAX_SOURCES) {
            failed += Failure("ZIP", GameModError.TOO_MANY)
        }
        return Summary(imported, skipped, failed)
    }

    /**
     * 递归扫描文件夹中的 `.zip` 和包含 `plugin.json` 的插件目录。
     * 文件夹本身也可以是单个插件目录。
     */
    fun importTree(treeUri: Uri): Summary {
        val root = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: return Summary(failed = listOf(Failure(displayName(treeUri), GameModError.INVALID_ARCHIVE)))
        val archives = ArrayList<DocumentFile>()
        val directories = ArrayList<DocumentFile>()
        var tooMany = false
        fun scan(file: DocumentFile, depth: Int) {
            if (tooMany || depth > MAX_DEPTH) return
            if (file.isDirectory) {
                if (file.findFile("plugin.json")?.isFile == true) {
                    directories += file
                    if (archives.size + directories.size > MAX_CANDIDATES) tooMany = true
                    return
                }
                file.listFiles().orEmpty().forEach { child ->
                    val name = child.name.orEmpty()
                    when {
                        child.isDirectory -> scan(child, depth + 1)
                        name.endsWith(".zip", ignoreCase = true) -> {
                            archives += child
                            if (archives.size + directories.size > MAX_CANDIDATES) tooMany = true
                        }
                    }
                }
            }
        }
        scan(root, 0)
        if (tooMany) {
            return Summary(failed = listOf(Failure(root.name.orEmpty().ifBlank { "folder" }, GameModError.TOO_MANY)))
        }
        if (archives.isEmpty() && directories.isEmpty()) {
            return Summary(failed = listOf(Failure(root.name.orEmpty().ifBlank { "folder" }, GameModError.INVALID_ARCHIVE)))
        }

        val imported = ArrayList<String>()
        val skipped = ArrayList<String>()
        val failed = ArrayList<Failure>()
        for (archive in archives) {
            val source = archive.name.orEmpty().ifBlank { "archive.zip" }
            try {
                importArchive(archive.uri, imported, skipped, failed, source)
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (error: GameModException) {
                failed += Failure(source, error.error)
            } catch (_: Throwable) {
                failed += Failure(source, GameModError.INVALID_ARCHIVE)
            }
        }
        for (directory in directories) {
            val source = directory.name.orEmpty().ifBlank { "plugin" }
            try {
                importDocumentPlugin(directory, imported, skipped, failed, source)
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (error: GameModException) {
                failed += Failure(source, error.error)
            } catch (_: Throwable) {
                failed += Failure(source, GameModError.INVALID_MANIFEST)
            }
        }
        return Summary(imported, skipped, failed)
    }

    /** 导入本地文件系统中的多个 ZIP。供内嵌文件选择器使用。 */
    fun importArchiveFiles(files: List<File>): Summary {
        val imported = ArrayList<String>()
        val skipped = ArrayList<String>()
        val failed = ArrayList<Failure>()
        val unique = files
            .mapNotNull { file -> runCatching { file.canonicalFile }.getOrNull() }
            .distinctBy { it.path }
            .take(MAX_SOURCES)
        for (file in unique) {
            val source = file.name.ifBlank { "archive.zip" }
            try {
                importArchiveFile(file, imported, skipped, failed, source)
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (error: GameModException) {
                failed += Failure(source, error.error)
            } catch (_: Throwable) {
                failed += Failure(source, GameModError.INVALID_ARCHIVE)
            }
        }
        if (files.size > MAX_SOURCES) failed += Failure("ZIP", GameModError.TOO_MANY)
        return Summary(imported, skipped, failed)
    }

    /**
     * 递归扫描本地文件夹中的 `.zip` 和包含 `plugin.json` 的模组目录。
     * 文件夹本身也可以是单个模组目录。供内嵌文件选择器使用。
     */
    fun importLocalFolder(folder: File): Summary {
        val root = runCatching { folder.canonicalFile }.getOrNull()
        if (root == null || !root.isDirectory) {
            return Summary(failed = listOf(Failure(folder.name.ifBlank { "folder" }, GameModError.INVALID_ARCHIVE)))
        }
        val archives = ArrayList<File>()
        val directories = ArrayList<File>()
        var tooMany = false

        fun scan(dir: File, depth: Int) {
            if (tooMany || depth > MAX_DEPTH) return
            if (File(dir, "plugin.json").isFile) {
                directories += dir
                if (archives.size + directories.size > MAX_CANDIDATES) tooMany = true
                return
            }
            dir.listFiles().orEmpty()
                .sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
                .forEach { child ->
                    if (Files.isSymbolicLink(child.toPath())) return@forEach
                    when {
                        child.isDirectory -> scan(child, depth + 1)
                        child.isFile && child.name.endsWith(".zip", ignoreCase = true) -> {
                            archives += child
                            if (archives.size + directories.size > MAX_CANDIDATES) tooMany = true
                        }
                    }
                }
        }

        scan(root, 0)
        if (tooMany) {
            return Summary(failed = listOf(Failure(root.name.ifBlank { "folder" }, GameModError.TOO_MANY)))
        }
        if (archives.isEmpty() && directories.isEmpty()) {
            return Summary(failed = listOf(Failure(root.name.ifBlank { "folder" }, GameModError.INVALID_ARCHIVE)))
        }

        val imported = ArrayList<String>()
        val skipped = ArrayList<String>()
        val failed = ArrayList<Failure>()
        for (archive in archives) {
            val source = archive.name.ifBlank { "archive.zip" }
            try {
                importArchiveFile(archive, imported, skipped, failed, source)
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (error: GameModException) {
                failed += Failure(source, error.error)
            } catch (_: Throwable) {
                failed += Failure(source, GameModError.INVALID_ARCHIVE)
            }
        }
        for (directory in directories) {
            val source = directory.name.ifBlank { "plugin" }
            try {
                importLocalPluginDirectory(directory, imported, skipped, failed, source)
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (error: GameModException) {
                failed += Failure(source, error.error)
            } catch (_: Throwable) {
                failed += Failure(source, GameModError.INVALID_MANIFEST)
            }
        }
        return Summary(imported, skipped, failed)
    }

    private fun importArchive(
        uri: Uri,
        imported: MutableList<String>,
        skipped: MutableList<String>,
        failed: MutableList<Failure>,
        source: String,
    ) {
        val input = resolver.openInputStream(uri)
            ?: throw GameModException(GameModError.INVALID_ARCHIVE)
        importArchiveStream(input, imported, skipped, failed, source)
    }

    private fun importArchiveFile(
        file: File,
        imported: MutableList<String>,
        skipped: MutableList<String>,
        failed: MutableList<Failure>,
        source: String,
    ) {
        if (!file.isFile) throw GameModException(GameModError.INVALID_ARCHIVE)
        importArchiveStream(file.inputStream().buffered(), imported, skipped, failed, source)
    }

    private fun importArchiveStream(
        input: InputStream,
        imported: MutableList<String>,
        skipped: MutableList<String>,
        failed: MutableList<Failure>,
        source: String,
    ) {
        val staging = createStaging()
        try {
            input.use { extractZip(it, staging) }
            val roots = findPluginRoots(staging)
            if (roots.isEmpty()) throw GameModException(GameModError.INVALID_ARCHIVE)
            for (root in roots) {
                importLocalPlugin(root, imported, skipped, failed, source)
            }
        } catch (error: ZipException) {
            throw GameModException(GameModError.INVALID_ARCHIVE, error)
        } finally {
            deleteTree(staging)
        }
    }

    private fun importDocumentPlugin(
        directory: DocumentFile,
        imported: MutableList<String>,
        skipped: MutableList<String>,
        failed: MutableList<Failure>,
        source: String,
    ) {
        val staging = createStaging()
        try {
            val budget = CopyBudget()
            val name = safeFileName(directory.name)
            copyDirectory(directory, staging, budget, 0)
            importLocalPlugin(File(staging, name), imported, skipped, failed, source)
        } finally {
            deleteTree(staging)
        }
    }

    private fun importLocalPluginDirectory(
        directory: File,
        imported: MutableList<String>,
        skipped: MutableList<String>,
        failed: MutableList<Failure>,
        source: String,
    ) {
        val staging = createStaging()
        try {
            val budget = CopyBudget()
            copyLocalDirectory(directory, staging, budget, 0)
            importLocalPlugin(File(staging, safeFileName(directory.name)), imported, skipped, failed, source)
        } finally {
            deleteTree(staging)
        }
    }

    private fun copyLocalDirectory(source: File, destination: File, budget: CopyBudget, depth: Int) {
        if (depth > MAX_DEPTH) throw GameModException(GameModError.TOO_MANY)
        if (Files.isSymbolicLink(source.toPath())) throw GameModException(GameModError.INVALID_ARCHIVE)
        val target = File(destination, safeFileName(source.name))
        if (!target.mkdirs() && !target.isDirectory) throw GameModException(GameModError.IO)
        source.listFiles().orEmpty().forEach { child ->
            val childName = safeFileName(child.name)
            if (Files.isSymbolicLink(child.toPath())) throw GameModException(GameModError.INVALID_ARCHIVE)
            if (child.isDirectory) {
                copyLocalDirectory(child, target, budget, depth + 1)
            } else {
                budget.files++
                if (budget.files > MAX_COPIED_FILES) throw GameModException(GameModError.TOO_MANY)
                child.inputStream().buffered().use { sourceStream ->
                    File(target, childName).outputStream().buffered().use { sink ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = sourceStream.read(buffer)
                            if (read <= 0) break
                            budget.bytes += read
                            if (budget.bytes > MAX_UNCOMPRESSED_BYTES) {
                                throw GameModException(GameModError.TOO_MANY)
                            }
                            sink.write(buffer, 0, read)
                        }
                    }
                }
            }
        }
    }

    private fun importLocalPlugin(
        root: File,
        imported: MutableList<String>,
        skipped: MutableList<String>,
        failed: MutableList<Failure>,
        source: String,
    ) {
        try {
            val id = GameModManager.commitImportedPlugin(root)
            imported += id
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (error: GameModException) {
            if (error.error == GameModError.PLUGIN_EXISTS) skipped += source
            else failed += Failure(source, error.error)
        } catch (_: Throwable) {
            failed += Failure(source, GameModError.IO)
        }
    }

    private fun extractZip(input: InputStream, destination: File) {
        val seen = HashSet<String>()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var entries = 0
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ZIP_ENTRIES) throw GameModException(GameModError.TOO_MANY)
                val normalized = entry.name.replace('\\', '/').trimStart('/')
                if (normalized.isBlank() || entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val relative = normalized.split('/').filter { it.isNotBlank() }
                if (relative.any { it == "." || it == ".." } || !seen.add(normalized)) {
                    throw GameModException(GameModError.INVALID_ARCHIVE)
                }
                val output = safeChild(destination, relative.joinToString(File.separator))
                output.parentFile?.mkdirs()
                output.outputStream().buffered().use { sink ->
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_UNCOMPRESSED_BYTES) throw GameModException(GameModError.TOO_MANY)
                        sink.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun findPluginRoots(staging: File): List<File> {
        if (File(staging, "plugin.json").isFile) return listOf(staging)
        val result = ArrayList<File>()
        fun scan(dir: File, depth: Int) {
            if (depth > 2 || result.size >= MAX_CANDIDATES) return
            dir.listFiles().orEmpty().filter { it.isDirectory }.forEach { child ->
                if (File(child, "plugin.json").isFile) result += child
                else scan(child, depth + 1)
            }
        }
        scan(staging, 0)
        return result
    }

    private fun copyDirectory(source: DocumentFile, destination: File, budget: CopyBudget, depth: Int) {
        if (depth > MAX_DEPTH) throw GameModException(GameModError.TOO_MANY)
        val name = safeFileName(source.name)
        val target = File(destination, name)
        if (!target.mkdirs() && !target.isDirectory) throw GameModException(GameModError.IO)
        source.listFiles().orEmpty().forEach { child ->
            val childName = safeFileName(child.name)
            if (child.isDirectory) {
                copyDirectory(child, target, budget, depth + 1)
            } else {
                budget.files++
                if (budget.files > MAX_COPIED_FILES) throw GameModException(GameModError.TOO_MANY)
                val input = resolver.openInputStream(child.uri) ?: throw GameModException(GameModError.INVALID_ARCHIVE)
                input.use { sourceStream ->
                    File(target, childName).outputStream().buffered().use { sink ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = sourceStream.read(buffer)
                            if (read <= 0) break
                            budget.bytes += read
                            if (budget.bytes > MAX_UNCOMPRESSED_BYTES) {
                                throw GameModException(GameModError.TOO_MANY)
                            }
                            sink.write(buffer, 0, read)
                        }
                    }
                }
            }
        }
    }

    private fun createStaging(): File {
        val root = GameModManager.pluginsRoot()
        if (!root.isDirectory && !root.mkdirs()) throw GameModException(GameModError.IO)
        val staging = File(root, ".import-${UUID.randomUUID()}")
        if (!staging.mkdirs()) throw GameModException(GameModError.IO)
        return staging
    }

    private fun safeChild(root: File, relative: String): File {
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, relative).canonicalFile
        if (target != canonicalRoot && !target.path.startsWith(canonicalRoot.path + File.separator)) {
            throw GameModException(GameModError.INVALID_ARCHIVE)
        }
        return target
    }

    private fun safeFileName(name: String?): String {
        val value = name?.trim().orEmpty()
        if (value.isBlank() || value == "." || value == ".." ||
            '/' in value || '\\' in value || '\u0000' in value
        ) {
            throw GameModException(GameModError.INVALID_ARCHIVE)
        }
        return value
    }

    private fun displayName(uri: Uri): String =
        runCatching { DocumentFile.fromSingleUri(appContext, uri)?.name }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "zip"

    private fun deleteTree(file: File) {
        runCatching { file.deleteRecursively() }
    }

    private class CopyBudget {
        var files = 0
        var bytes = 0L
    }

    companion object {
        private const val MAX_SOURCES = 64
        private const val MAX_CANDIDATES = 256
        private const val MAX_DEPTH = 4
        private const val MAX_ZIP_ENTRIES = 4096
        private const val MAX_COPIED_FILES = 4096
        private const val MAX_UNCOMPRESSED_BYTES = 256L * 1024 * 1024
    }
}
