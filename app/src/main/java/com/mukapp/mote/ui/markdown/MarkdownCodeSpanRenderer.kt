package com.mukapp.mote.ui.markdown

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.util.LruCache
import io.noties.prism4j.AbsVisitor
import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j

private data class CodeContentCacheKey(
    val code: String,
    val normalizedLanguage: String,
    val colorKey: Int
)

class MarkdownCodeSpanRenderer(
    context: Context,
    private val codeColors: MarkdownCodeColors = resolveMarkdownCodeColors(context)
) {

    private val prism4j: Prism4j? = runCatching {
        Prism4j(createGrammarLocator())
    }.getOrNull()

    private val defaultTextColor: Int by lazy {
        codeColors.codeTextColor
    }
    private val keywordColor: Int by lazy {
        codeColors.keywordColor
    }
    private val stringColor: Int by lazy {
        codeColors.stringColor
    }
    private val commentColor: Int by lazy {
        codeColors.commentColor
    }
    private val numberColor: Int by lazy {
        codeColors.numberColor
    }
    private val punctuationColor: Int by lazy {
        blendWithAlpha(defaultTextColor, 0xCC)
    }
    private val annotationColor: Int by lazy {
        codeColors.annotationColor
    }

    fun buildCodeContent(code: String, language: String): SpannableStringBuilder {
        val normalizedLanguage = normalizeLanguage(language)
        val canUseCache = code.length <= MaxCachedCodeLength
        val cacheKey = if (canUseCache) {
            CodeContentCacheKey(
                code = code,
                normalizedLanguage = normalizedLanguage,
                colorKey = codeColors.hashCode()
            )
        } else {
            null
        }
        if (cacheKey != null) {
            synchronized(codeContentCacheLock) {
                codeContentCache.get(cacheKey)?.let { cached ->
                    return SpannableStringBuilder(cached)
                }
            }
        }

        val ssb = SpannableStringBuilder(code)
        if (ssb.isNotEmpty()) {
            ssb.setSpan(TypefaceSpan("monospace"), 0, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (code.length <= MaxHighlightedCodeLength && !applyPrismHighlight(ssb, code, normalizedLanguage)) {
                applyFallbackHighlight(ssb, normalizedLanguage, 0, ssb.length)
            }
        }
        if (cacheKey != null) {
            synchronized(codeContentCacheLock) {
                codeContentCache.put(cacheKey, SpannableStringBuilder(ssb))
            }
        }
        return ssb
    }

    private fun applyPrismHighlight(ssb: SpannableStringBuilder, code: String, normalizedLanguage: String): Boolean {
        val prism = prism4j ?: return false
        val grammar = prism.grammar(normalizedLanguage) ?: return false
        val nodes = prism.tokenize(code, grammar)
        if (nodes.isEmpty()) return false

        var offset = 0
        object : AbsVisitor() {
            override fun visitText(text: Prism4j.Text) {
                offset += text.literal().length
            }

            override fun visitSyntax(syntax: Prism4j.Syntax) {
                val start = offset
                if (syntax.tokenized()) {
                    visit(syntax.children())
                } else {
                    offset += syntax.matchedString().length
                }
                val end = offset
                if (end > start) {
                    colorForTokenType(syntax.type(), syntax.alias())?.let { color ->
                        ssb.setSpan(
                            ForegroundColorSpan(color),
                            start,
                            end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
            }
        }.visit(nodes)
        return true
    }

    private fun colorForTokenType(type: String?, alias: String?): Int? {
        val normalized = (alias ?: type).orEmpty().lowercase()
        return when (normalized) {
            "keyword", "operator", "boolean", "builtin", "atrule", "important" -> keywordColor
            "string", "char", "attr-value", "template-string", "regex", "url" -> stringColor
            "comment", "prolog", "doctype", "cdata" -> commentColor
            "number", "symbol", "constant" -> numberColor
            "punctuation", "delimiter" -> punctuationColor
            "annotation", "decorator", "tag", "property", "attr-name" -> annotationColor
            else -> null
        }
    }

    private fun createGrammarLocator(): GrammarLocator {
        val clazz = Class.forName("com.mukapp.mote.ui.markdown.MarkdownGrammarLocator")
        val ctor = clazz.getDeclaredConstructor()
        ctor.isAccessible = true
        return ctor.newInstance() as GrammarLocator
    }

    private fun normalizeLanguage(language: String): String {
        return when (language.trim().lowercase()) {
            "kt", "kts" -> "kotlin"
            "js" -> "javascript"
            "ts", "tsx", "jsx" -> "javascript"
            "py" -> "python"
            "html", "xml", "svg" -> "markup"
            "yml" -> "yaml"
            "c++" -> "cpp"
            "cs" -> "csharp"
            else -> language.trim().lowercase()
        }
    }

    private fun applyFallbackHighlight(ssb: SpannableStringBuilder, language: String, start: Int, end: Int) {
        val code = ssb.substring(start, end)
        val langLower = language.lowercase()

        val keywords = when {
            langLower in setOf("bash", "sh", "shell", "zsh") -> SHELL_KEYWORDS
            else -> null
        }

        if (keywords != null) {
            for (keyword in keywords) {
                var searchFrom = 0
                while (searchFrom < code.length) {
                    val idx = code.indexOf(keyword, searchFrom)
                    if (idx < 0) break
                    val beforeOk = idx == 0 || !code[idx - 1].isLetterOrDigit()
                    val afterIdx = idx + keyword.length
                    val afterOk = afterIdx >= code.length || !code[afterIdx].isLetterOrDigit()
                    if (beforeOk && afterOk) {
                        ssb.setSpan(
                            ForegroundColorSpan(keywordColor),
                            start + idx,
                            start + afterIdx,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    searchFrom = idx + keyword.length
                }
            }
        }

        for (match in STRING_REGEX.findAll(code)) {
            val matchStart = start + match.range.first
            val matchEnd = start + match.range.last + 1
            if (matchStart < end && matchEnd <= end) {
                ssb.setSpan(
                    ForegroundColorSpan(stringColor),
                    matchStart,
                    matchEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        for (match in COMMENT_REGEX.findAll(code)) {
            val matchStart = start + match.range.first
            val matchEnd = start + match.range.last + 1
            if (matchStart < end && matchEnd <= end) {
                ssb.setSpan(
                    ForegroundColorSpan(commentColor),
                    matchStart,
                    matchEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private companion object {
        private val STRING_REGEX = Regex("""\"[^\"]*\"|'[^']*'|\"\"\"[\s\S]*?\"\"\"""")
        private val COMMENT_REGEX = Regex("#.*?$", RegexOption.MULTILINE)
        private const val MaxHighlightedCodeLength = 24_000
        private const val MaxCachedCodeLength = 32_000
        private const val CodeContentCacheSizeChars = 240_000
        private val codeContentCacheLock = Any()
        private val codeContentCache = object : LruCache<CodeContentCacheKey, SpannableStringBuilder>(CodeContentCacheSizeChars) {
            override fun sizeOf(key: CodeContentCacheKey, value: SpannableStringBuilder): Int {
                return value.length.coerceAtLeast(1)
            }
        }

        private val SHELL_KEYWORDS = setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done",
            "case", "esac", "function", "return", "exit", "break", "continue", "in",
            "echo", "printf", "read", "cd", "pwd", "ls", "mkdir", "rm", "cp", "mv",
            "cat", "grep", "sed", "awk", "find", "sort", "uniq", "wc", "head", "tail",
            "chmod", "chown", "export", "source", "alias", "unset", "set", "shift",
            "test", "true", "false", "local", "declare", "typeset", "readonly"
        )
    }
}
