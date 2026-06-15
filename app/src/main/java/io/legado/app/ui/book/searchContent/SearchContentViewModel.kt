package io.legado.app.ui.book.searchContent


import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.book.BookContent
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.ChineseUtils
import java.util.LinkedHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class SearchContentViewModel(application: Application) : BaseViewModel(application) {
    companion object {
        var replaceEnabled: Boolean = false
        var regexReplace: Boolean = true
        private const val processedContentCacheMaxChars = 5_000_000
        private val cacheLock = Any()
        private var processedContentCacheChars = 0
        private val processedContentCache =
            LinkedHashMap<String, BookContent>(16, 0.75f, true)
        private var preparedContentCacheChars = 0
        private val preparedContentCache =
            LinkedHashMap<String, String>(16, 0.75f, true)

        private fun trimProcessedContentCache() {
            val iterator = processedContentCache.entries.iterator()
            while (processedContentCacheChars > processedContentCacheMaxChars && iterator.hasNext()) {
                val item = iterator.next()
                processedContentCacheChars -= item.value.toString().length
                iterator.remove()
            }
        }

        private fun trimPreparedContentCache() {
            val iterator = preparedContentCache.entries.iterator()
            while (preparedContentCacheChars > processedContentCacheMaxChars && iterator.hasNext()) {
                val item = iterator.next()
                preparedContentCacheChars -= item.value.length
                iterator.remove()
            }
        }

    }

    private fun appendRuleVersion(builder: StringBuilder, rule: ReplaceRule) {
        builder.append(rule.id)
        builder.append(':')
        builder.append(rule.pattern.hashCode())
        builder.append(':')
        builder.append(rule.replacement.hashCode())
        builder.append(':')
        builder.append(rule.isRegex)
        builder.append(':')
        builder.append(rule.getValidTimeoutMillisecond())
        builder.append(';')
    }

    var bookUrl: String = ""
    var book: Book? = null
    private var contentProcessor: ContentProcessor? = null
    var effectiveReplaceRuleId: Long? = null
    var effectiveReplaceRuleName: String? = null
    var lastQuery: String = ""
    var searchResultCounts = 0
    val cacheChapterNames = hashSetOf<String>()
    val searchResultList: MutableList<SearchResult> = mutableListOf()

    fun initBook(bookUrl: String, success: () -> Unit) {
        this.bookUrl = bookUrl
        execute {
            book = appDb.bookDao.getBook(bookUrl)
            book?.let {
                contentProcessor = ContentProcessor.get(it.name, it.origin)
            }
        }.onSuccess {
            success.invoke()
        }
    }

    suspend fun searchChapter(
        query: String,
        chapter: BookChapter
    ): List<SearchResult> {
        val searchResultsWithinChapter: MutableList<SearchResult> = mutableListOf()
        val book = book ?: return searchResultsWithinChapter
        val chapterContent = BookHelp.getContent(book, chapter) ?: return searchResultsWithinChapter
        currentCoroutineContext().ensureActive()
        chapter.title = when (AppConfig.chineseConverterType) {
            1 -> ChineseUtils.t2s(chapter.title)
            2 -> ChineseUtils.s2t(chapter.title)
            else -> chapter.title
        }
        currentCoroutineContext().ensureActive()
        effectiveReplaceRuleId?.let { ruleId ->
            return searchEffectiveReplaceRule(query, book, chapter, chapterContent, ruleId)
        }
        val mContent = getSearchBookContent(book, chapter, chapterContent, replaceEnabled)
            .toString()
        val positions = searchPosition(mContent, query)
        positions.forEachIndexed { index, resultPosition ->
            currentCoroutineContext().ensureActive()
            val position = resultPosition.first
            val construct = getResultAndQueryIndex(mContent, position, query)
            val result = SearchResult(
                resultCountWithinChapter = index,
                resultText = construct.second,
                chapterTitle = chapter.title,
                query = query,
                chapterIndex = chapter.index,
                queryIndexInResult = construct.first,
                queryIndexInChapter = position,
                queryLength = resultPosition.second,
                isRegex = regexReplace
            )
            searchResultsWithinChapter.add(result)
        }
        return searchResultsWithinChapter
    }

    private suspend fun searchEffectiveReplaceRule(
        query: String,
        book: Book,
        chapter: BookChapter,
        chapterContent: String,
        ruleId: Long
    ): List<SearchResult> {
        val bookContent = getSearchBookContent(book, chapter, chapterContent, true)
        val rule = bookContent.effectiveReplaceRules?.firstOrNull { it.id == ruleId }
            ?: return emptyList()
        effectiveReplaceRuleName = rule.name
        val searchContent = bookContent.toString()
        val ruleContent = getPreparedRuleContent(
            book,
            chapter,
            chapterContent,
            ruleId
        )
        val originalPositions = searchRulePosition(ruleContent, rule)
        val searchPositions = searchRulePosition(searchContent, rule).ifEmpty {
            searchReplacementPosition(searchContent, rule, ruleContent)
        }
        val displayPositions = originalPositions.ifEmpty { searchPositions }
        return displayPositions.mapIndexed { index, displayPosition ->
            val jumpPosition = searchPositions.getOrNull(index)
                ?: getProcessedPosition(book, chapter, ruleContent, displayPosition.first, rule)
            val displayContent = if (originalPositions.isNotEmpty()) ruleContent else searchContent
            val construct = getResultAndQueryIndex(
                displayContent,
                displayPosition.first,
                displayPosition.second
            )
            SearchResult(
                resultCountWithinChapter = index,
                resultText = construct.second,
                chapterTitle = chapter.title,
                query = query,
                chapterIndex = chapter.index,
                queryIndexInResult = construct.first,
                queryIndexInChapter = jumpPosition.first,
                queryLength = jumpPosition.second.coerceAtLeast(1),
                effectiveReplaceRuleName = rule.name,
                isRegex = rule.isRegex
            )
        }
    }

    private fun getProcessedPosition(
        book: Book,
        chapter: BookChapter,
        preparedContent: String,
        originalPosition: Int,
        rule: ReplaceRule
    ): Pair<Int, Int> {
        val position = originalPosition.coerceIn(0, preparedContent.length)
        val prefix = preparedContent.take(position)
        val processedPrefix = contentProcessor!!.getContentFromPreparedContent(
            book,
            chapter,
            prefix,
            useReplace = true,
            startRuleId = rule.id
        ).toString()
        return processedPrefix.length to 1
    }

    private suspend fun searchRulePosition(content: String, rule: ReplaceRule): List<Pair<Int, Int>> {
        val position: MutableList<Pair<Int, Int>> = mutableListOf()
        try {
            if (rule.isRegex) {
                rule.regex.findAll(content).forEach { match ->
                    currentCoroutineContext().ensureActive()
                    position.add(match.range.first to match.value.length)
                }
            } else {
                var index = content.indexOf(rule.pattern)
                while (index >= 0) {
                    currentCoroutineContext().ensureActive()
                    position.add(index to rule.pattern.length)
                    index = content.indexOf(rule.pattern, index + rule.pattern.length)
                }
            }
        } catch (_: Exception) {
        }
        return position
    }

    private suspend fun searchReplacementPosition(
        content: String,
        rule: ReplaceRule,
        sourceContent: String
    ): List<Pair<Int, Int>> {
        val replacements = buildReplacementCandidates(rule, sourceContent)
        if (replacements.isEmpty()) {
            return emptyList()
        }
        val position: MutableList<Pair<Int, Int>> = mutableListOf()
        replacements.forEach { replacement ->
            var index = content.indexOf(replacement)
            while (index >= 0) {
                currentCoroutineContext().ensureActive()
                position.add(index to replacement.length)
                index = content.indexOf(replacement, index + replacement.length)
            }
        }
        return position.distinct().sortedBy { it.first }
    }

    private fun buildReplacementCandidates(
        rule: ReplaceRule,
        sourceContent: String
    ): List<String> {
        val replacements = linkedSetOf<String>()
        addReplacementCandidates(replacements, rule.replacement)
        if (rule.isRegex && !rule.replacement.startsWith("@js:")) {
            try {
                rule.regex.findAll(sourceContent).forEach { match ->
                    val matcher = rule.regex.toPattern().matcher(match.value)
                    if (matcher.find()) {
                        val buffer = StringBuffer()
                        matcher.appendReplacement(buffer, rule.replacement)
                        matcher.appendTail(buffer)
                        addReplacementCandidates(replacements, buffer.toString())
                    }
                }
            } catch (_: Exception) {
            }
        }
        return replacements.filter { it.isNotBlank() }
    }

    private fun addReplacementCandidates(
        replacements: MutableSet<String>,
        replacement: String
    ) {
        if (replacement.isBlank()) {
            return
        }
        val lineFeedReplacement = replacement.replace("\r\n", "\n").replace('\r', '\n')
        replacements.add(lineFeedReplacement)
        lineFeedReplacement.toDisplayContentCandidates().forEach {
            replacements.add(it)
        }
    }

    private fun String.toDisplayContentCandidates(): List<String> {
        val lines = split('\n').map { line ->
            line.trim {
                it.code <= 0x20 || it == '　'
            }
        }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            return emptyList()
        }
        return listOf(
            lines.joinToString("\n"),
            lines.mapIndexed { index, line ->
                if (index == 0) line else "${ReadBookConfig.paragraphIndent}$line"
            }.joinToString("\n"),
            lines.joinToString("\n") { "${ReadBookConfig.paragraphIndent}$it" }
        )
    }

    private fun getPreparedRuleContent(
        book: Book,
        chapter: BookChapter,
        chapterContent: String,
        ruleId: Long
    ): String {
        val processor = contentProcessor!!
        val replaceVersion = buildString {
            append(AppConfig.chineseConverterType)
            append('|')
            append(book.getUseReplaceRule())
            append('|')
            append(book.getReSegment())
            append('|')
            append(AppConfig.adaptSpecialStyle)
            append('|')
            append(ReadBookConfig.paragraphIndent.hashCode())
            append('|')
            append(book.name.hashCode())
            append(':')
            append(book.origin.hashCode())
            append('|')
            append(book.toReplaceBook().hashCode())
            append('|')
            append(chapter.title.hashCode())
            append(':')
            append(chapter.url.hashCode())
            append(':')
            append(chapter.toString().hashCode())
            append('|')
            append(chapterContent.length)
            append(':')
            append(chapterContent.hashCode())
            append('|')
            processor.getTitleReplaceRules().forEach {
                appendRuleVersion(this, it)
            }
            append('|')
            processor.getContentReplaceRules().forEach {
                appendRuleVersion(this, it)
                if (it.id == ruleId) {
                    return@forEach
                }
            }
        }
        val key = "${book.bookUrl}:${chapter.index}:$ruleId:$replaceVersion"
        synchronized(cacheLock) {
            preparedContentCache[key]
        }?.let {
            return it
        }
        val contentBeforeRule = processor.getContentBeforeReplaceRule(
            book,
            chapter,
            chapterContent,
            ruleId
        )
        return synchronized(cacheLock) {
            preparedContentCache[key]?.let {
                return@synchronized it
            }
            preparedContentCache[key] = contentBeforeRule
            preparedContentCacheChars += contentBeforeRule.length
            trimPreparedContentCache()
            contentBeforeRule
        }
    }

    private fun getSearchBookContent(
        book: Book,
        chapter: BookChapter,
        chapterContent: String,
        useReplace: Boolean
    ): BookContent {
        val processor = contentProcessor!!
        if (!useReplace) {
            return processor.getContent(book, chapter, chapterContent, useReplace = false)
        }
        val replaceVersion = buildString {
            append(AppConfig.chineseConverterType)
            append('|')
            append(book.getUseReplaceRule())
            append('|')
            append(book.getReSegment())
            append('|')
            append(AppConfig.adaptSpecialStyle)
            append('|')
            append(ReadBookConfig.paragraphIndent.hashCode())
            append('|')
            append(book.name.hashCode())
            append(':')
            append(book.origin.hashCode())
            append('|')
            append(book.toReplaceBook().hashCode())
            append('|')
            append(chapter.title.hashCode())
            append(':')
            append(chapter.url.hashCode())
            append(':')
            append(chapter.toString().hashCode())
            append('|')
            append(chapterContent.length)
            append(':')
            append(chapterContent.hashCode())
            append('|')
            processor.getTitleReplaceRules().forEach {
                appendRuleVersion(this, it)
            }
            append('|')
            processor.getContentReplaceRules().forEach {
                appendRuleVersion(this, it)
            }
        }
        val key = "${book.bookUrl}:${chapter.index}:$replaceVersion"
        synchronized(cacheLock) {
            processedContentCache[key]
        }?.let {
            return it
        }
        val bookContent = processor.getContent(book, chapter, chapterContent, useReplace = true)
        return synchronized(cacheLock) {
            processedContentCache[key]?.let {
                return@synchronized it
            }
            processedContentCache[key] = bookContent
            processedContentCacheChars += bookContent.toString().length
            trimProcessedContentCache()
            bookContent
        }
    }

    private suspend fun searchPosition(content: String, pattern: String): List<Pair<Int, Int>> {
        val position: MutableList<Pair<Int, Int>> = mutableListOf()
        if (regexReplace) { // 正则表达式搜索
            try {
                Regex(pattern).findAll(content).forEach { match ->
                    currentCoroutineContext().ensureActive()
                    position.add(match.range.first to match.value.length)
                }
            } catch (e: Exception) {
                return position
            }
        } else {
            var index = content.indexOf(pattern)
            while (index >= 0) {
                currentCoroutineContext().ensureActive()
                position.add(index to pattern.length)
                index = content.indexOf(pattern, index + pattern.length)
            }
        }
        return position
    }

    private fun getResultAndQueryIndex(
        content: String,
        queryIndexInContent: Int,
        query: String
    ): Pair<Int, String> {
        return getResultAndQueryIndex(content, queryIndexInContent, query.length)
    }

    private fun getResultAndQueryIndex(
        content: String,
        queryIndexInContent: Int,
        queryLength: Int
    ): Pair<Int, String> {
        // 左右移动20个字符，构建关键词周边文字，在搜索结果里显示
        // 判断段落，只在关键词所在段落内分割
        // 利用标点符号分割完整的句
        // length和设置结合，自由调整周边文字长度
        val length = 20
        var po1 = queryIndexInContent - length
        var po2 = queryIndexInContent + queryLength + length
        if (po1 < 0) {
            po1 = 0
        }
        if (po2 > content.length) {
            po2 = content.length
        }
        val queryIndexInResult = queryIndexInContent - po1
        val newText = content.substring(po1, po2)
        return queryIndexInResult to newText
    }

}
