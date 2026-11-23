package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.disk.windows.UnattendXmlBuilder
import io.github.drlacheheb.kufus.domain.repository.HostUserEnvironment
import java.util.Locale

class JvmHostUserEnvironment : HostUserEnvironment {
    override val defaultUsername: String?
        get() = UnattendXmlBuilder.sanitizeLocalUsername(System.getProperty("user.name"))

    override val defaultLocaleTag: String?
        get() =
            Locale
                .getDefault()
                .toLanguageTag()
                .takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
                ?.let(UnattendXmlBuilder::sanitizeLocaleTag)
}
