package com.agenthud.threads.ui

private val FENCE = Regex("```[a-zA-Z0-9_+-]*\\n?")
private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
private val BOLD_STAR = Regex("\\*\\*(.+?)\\*\\*")
private val BOLD_UNDER = Regex("(?<![\\w])__(.+?)__(?![\\w])")
private val ITALIC_STAR = Regex("(?<![\\w*])\\*(?!\\s)([^*\\n]+?)(?<!\\s)\\*(?![\\w*])")
private val ITALIC_UNDER = Regex("(?<![\\w])_(?!\\s)([^_\\n]+?)(?<!\\s)_(?![\\w])")
private val STRIKE = Regex("~~(.+?)~~")
private val CODE = Regex("`([^`\\n]+)`")
private val HEADING = Regex("(?m)^\\s{0,3}#{1,6}\\s+")
private val QUOTE = Regex("(?m)^\\s{0,3}>\\s?")
private val BULLET = Regex("(?m)^(\\s*)[-*+]\\s+")

/** Display-only: drops markdown markup so the watch shows words, not `**asterisks**` and backticks. */
fun plainText(raw: String): String {
    if (raw.isEmpty()) return raw
    var s = raw
    s = FENCE.replace(s, "")
    s = IMAGE.replace(s, "$1")
    s = LINK.replace(s, "$1")
    s = HEADING.replace(s, "")
    s = QUOTE.replace(s, "")
    s = BULLET.replace(s) { "${it.groupValues[1]}• " }
    s = CODE.replace(s, "$1")
    s = BOLD_STAR.replace(s, "$1")
    s = BOLD_UNDER.replace(s, "$1")
    s = STRIKE.replace(s, "$1")
    s = ITALIC_STAR.replace(s, "$1")
    s = ITALIC_UNDER.replace(s, "$1")
    return s.trim()
}
