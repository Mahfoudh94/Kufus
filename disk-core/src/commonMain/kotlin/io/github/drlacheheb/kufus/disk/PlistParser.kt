package io.github.drlacheheb.kufus.disk

internal sealed interface PlistValue {
    data class Str(
        val value: String,
    ) : PlistValue

    data class IntVal(
        val value: Long,
    ) : PlistValue

    data class BoolVal(
        val value: Boolean,
    ) : PlistValue

    data class Arr(
        val items: List<PlistValue>,
    ) : PlistValue

    data class Dict(
        val entries: Map<String, PlistValue>,
    ) : PlistValue
}

internal fun PlistValue.Dict.string(key: String): String? = (entries[key] as? PlistValue.Str)?.value

internal fun PlistValue.Dict.long(key: String): Long? = (entries[key] as? PlistValue.IntVal)?.value

internal fun PlistValue.Dict.bool(key: String): Boolean? = (entries[key] as? PlistValue.BoolVal)?.value

internal fun PlistValue.Dict.array(key: String): List<PlistValue> = (entries[key] as? PlistValue.Arr)?.items.orEmpty()

internal fun parsePlistRootDict(xml: String): PlistValue.Dict? {
    val reader = PlistXmlReader(xml)
    var tag = reader.nextTag()
    while (tag != null) {
        if (tag == "dict") return reader.readDict()
        tag = reader.nextTag()
    }
    return null
}

private class PlistXmlReader(
    private val xml: String,
) {
    private var cursor = 0

    fun nextTag(): String? {
        var found: String? = null
        while (found == null && cursor < xml.length) {
            found = readNextRelevantTag()
        }
        return found
    }

    fun readDict(): PlistValue.Dict {
        val entries = linkedMapOf<String, PlistValue>()
        var tag = nextTag()
        while (tag != null && tag != "/dict") {
            if (tag == "key") {
                val key = unescapeXml(readTextUntil("</key>"))
                val valueTag = nextTag()
                if (valueTag != null) {
                    entries[key] = readValue(valueTag)
                }
            }
            tag = nextTag()
        }
        return PlistValue.Dict(entries)
    }

    fun readArray(): PlistValue.Arr {
        val items = mutableListOf<PlistValue>()
        var tag = nextTag()
        while (tag != null && tag != "/array") {
            items += readValue(tag)
            tag = nextTag()
        }
        return PlistValue.Arr(items)
    }

    private fun readNextRelevantTag(): String? {
        val open = xml.indexOf('<', cursor)
        if (open < 0) {
            cursor = xml.length
            return null
        }
        if (xml.startsWith("<!--", open)) {
            val commentEnd = xml.indexOf("-->", open + 4)
            cursor = if (commentEnd < 0) xml.length else commentEnd + 3
            return null
        }
        val close = xml.indexOf('>', open + 1)
        if (close < 0) {
            cursor = xml.length
            return null
        }
        val rawTag = xml.substring(open + 1, close).trim()
        cursor = close + 1
        if (rawTag.startsWith("?") || rawTag.startsWith("!")) return null
        val selfClosing = rawTag.endsWith("/")
        val tagName = rawTag.removeSuffix("/").trim().substringBefore(' ')
        return if (selfClosing) "$tagName/" else tagName
    }

    private fun readValue(tag: String): PlistValue =
        when (tag) {
            "dict" -> readDict()
            "dict/" -> PlistValue.Dict(emptyMap())
            "array" -> readArray()
            "array/" -> PlistValue.Arr(emptyList())
            "string" -> PlistValue.Str(unescapeXml(readTextUntil("</string>")))
            "string/" -> PlistValue.Str("")
            "integer" -> PlistValue.IntVal(readTextUntil("</integer>").trim().toLongOrNull() ?: 0L)
            "true/" -> PlistValue.BoolVal(true)
            "false/" -> PlistValue.BoolVal(false)
            else -> skipUnknownValue(tag)
        }

    private fun skipUnknownValue(tag: String): PlistValue {
        if (!tag.endsWith("/") && !tag.startsWith("/")) {
            readTextUntil("</$tag>")
        }
        return PlistValue.Str("")
    }

    private fun readTextUntil(closingTag: String): String {
        val end = xml.indexOf(closingTag, cursor)
        if (end < 0) {
            val rest = xml.substring(cursor)
            cursor = xml.length
            return rest
        }
        val text = xml.substring(cursor, end)
        cursor = end + closingTag.length
        return text
    }

    private fun unescapeXml(raw: String): String =
        raw
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
}
