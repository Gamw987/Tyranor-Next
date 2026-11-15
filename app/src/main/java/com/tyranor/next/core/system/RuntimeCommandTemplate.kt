package com.tyranor.next.core.system

/** 自定义特权命令的纯字符串构造与包名校验，便于 JVM 单测。 */
object RuntimeCommandTemplate {
    private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")

    fun isValidPackageName(value: String): Boolean = PACKAGE_NAME.matches(value)

    fun build(template: String, command: String): String {
        val trimmed = template.trim()
        val quoted = shellQuote(command)
        return if (trimmed.contains("%s")) {
            trimmed.replace("%s", quoted)
        } else {
            "$trimmed $quoted"
        }
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
