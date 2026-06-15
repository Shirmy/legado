package io.legado.app.help.book

import android.os.Build
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.AppPattern.spaceRegex
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.exception.RegexTimeoutException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.escapeRegex
import io.legado.app.utils.replace
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import splitties.init.appCtx
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern

class ContentProcessor private constructor(
    private val bookName: String,
    private val bookOrigin: String
) {

    companion object {
        private val processors = hashMapOf<String, WeakReference<ContentProcessor>>()
        private val isAndroid8 = Build.VERSION.SDK_INT in 26..27

        fun get(book: Book) = get(book.name, book.origin)

        fun get(bookName: String, bookOrigin: String): ContentProcessor {
            val processorWr = processors[bookName + bookOrigin]
            var processor: ContentProcessor? = processorWr?.get()
            if (processor == null) {
                processor = ContentProcessor(bookName, bookOrigin)
                processors[bookName + bookOrigin] = WeakReference(processor)
            }
            return processor
        }

        fun upReplaceRules() {
            processors.forEach {
                it.value.get()?.upReplaceRules()
            }
        }

    }

    private val titleReplaceRules = CopyOnWriteArrayList<ReplaceRule>()
    private val contentReplaceRules = CopyOnWriteArrayList<ReplaceRule>()
    val removeSameTitleCache = hashSetOf<String>()

    init {
        upReplaceRules()
        upRemoveSameTitle()
    }

    fun upReplaceRules() {
        titleReplaceRules.run {
            clear()
            addAll(appDb.replaceRuleDao.findEnabledByTitleScope(bookName, bookOrigin))
        }
        contentReplaceRules.run {
            clear()
            addAll(appDb.replaceRuleDao.findEnabledByContentScope(bookName, bookOrigin))
        }
    }

    private fun upRemoveSameTitle() {
        val book = appDb.bookDao.getBookByOrigin(bookName, bookOrigin) ?: return
        removeSameTitleCache.clear()
        val files = BookHelp.getChapterFiles(book).filter {
            it.endsWith("nr")
        }
        removeSameTitleCache.addAll(files)
    }

    fun getTitleReplaceRules(): List<ReplaceRule> {
        return titleReplaceRules
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun getContentReplaceRules(): List<ReplaceRule> {
        return contentReplaceRules
    }

    fun getContentBeforeContentReplace(
        book: Book,
        chapter: BookChapter,
        content: String,
        chineseConvert: Boolean = true,
        reSegment: Boolean = true
    ): String {
        var mContent = content
        val replaceBook by lazy { book.toReplaceBook() }
        if (content != "null") {
            val fileName = chapter.getFileName("nr")
            if (!removeSameTitleCache.contains(fileName)) try {
                val name = Pattern.quote(book.name)
                var title = chapter.title.escapeRegex().replace(spaceRegex, "\\\\s*")
                var matcher = Pattern.compile("^(\\s|\\p{P}|${name})*${title}(\\s)*")
                    .matcher(mContent)
                if (matcher.find()) {
                    mContent = mContent.substring(matcher.end())
                } else if (book.getUseReplaceRule()) {
                    title = Pattern.quote(
                        chapter.getDisplayTitle(
                            titleReplaceRules,
                            chineseConvert = false,
                            replaceBook = replaceBook
                        )
                    )
                    matcher = Pattern.compile("^(\\s|\\p{P}|${name})*${title}(\\s)*")
                        .matcher(mContent)
                    if (matcher.find()) {
                        mContent = mContent.substring(matcher.end())
                    }
                }
            } catch (e: Exception) {
                AppLog.put("去除重复标题出错\n${e.localizedMessage}", e)
            }
            if (reSegment && book.getReSegment()) {
                mContent = ContentHelp.reSegment(mContent, chapter.title)
            }
            if (chineseConvert) {
                try {
                    when (AppConfig.chineseConverterType) {
                        1 -> mContent = ChineseUtils.t2s(mContent)
                        2 -> mContent = ChineseUtils.s2t(mContent)
                    }
                } catch (_: Exception) {
                    appCtx.toastOnUi("简繁转换出错")
                }
            }
            if (AppConfig.adaptSpecialStyle) {
                mContent = AppPattern.useHtmlRegex.replace(mContent) { matchResult ->
                    "特殊格式的占位不应该被看见。"
                }
            }
            mContent = mContent.lines().joinToString("\n") { it.trim() }
        }
        return mContent
    }

    fun getContentBeforeReplaceRule(
        book: Book,
        chapter: BookChapter,
        content: String,
        ruleId: Long
    ): String {
        var mContent = getContentBeforeContentReplace(book, chapter, content)
        val replaceBook by lazy { book.toReplaceBook() }
        getContentReplaceRules().forEach { item ->
            if (item.id == ruleId) {
                return mContent
            }
            if (item.pattern.isEmpty()) {
                return@forEach
            }
            try {
                mContent = if (item.isRegex) {
                    mContent.replace(
                        item.name,
                        item.regex,
                        item.replacement,
                        item.getValidTimeoutMillisecond(),
                        chapter,
                        replaceBook
                    )
                } else {
                    mContent.replace(item.pattern, item.replacement)
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                AppLog.put("替换净化: 规则 ${item.name}替换出错.\n${mContent}", e)
            }
        }
        return mContent
    }

    fun replaceContentRule(
        book: Book,
        chapter: BookChapter,
        content: String,
        rule: ReplaceRule
    ): String {
        if (rule.pattern.isEmpty()) {
            return content
        }
        val replaceBook by lazy { book.toReplaceBook() }
        return try {
            if (rule.isRegex) {
                content.replace(
                    rule.name,
                    rule.regex,
                    rule.replacement,
                    rule.getValidTimeoutMillisecond(),
                    chapter,
                    replaceBook
                )
            } else {
                content.replace(rule.pattern, rule.replacement)
            }
        } catch (e: RegexTimeoutException) {
            rule.isEnabled = false
            appDb.replaceRuleDao.update(rule)
            rule.name + e.stackTraceStr
        } catch (_: CancellationException) {
            content
        } catch (e: Exception) {
            AppLog.put("替换净化: 规则 ${rule.name}替换出错.\n$content", e)
            appCtx.toastOnUi("替换净化: 规则 ${rule.name}替换出错")
            content
        }
    }

    fun getContentFromPreparedContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        useReplace: Boolean = true,
        startRuleId: Long? = null
    ): BookContent {
        var mContent = content
        var effectiveReplaceRules: ArrayList<ReplaceRule>? = null
        if (useReplace && book.getUseReplaceRule()) {
            effectiveReplaceRules = arrayListOf()
            var canReplace = startRuleId == null
            getContentReplaceRules().forEach { item ->
                if (!canReplace) {
                    canReplace = item.id == startRuleId
                }
                if (!canReplace) {
                    return@forEach
                }
                if (item.pattern.isEmpty()) {
                    return@forEach
                }
                try {
                    val tmp = replaceContentRule(book, chapter, mContent, item)
                    if (mContent != tmp) {
                        effectiveReplaceRules.add(item)
                        mContent = tmp
                    }
                } catch (e: RegexTimeoutException) {
                    item.isEnabled = false
                    appDb.replaceRuleDao.update(item)
                    mContent = item.name + e.stackTraceStr
                } catch (_: CancellationException) {
                } catch (e: Exception) {
                    AppLog.put("替换净化: 规则 ${item.name}替换出错.\n${mContent}", e)
                    appCtx.toastOnUi("替换净化: 规则 ${item.name}替换出错")
                }
            }
        }
        if (isAndroid8) {
            mContent = mContent.replace('\u00A0', ' ')
        }
        val contents = arrayListOf<String>()
        mContent.split("\n").forEach { str ->
            val paragraph = str.trim {
                it.code <= 0x20 || it == '　'
            }
            if (paragraph.isNotEmpty()) {
                contents.add("${ReadBookConfig.paragraphIndent}$paragraph")
            }
        }
        return BookContent(false, contents, effectiveReplaceRules)
    }

    fun getEffectiveReplaceRules(
        book: Book,
        chapter: BookChapter,
        content: String,
        missingRuleIds: Set<Long> = emptySet(),
        rules: List<ReplaceRule> = getContentReplaceRules()
    ): List<ReplaceRule> {
        if (content == "null" || !book.getUseReplaceRule()) {
            return emptyList()
        }
        var mContent = content
        val effectiveReplaceRules = arrayListOf<ReplaceRule>()
        val remainingRuleIds = missingRuleIds.toMutableSet()
        val replaceBook by lazy { book.toReplaceBook() }
        val fileName = chapter.getFileName("nr")
        if (!removeSameTitleCache.contains(fileName)) try {
            val name = Pattern.quote(book.name)
            var title = chapter.title.escapeRegex().replace(spaceRegex, "\\\\s*")
            var matcher = Pattern.compile("^(\\s|\\p{P}|${name})*${title}(\\s)*")
                .matcher(mContent)
            if (matcher.find()) {
                mContent = mContent.substring(matcher.end())
            } else {
                title = Pattern.quote(
                    chapter.getDisplayTitle(
                        titleReplaceRules,
                        chineseConvert = false,
                        replaceBook = replaceBook
                    )
                )
                matcher = Pattern.compile("^(\\s|\\p{P}|${name})*${title}(\\s)*")
                    .matcher(mContent)
                if (matcher.find()) {
                    mContent = mContent.substring(matcher.end())
                }
            }
        } catch (e: Exception) {
            AppLog.put("Remove duplicate title failed\n${e.localizedMessage}", e)
        }
        if (book.getReSegment()) {
            mContent = ContentHelp.reSegment(mContent, chapter.title)
        }
        try {
            when (AppConfig.chineseConverterType) {
                1 -> mContent = ChineseUtils.t2s(mContent)
                2 -> mContent = ChineseUtils.s2t(mContent)
            }
        } catch (_: Exception) {
            appCtx.toastOnUi("Chinese convert error")
        }
        if (AppConfig.adaptSpecialStyle) {
            mContent = AppPattern.useHtmlRegex.replace(mContent) {
                "SPECIAL_STYLE_PLACEHOLDER"
            }
        }
        mContent = mContent.lines().joinToString("\n") { it.trim() }
        for (item in rules) {
            if (item.pattern.isEmpty()) {
                continue
            }
            try {
                val tmp = if (item.isRegex) {
                    mContent.replace(
                        item.name,
                        item.regex,
                        item.replacement,
                        item.getValidTimeoutMillisecond(),
                        chapter,
                        replaceBook
                    )
                } else {
                    mContent.replace(item.pattern, item.replacement)
                }
                if (mContent != tmp) {
                    effectiveReplaceRules.add(item)
                    remainingRuleIds.remove(item.id)
                    mContent = tmp
                    if (missingRuleIds.isNotEmpty() && remainingRuleIds.isEmpty()) {
                        break
                    }
                }
            } catch (e: RegexTimeoutException) {
                item.isEnabled = false
                appDb.replaceRuleDao.update(item)
                mContent = item.name + e.stackTraceStr
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                AppLog.put("Replace rule ${item.name} failed.\n${mContent}", e)
                appCtx.toastOnUi("Replace rule ${item.name} failed")
            }
        }
        return effectiveReplaceRules
    }
    fun getContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        includeTitle: Boolean = true,
        useReplace: Boolean = true,
        useBookReplaceState: Boolean = true,
        chineseConvert: Boolean = true,
        reSegment: Boolean = true
    ): BookContent {
        var mContent = content
        var sameTitleRemoved = false
        var effectiveReplaceRules: ArrayList<ReplaceRule>? = null
        val replaceBook by lazy { book.toReplaceBook() }
        val applyReplace = useReplace && (!useBookReplaceState || book.getUseReplaceRule())
        if (content != "null") {
            //去除重复标题
            val fileName = chapter.getFileName("nr")
            if (!removeSameTitleCache.contains(fileName)) try {
                val name = Pattern.quote(book.name)
                var title = chapter.title.escapeRegex().replace(spaceRegex, "\\\\s*")
                var matcher = Pattern.compile("^(\\s|\\p{P}|${name})*${title}(\\s)*")
                    .matcher(mContent)
                if (matcher.find()) {
                    mContent = mContent.substring(matcher.end())
                    sameTitleRemoved = true
                } else if (applyReplace) {
                    title = Pattern.quote(
                        chapter.getDisplayTitle(
                            titleReplaceRules,
                            chineseConvert = false,
                            replaceBook = replaceBook
                        )
                    )
                    matcher = Pattern.compile("^(\\s|\\p{P}|${name})*${title}(\\s)*")
                        .matcher(mContent)
                    if (matcher.find()) {
                        mContent = mContent.substring(matcher.end())
                        sameTitleRemoved = true
                    }
                }
            } catch (e: Exception) {
                AppLog.put("去除重复标题出错\n${e.localizedMessage}", e)
            }
            if (reSegment && book.getReSegment()) {
                //重新分段
                mContent = ContentHelp.reSegment(mContent, chapter.title)
            }
            if (chineseConvert) {
                //简繁转换
                try {
                    when (AppConfig.chineseConverterType) {
                        1 -> mContent = ChineseUtils.t2s(mContent)
                        2 -> mContent = ChineseUtils.s2t(mContent)
                    }
                } catch (_: Exception) {
                    appCtx.toastOnUi("简繁转换出错")
                }
            }
            val useHtmlMap = mutableMapOf<String, String>()
            if (AppConfig.adaptSpecialStyle) { //html处理
                mContent = AppPattern.useHtmlRegex.replace(mContent) { matchResult ->
                    val placeholder = "特殊格式的占位不应该被看见${useHtmlMap.size}。"
                    useHtmlMap[placeholder] = "\n${matchResult.value.replace("\n","")}\n"
                    placeholder
                }
            }
            if (applyReplace) {
                //替换
                effectiveReplaceRules = arrayListOf()
                mContent = mContent.lines().joinToString("\n") { it.trim() }
                getContentReplaceRules().forEach { item ->
                    if (item.pattern.isEmpty()) {
                        return@forEach
                    }
                    try {
                        val tmp = if (item.isRegex) {
                            mContent.replace(
                                item.name,
                                item.regex,
                                item.replacement,
                                item.getValidTimeoutMillisecond(),
                                chapter,
                                replaceBook
                            )
                        } else {
                            mContent.replace(item.pattern, item.replacement)
                        }
                        if (mContent != tmp) {
                            effectiveReplaceRules.add(item)
                            mContent = tmp
                        }
                    } catch (e: RegexTimeoutException) {
                        item.isEnabled = false
                        appDb.replaceRuleDao.update(item)
                        mContent = item.name + e.stackTraceStr
                    } catch (_: CancellationException) {
                    } catch (e: Exception) {
                        AppLog.put("替换净化: 规则 ${item.name}替换出错.\n${mContent}", e)
                        appCtx.toastOnUi("替换净化: 规则 ${item.name}替换出错")
                    }
                }
            }
            useHtmlMap.forEach { (placeholder, originalContent) ->
                mContent = mContent.replace(placeholder, originalContent)
            }
        }
        if (includeTitle) {
            //重新添加标题
            mContent = chapter.getDisplayTitle(
                getTitleReplaceRules(),
                useReplace = applyReplace,
                replaceBook = replaceBook
            ) + "\n" + mContent
        }
        if (isAndroid8) {
            mContent = mContent.replace('\u00A0', ' ')
        }
        val contents = arrayListOf<String>()
        mContent.split("\n").forEach { str ->
            val paragraph = str.trim {
                it.code <= 0x20 || it == '　'
            }
            if (paragraph.isNotEmpty()) {
                if (contents.isEmpty() && includeTitle) {
                    contents.add(paragraph)
                } else {
                    contents.add("${ReadBookConfig.paragraphIndent}$paragraph")
                }
            }
        }
        return BookContent(sameTitleRemoved, contents, effectiveReplaceRules)
    }

}
