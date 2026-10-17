package com.cia.texturepacker

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

/** Simple regex-based syntax highlighter for JSON / XML / plist / TXT. */
object CodeHighlighter {

    private val C_KEY = 0xFFCE9178.toInt()      // orange (JSON keys, plist keys)
    private val C_STRING = 0xFF9CDCFE.toInt()   // light blue (strings)
    private val C_NUMBER = 0xFFB5CEA8.toInt()   // green (numbers)
    private val C_BOOL = 0xFF569CD6.toInt()     // blue (true/false/null)
    private val C_TAG = 0xFF569CD6.toInt()      // blue (XML tags)
    private val C_ATTR = 0xFF9CDCFE.toInt()     // light blue (XML attrs)
    private val C_COMMENT = 0xFF6A9955.toInt()  // green (comments)
    private val C_TEXT = 0xFFD4D4D4.toInt()     // default

    fun highlight(code: String, format: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder(code)
        when (format.uppercase()) {
            "JSON" -> highlightJson(sb)
            "XML", "PLIST", "PLIST3" -> highlightXml(sb)
            else -> highlightTxt(sb)
        }
        return sb
    }

    private fun span(sb: SpannableStringBuilder, start: Int, end: Int, color: Int, bold: Boolean = false) {
        if (start < 0 || end > sb.length || start >= end) return
        sb.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun highlightJson(sb: SpannableStringBuilder) {
        val text = sb.toString()
        // Strings (keys and values): "..." — keys end with ":
        val strRe = Regex("\"(?:\\\\.|[^\"\\\\])*\"")
        val strRanges = mutableListOf<IntRange>()
        for (m in strRe.findAll(text)) {
            strRanges.add(m.range)
            val after = text.substring(m.range.last + 1).trimStart()
            val isKey = after.startsWith(":")
            span(sb, m.range.first, m.range.last + 1, if (isKey) C_KEY else C_STRING)
        }
        fun inString(pos: Int) = strRanges.any { pos in it }
        // Numbers (outside strings)
        for (m in Regex("(?<=[:\\s\\[,])(-?\\d+(?:\\.\\d+)?)").findAll(text)) {
            if (!inString(m.range.first)) span(sb, m.range.first, m.range.last + 1, C_NUMBER)
        }
        // Booleans / null (outside strings)
        for (m in Regex("\\b(true|false|null)\\b").findAll(text)) {
            if (!inString(m.range.first)) span(sb, m.range.first, m.range.last + 1, C_BOOL)
        }
    }

    private fun highlightXml(sb: SpannableStringBuilder) {
        val text = sb.toString()
        // Comments
        for (m in Regex("<!--[\\s\\S]*?-->").findAll(text)) {
            span(sb, m.range.first, m.range.last + 1, C_COMMENT)
        }
        // DOCTYPE / declarations
        for (m in Regex("<![^>]*>|<\\?[^?]*\\?>").findAll(text)) {
            span(sb, m.range.first, m.range.last + 1, C_COMMENT)
        }
        // Tags: <name ... > — color tag name blue, attributes light blue, values orange
        for (m in Regex("</?[A-Za-z_][\\w.:-]*").findAll(text)) {
            span(sb, m.range.first, m.range.last + 1, C_TAG)
        }
        for (m in Regex("[A-Za-z_][\\w.:-]*=").findAll(text)) {
            span(sb, m.range.first, m.range.last, C_ATTR)
        }
        for (m in Regex("\"[^\"]*\"").findAll(text)) {
            span(sb, m.range.first, m.range.last + 1, C_KEY)
        }
        // plist <key> values and <string> contents
        for (m in Regex("(?<=<key>)[^<]+(?=</key>)").findAll(text)) {
            span(sb, m.range.first, m.range.last + 1, C_KEY, bold = true)
        }
        for (m in Regex("(?<=<string>)[^<]+(?=</string>)").findAll(text)) {
            span(sb, m.range.first, m.range.last + 1, C_STRING)
        }
        for (m in Regex("<(true|false)\\/>").findAll(text)) {
            span(sb, m.range.first, m.range.last + 1, C_BOOL)
        }
        for (m in Regex("(?<=<integer>)[^<]+(?=</integer>)").findAll(text)) {
            span(sb, m.range.first, m.range.last + 1, C_NUMBER)
        }
    }

    private fun highlightTxt(sb: SpannableStringBuilder) {
        val text = sb.toString()
        for (m in Regex("^#.*", RegexOption.MULTILINE).findAll(text)) {
            span(sb, m.range.first, m.range.last + 1, C_COMMENT)
        }
        for (m in Regex("(?m)^[^\\s#][^\\s]*").findAll(text)) {
            span(sb, m.range.first, m.range.last + 1, C_KEY)
        }
        for (m in Regex("\\b\\d+\\b").findAll(text)) {
            span(sb, m.range.first, m.range.last + 1, C_NUMBER)
        }
    }

    /** Avoid showing raw color ints to callers. */
    @Suppress("unused")
    fun defaultTextColor(): Int = Color.parseColor("#D4D4D4")
}
