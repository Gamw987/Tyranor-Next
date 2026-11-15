package com.tyranor.next.core.system

import android.app.ActivityManager
import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * 应用外运行环境的尽力探测。
 *
 * - shell 是 Android 自带 `/system/bin/sh`，不依赖 root；
 * - root 通过 `su -c id` 由用户在 Magisk/KernelSU 等管理器中授权；
 * - 结束外部应用优先使用 root 的 `am force-stop`，未授权时退回
 *   [ActivityManager.killBackgroundProcesses]（只能结束后台进程，不能保证成功）。
 *
 * 这里不缓存授权结果：root 授权可能被系统撤销，且首次调用需要用户确认。
 */
object RuntimeAccess {
    data class Status(
        val shellReady: Boolean,
        val rootReady: Boolean,
        val shellDetail: String,
        val rootDetail: String,
    )

    data class KillResult(
        val usedRoot: Boolean,
        val requested: List<String>,
        val failed: List<String>,
    )

    private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")

    fun probe(includeRoot: Boolean = true): Status {
        val shell = runShell("id", timeoutSeconds = 5)
        val root = if (includeRoot) runShell("su -c id", timeoutSeconds = 20) else null
        return Status(
            shellReady = shell.exitCode == 0,
            rootReady = root?.exitCode == 0 && root.output.contains("uid=0"),
            shellDetail = shell.output.trim().ifBlank { "exit=${shell.exitCode}" },
            rootDetail = root?.output?.trim()?.ifBlank { "exit=${root.exitCode}" } ?: "not checked",
        )
    }

    /**
     * 尽力结束指定包。root 可用时逐包执行 `am force-stop`；否则只调用
     * `killBackgroundProcesses`，后者不会结束前台服务，返回值也不是“已退出”证明。
     */
    fun forceStop(context: Context, packageNames: Collection<String>): KillResult {
        val packages = packageNames.filter { PACKAGE_NAME.matches(it) }.distinct()
        if (packages.isEmpty()) return KillResult(false, emptyList(), emptyList())
        val root = runShell(
            packages.joinToString("; ") { "am force-stop $it" },
            timeoutSeconds = 15,
            viaRoot = true,
        )
        if (root.exitCode == 0) return KillResult(true, packages, emptyList())

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return KillResult(false, packages, packages)
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
        return KillResult(false, packages, failed)
    }

    private data class ShellResult(val exitCode: Int, val output: String)

    private fun runShell(command: String, timeoutSeconds: Long, viaRoot: Boolean = false): ShellResult {
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

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
