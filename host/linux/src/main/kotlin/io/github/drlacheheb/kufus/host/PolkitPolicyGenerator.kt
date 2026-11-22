package io.github.drlacheheb.kufus.host

const val POLKIT_WORKER_ACTION_ID = "io.github.drlacheheb.kufus.worker"
const val PACKAGED_LINUX_WORKER_PATH = "/opt/kufus/lib/app/resources/kufus-worker"

/**
 * Renders the freedesktop PolicyKit XML action definition matching [workerExecutablePath].
 */
object PolkitPolicyGenerator {
    fun renderPolicyXml(workerExecutablePath: String = PACKAGED_LINUX_WORKER_PATH): String {
        require(workerExecutablePath.isNotBlank()) { "Worker executable path must not be blank" }
        require('\n' !in workerExecutablePath && '\r' !in workerExecutablePath) {
            "Worker executable path must not contain line breaks"
        }
        val escapedPath = escapeXmlText(workerExecutablePath.trim())
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<!DOCTYPE policyconfig PUBLIC""")
            appendLine(""" "-//freedesktop//DTD PolicyKit Policy Configuration 1.0//EN"""")
            appendLine(""" "http://www.freedesktop.org/standards/PolicyKit/1/policyconfig.dtd">""")
            appendLine("<policyconfig>")
            appendLine("  <vendor>Kufus</vendor>")
            appendLine("  <vendor_url>https://github.com/drlacheheb/kufus</vendor_url>")
            appendLine("  <icon_name>drive-removable-media</icon_name>")
            appendLine("  <action id=\"$POLKIT_WORKER_ACTION_ID\">")
            appendLine("    <description>Run privileged Kufus disk operations</description>")
            appendLine("    <message>Authentication is required by Kufus to write or eject the selected USB drive.</message>")
            appendLine("    <defaults>")
            appendLine("      <allow_any>auth_admin</allow_any>")
            appendLine("      <allow_inactive>auth_admin</allow_inactive>")
            appendLine("      <allow_active>auth_admin</allow_active>")
            appendLine("    </defaults>")
            appendLine("    <annotate key=\"org.freedesktop.policykit.exec.path\">$escapedPath</annotate>")
            appendLine("  </action>")
            append("</policyconfig>")
            append('\n')
        }
    }
}

private fun escapeXmlText(value: String): String =
    buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }
