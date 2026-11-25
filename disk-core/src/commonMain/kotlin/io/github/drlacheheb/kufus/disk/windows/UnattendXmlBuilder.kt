package io.github.drlacheheb.kufus.disk.windows

import io.github.drlacheheb.kufus.disk.model.WindowsUserExperienceOptions

private const val MAX_WINDOWS_USERNAME_LEN = 20
private val disallowedWindowsUserChars = setOf('"', '/', '\\', '[', ']', ':', ';', '|', '=', ',', '+', '*', '?', '<', '>', '@')
private const val UNATTEND_PUBLIC_KEY_TOKEN = "31bf3856ad364e35"

/**
 * Generates Windows Setup `autounattend.xml` answer files matching Windows User Experience options.
 */
object UnattendXmlBuilder {
    fun shouldGenerate(options: WindowsUserExperienceOptions): Boolean =
        options.bypassSystemRequirements ||
            options.bypassMicrosoftAccount ||
            options.disableDataCollection ||
            !sanitizeLocalUsername(options.localAccountUsername).isNullOrEmpty() ||
            !sanitizeLocaleTag(options.regionalLocaleTag).isNullOrEmpty()

    fun sanitizeLocalUsername(raw: String?): String? {
        if (raw == null) return null
        val filtered =
            raw
                .trim()
                .filter { ch -> ch.code in 0x20..0x7E && ch !in disallowedWindowsUserChars }
                .trimEnd('.')
                .take(MAX_WINDOWS_USERNAME_LEN)
                .trim()
        return filtered.ifEmpty { null }
    }

    fun sanitizeLocaleTag(raw: String?): String? {
        if (raw == null) return null
        val normalized = raw.trim().replace('_', '-')
        val valid = normalized.isNotEmpty() && normalized.length <= 24 && normalized.all { it.isLetterOrDigit() || it == '-' }
        return normalized.takeIf { valid }
    }

    fun buildXml(options: WindowsUserExperienceOptions): String {
        val username = sanitizeLocalUsername(options.localAccountUsername)
        val localeTag = sanitizeLocaleTag(options.regionalLocaleTag)

        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            appendLine("<unattend xmlns=\"urn:schemas-microsoft-com:unattend\">")
            appendWindowsPePass(this, options, localeTag)
            appendSpecializePass(this, options, localeTag)
            appendOobeSystemPass(this, options, username, localeTag)
            appendLine("</unattend>")
        }
    }

    private fun appendWindowsPePass(
        sb: StringBuilder,
        options: WindowsUserExperienceOptions,
        localeTag: String?,
    ) {
        if (!shouldGenerate(options)) return
        sb.appendLine("  <settings pass=\"windowsPE\">")
        if (localeTag != null) {
            appendComponentOpen(sb, "Microsoft-Windows-International-Core-WinPE")
            val escapedLocale = escapeXml(localeTag)
            sb.appendLine("      <SetupUILanguage><UILanguage>$escapedLocale</UILanguage></SetupUILanguage>")
            appendLocaleElements(sb, escapedLocale)
            sb.appendLine("    </component>")
        }
        appendComponentOpen(sb, "Microsoft-Windows-Setup")
        // Windows 10 2004+ / Windows 11 WinPE fails with "Windows cannot read the <ProductKey> setting"
        // unless Microsoft-Windows-Setup includes an explicit (even empty) <ProductKey><Key /></ProductKey>.
        sb.appendLine("      <UserData>")
        sb.appendLine("        <AcceptEula>true</AcceptEula>")
        sb.appendLine("        <ProductKey>")
        sb.appendLine("          <Key />")
        sb.appendLine("        </ProductKey>")
        sb.appendLine("      </UserData>")
        if (options.bypassSystemRequirements) {
            sb.appendLine("      <RunSynchronous>")
            val regKeys = listOf("BypassTPMCheck", "BypassSecureBootCheck", "BypassRAMCheck", "BypassCPUCheck", "BypassStorageCheck")
            regKeys.forEachIndexed { idx, keyName ->
                val order = idx + 1
                sb.appendLine("        <RunSynchronousCommand wcm:action=\"add\">")
                sb.appendLine("          <Order>$order</Order>")
                sb.appendLine("          <Path>reg add HKLM\\SYSTEM\\Setup\\LabConfig /v $keyName /t REG_DWORD /d 1 /f</Path>")
                sb.appendLine("        </RunSynchronousCommand>")
            }
            sb.appendLine("      </RunSynchronous>")
        }
        sb.appendLine("    </component>")
        sb.appendLine("  </settings>")
    }

    private fun appendSpecializePass(
        sb: StringBuilder,
        options: WindowsUserExperienceOptions,
        localeTag: String?,
    ) {
        if (!options.bypassMicrosoftAccount && localeTag == null) return
        sb.appendLine("  <settings pass=\"specialize\">")
        if (options.bypassMicrosoftAccount) {
            appendComponentOpen(sb, "Microsoft-Windows-Deployment")
            sb.appendLine("      <RunSynchronous>")
            sb.appendLine("        <RunSynchronousCommand wcm:action=\"add\">")
            sb.appendLine("          <Order>1</Order>")
            sb.appendLine("          <Path>reg add HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\OOBE /v BypassNRO /t REG_DWORD /d 1 /f</Path>")
            sb.appendLine("        </RunSynchronousCommand>")
            sb.appendLine("      </RunSynchronous>")
            sb.appendLine("    </component>")
        }
        if (localeTag != null) {
            appendComponentOpen(sb, "Microsoft-Windows-International-Core")
            appendLocaleElements(sb, escapeXml(localeTag))
            sb.appendLine("    </component>")
        }
        sb.appendLine("  </settings>")
    }

    private fun shouldEmitOobePass(
        options: WindowsUserExperienceOptions,
        username: String?,
        localeTag: String?,
    ): Boolean = options.bypassMicrosoftAccount || options.disableDataCollection || username != null || localeTag != null

    private fun appendOobeSystemPass(
        sb: StringBuilder,
        options: WindowsUserExperienceOptions,
        username: String?,
        localeTag: String?,
    ) {
        if (!shouldEmitOobePass(options, username, localeTag)) return
        sb.appendLine("  <settings pass=\"oobeSystem\">")
        if (localeTag != null) {
            appendComponentOpen(sb, "Microsoft-Windows-International-Core")
            appendLocaleElements(sb, escapeXml(localeTag))
            sb.appendLine("    </component>")
        }
        appendComponentOpen(sb, "Microsoft-Windows-Shell-Setup")
        sb.appendLine("      <OOBE>")
        sb.appendLine("        <HideEULAPage>true</HideEULAPage>")
        if (options.bypassMicrosoftAccount) {
            sb.appendLine("        <HideOnlineAccountScreens>true</HideOnlineAccountScreens>")
            sb.appendLine("        <HideWirelessSetupInOOBE>true</HideWirelessSetupInOOBE>")
        }
        if (options.disableDataCollection) {
            sb.appendLine("        <ProtectYourPC>3</ProtectYourPC>")
        }
        sb.appendLine("      </OOBE>")
        if (username != null) {
            val escapedUser = escapeXml(username)
            sb.appendLine("      <UserAccounts>")
            sb.appendLine("        <LocalAccounts>")
            sb.appendLine("          <LocalAccount wcm:action=\"add\">")
            sb.appendLine("            <Name>$escapedUser</Name>")
            sb.appendLine("            <DisplayName>$escapedUser</DisplayName>")
            sb.appendLine("            <Group>Administrators</Group>")
            sb.appendLine("            <Password><Value></Value><PlainText>true</PlainText></Password>")
            sb.appendLine("          </LocalAccount>")
            sb.appendLine("        </LocalAccounts>")
            sb.appendLine("      </UserAccounts>")
        }
        sb.appendLine("    </component>")
        sb.appendLine("  </settings>")
    }

    private fun appendComponentOpen(
        sb: StringBuilder,
        componentName: String,
    ) {
        sb.append("    <component name=\"")
        sb.append(componentName)
        sb.append("\" processorArchitecture=\"amd64\" publicKeyToken=\"")
        sb.append(UNATTEND_PUBLIC_KEY_TOKEN)
        sb.appendLine(
            "\" language=\"neutral\" versionScope=\"nonSxS\" " +
                "xmlns:wcm=\"http://schemas.microsoft.com/WMIConfig/2002/State\" " +
                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">",
        )
    }

    private fun appendLocaleElements(
        sb: StringBuilder,
        escapedLocale: String,
    ) {
        sb.appendLine("      <InputLocale>$escapedLocale</InputLocale>")
        sb.appendLine("      <SystemLocale>$escapedLocale</SystemLocale>")
        sb.appendLine("      <UILanguage>$escapedLocale</UILanguage>")
        sb.appendLine("      <UserLocale>$escapedLocale</UserLocale>")
    }

    internal fun escapeXml(input: String): String =
        buildString(input.length) {
            for (ch in input) {
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
}
