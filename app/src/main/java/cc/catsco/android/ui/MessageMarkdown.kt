package cc.catsco.android.ui

/** Compatibility for Chinese bold punctuation that violates CommonMark delimiter rules. */
internal fun normalizeMessageMarkdown(text: String): String {
    val code = Regex("(`{3,}|~{3,})[\\s\\S]*?(?:\\1|\\z)|(`+)[\\s\\S]*?\\2")
    val spacedBold = Regex("(?<!\\\\)\\*\\*[\\p{Z}\\t]*((?:(?!\\r?\\n[\\p{Z}\\t]*\\r?\\n)[^*])+?)\\*\\*")
    fun normalizePlain(value: String) = spacedBold.replace(value) { match ->
        val content = match.groupValues[1]
        if (content.any { it in '\u3400'..'\u9fff' && it.isLetter() }) {
            val trimmed = content.trim()
            val first = trimmed.indexOfFirst(Char::isLetterOrDigit)
            val last = trimmed.indexOfLast(Char::isLetterOrDigit)
            trimmed.take(first) + "**" + trimmed.substring(first, last + 1) + "**" + trimmed.substring(last + 1)
        } else match.value
    }
    return buildString {
        var start = 0
        code.findAll(text).forEach { match ->
            append(normalizePlain(text.substring(start, match.range.first)))
            append(match.value)
            start = match.range.last + 1
        }
        append(normalizePlain(text.substring(start)))
    }
}
