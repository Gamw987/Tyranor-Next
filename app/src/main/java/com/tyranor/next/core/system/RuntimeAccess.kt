package com.tyranor.next.core.system

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/**
 * 结束外置运行环境时使用的特权通道。
 *
 * Shizuku 是首选：它由用户安装并启动后，通过 ADB 或 Root 提供系统级权限，
 * 不要求 Tyranor 自己持有 Root。Root 通道直接走 `su`，作为本机已有 Root
 * 时的兼容选项。自定义命令允许用户把结束操作交给自己的包装脚本。
 * 所有通道都不可用或执行失败时，只请求系统清理后台进程。
 */
object RuntimeAccess {
    enum class Authorizer(val storage: String) {
        SHIZUKU("shizuku"),
        ROOT("root"),
        CUSTOM("custom"),
        NONE("none"),
    }

    enum class RootMode {
        NONE,
        MAGISK,
        KERNELSU,
        APATCH,
        OTHER,
    }

    enum class ShizukuMode {
        NONE,
        ADB,
        ROOT,
    }

    data class Status(
        val shizukuAvailable: Boolean = false,
        val shizukuAuthorized: Boolean = false,
        val shizukuUid: Int? = null,
        val rootReady: Boolean? = null,
        val rootMode: RootMode = RootMode.NONE,
        val rootDetail: String = "",
        val customCommand: String = "",
    ) {
        val shizukuMode: ShizukuMode
            get() = when {
                !shizukuAvailable || !shizukuAuthorized -> ShizukuMode.NONE
                shizukuUid == 0 -> ShizukuMode.ROOT
                else -> ShizukuMode.ADB
            }
    }

    data class RootProbe(
        val ready: Boolean,
        val mode: RootMode,
        val detail: String,
    )

    data class KillResult(
        val authorizer: Authorizer,
        val usedShizuku: Boolean,
        val usedRoot: Boolean,
        val usedCustom: Boolean,
        val requested: List<String>,
        val failed: List<String>,
    )

    private const val PREFS = "runtime_access"
    private const val KEY_AUTHORIZER = "privilege"
    private const val KEY_CUSTOM_COMMAND = "custom_authorizer"
    private const val CUSTOM_COMMAND_MAX = 512

    /** Shizuku 13.x 仍保留 newProcess，但已标记废弃；反射调用以便在旧版本上兼容。 */
    private val newProcessMethod: Method? by lazy {
        runCatching {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
        }.getOrNull()
    }

    fun getAuthorizer(context: Context): Authorizer {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_AUTHORIZER, Authorizer.SHIZUKU.storage)
        return Authorizer.entries.firstOrNull { it.storage == value } ?: Authorizer.SHIZUKU
    }

    fun setAuthorizer(context: Context, authorizer: Authorizer) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AUTHORIZER, authorizer.storage)
            .apply()
    }

    fun getCustomCommand(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_COMMAND, "")
            .orEmpty()
            .trim()

    fun setCustomCommand(context: Context, command: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_COMMAND, command.trim().take(CUSTOM_COMMAND_MAX))
            .apply()
    }

    /** 读取状态；默认不触发 Root 授权，避免仅打开页面就弹窗。 */
    fun probe(context: Context, includeRoot: Boolean = false): Status {
        val shizuku = runCatching {
            val available = Shizuku.pingBinder()
            val authorized = available &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            val uid = if (available && authorized) runCatching { Shizuku.getUid() }.getOrNull() else null
            Triple(available, authorized, uid)
        }.getOrDefault(Triple(false, false, null))

        val root = if (includeRoot) probeRoot() else null
        return Status(
            shizukuAvailable = shizuku.first,
            shizukuAuthorized = shizuku.second,
            shizukuUid = shizuku.third,
            rootReady = root?.ready,
            rootMode = root?.mode ?: RootMode.NONE,
            rootDetail = root?.detail.orEmpty(),
            customCommand = getCustomCommand(context),
        )
    }

    fun probeRoot(): RootProbe {
        val result = runShell(
            "id; command -v ksud; command -v magisk; command -v apd",
            timeoutSeconds = 20,
            viaRoot = true,
        )
        val output = result.output.trim()
        val ready = result.exitCode == 0 && output.contains("uid=0")
        val mode = when {
            !ready -> RootMode.NONE
            output.contains("ksud") -> RootMode.KERNELSU
            output.contains("magisk") -> RootMode.MAGISK
            output.contains("apd") -> RootMode.APATCH
            else -> RootMode.OTHER
        }
        return RootProbe(
            ready = ready,
            mode = mode,
            detail = output.ifBlank { "exit=${result.exitCode}" },
        )
    }

    /**
     * 按「可用特权」页的选择结束指定包：
     * - Shizuku：`am force-stop`，仅授权后使用；
     * - Root：`su -c am force-stop`；
     * - 自定义：执行用户提供的命令模板；
     * - 都不可用或执行失败时请求系统清理后台进程，不能证明前台应用已结束。
     */
    fun forceStop(context: Context, packageNames: Collection<String>): KillResult {
        val authorizer = getAuthorizer(context)
        val packages = packageNames.filter(RuntimeCommandTemplate::isValidPackageName).distinct()
        if (packages.isEmpty()) {
            return KillResult(authorizer, false, false, false, emptyList(), emptyList())
        }
        val command = packages.joinToString("; ") { "am force-stop $it" }

        when (authorizer) {
            Authorizer.SHIZUKU -> {
                if (isShizukuAuthorized()) {
                    val result = runShizuku(command, timeoutSeconds = 15)
                    if (result.exitCode == 0) {
                        return KillResult(authorizer, true, false, false, packages, emptyList())
                    }
                }
            }

            Authorizer.ROOT -> {
                val result = runShell(command, timeoutSeconds = 15, viaRoot = true)
                if (result.exitCode == 0) {
                    return KillResult(authorizer, false, true, false, packages, emptyList())
                }
            }

            Authorizer.CUSTOM -> {
                val custom = getCustomCommand(context)
                if (custom.isNotEmpty()) {
                    val result = runShell(buildCustomCommand(custom, command), timeoutSeconds = 15)
                    if (result.exitCode == 0) {
                        return KillResult(authorizer, false, false, true, packages, emptyList())
                    }
                }
            }

            Authorizer.NONE -> Unit
        }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return KillResult(authorizer, false, false, false, packages, packages)
        val failed = ArrayList<String>()
        for (pkg in packages) {
            try {
                activityManager.killBackgroundProcesses(pkg)
            } catch (_: SecurityException) {
                failed += pkg
            } catch (_: Throwable) {
                failed += pkg
            }
        }
        return KillResult(authorizer, false, false, false, packages, failed)
    }

    fun isShizukuAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isShizukuAuthorized(): Boolean = runCatching {
        isShizukuAvailable() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestShizukuPermission(requestCode: Int) {
        if (isShizukuAvailable()) Shizuku.requestPermission(requestCode)
    }

    internal fun buildCustomCommand(template: String, command: String): String =
        RuntimeCommandTemplate.build(template, command)

    private data class ShellResult(val exitCode: Int, val output: String)

    private fun runShizuku(command: String, timeoutSeconds: Long): ShellResult {
        val method = newProcessMethod
            ?: return ShellResult(-1, "Shizuku newProcess unavailable")
        return try {
            val process = method.invoke(
                null,
                arrayOf("/system/bin/sh", "-c", command),
                null,
                null,
            ) as? Process ?: return ShellResult(-1, "Shizuku process unavailable")
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroy()
                ShellResult(-1, "timeout")
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() } +
                    process.errorStream.bufferedReader().use { it.readText() }
                ShellResult(process.exitValue(), output)
            }
        } catch (error: Throwable) {
            ShellResult(-1, error.message.orEmpty())
        }
    }

    private fun runShell(
        command: String,
        timeoutSeconds: Long,
        viaRoot: Boolean = false,
    ): ShellResult {
        val fullCommand = if (viaRoot) "su -c ${shellQuote(command)}" else command
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", fullCommand)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                ShellResult(-1, "timeout")
            } else {
                ShellResult(process.exitValue(), process.inputStream.bufferedReader().use { it.readText() })
            }
        } catch (error: Throwable) {
            ShellResult(-1, error.message.orEmpty())
        }
    }

    private fun shellQuote(value: String): String = RuntimeCommandTemplate.shellQuote(value)
}
