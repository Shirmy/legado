package io.legado.app.ui.book.read

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import splitties.init.appCtx
import java.io.File

object EffectiveReplaceFullCache {

    private const val SCAN_PARALLELISM = 2

    data class Snapshot(
        val key: String = "",
        val ruleIds: List<Long> = emptyList(),
        val scannedCount: Int = 0,
        val completed: Boolean = false
    )

    data class ScanState(
        val rules: List<ReplaceRule>,
        val scannedCount: Int,
        val totalCount: Int,
        val completed: Boolean
    )

    private val cacheDir by lazy {
        FileUtils.createFolderIfNotExist(appCtx.externalFiles, "effective_replace_full")
    }

    fun buildKey(book: Book, rules: List<ReplaceRule>): String {
        return buildString {
            append(book.bookUrl)
            append('|')
            append(book.originName)
            append('|')
            append(book.getUseReplaceRule())
            append('|')
            append(book.getReSegment())
            append('|')
            append(AppConfig.chineseConverterType)
            append('|')
            append(AppConfig.adaptSpecialStyle)
            append('|')
            append(book.totalChapterNum)
            append('|')
            append(book.latestChapterTime)
            append('|')
            if (book.isLocal) {
                runCatching {
                    val fileDoc = FileDoc.fromUri(book.getLocalUri(), false)
                    append(fileDoc.size)
                    append(':')
                    append(fileDoc.lastModified)
                }.onFailure {
                    append("local-file-missing")
                }
            }
            append('|')
            rules.forEach { rule ->
                append(rule.id)
                append(':')
                append(rule.order)
                append(':')
                append(rule.pattern.hashCode())
                append(':')
                append(rule.replacement.hashCode())
                append(':')
                append(rule.isRegex)
                append(';')
            }
        }
    }

    fun read(book: Book): Snapshot? {
        val file = cacheFile(book, create = false)
        if (!file.exists()) return null
        return GSON.fromJsonObject<Snapshot>(file.readText()).getOrNull()
    }

    fun readValid(book: Book, key: String): Snapshot? {
        return read(book)?.takeIf { it.key == key }
    }

    fun rulesFrom(snapshot: Snapshot, rules: List<ReplaceRule>): List<ReplaceRule> {
        val ruleMap = rules.associateBy { it.id }
        return snapshot.ruleIds.mapNotNull { ruleMap[it] }
    }

    fun write(book: Book, snapshot: Snapshot) {
        cacheFile(book).writeText(GSON.toJson(snapshot))
    }

    fun clear(book: Book) {
        FileUtils.delete(cacheFile(book))
    }

    suspend fun scan(
        book: Book,
        processor: ContentProcessor = ContentProcessor.get(book),
        replaceRules: List<ReplaceRule> = processor.getContentReplaceRules(),
        onUpdate: suspend (ScanState) -> Unit = {}
    ): ScanState = coroutineScope {
        val key = buildKey(book, replaceRules)
        val oldSnapshot = readValid(book, key)
        val ruleOrders = replaceRules.mapIndexed { index, rule -> rule.id to index }.toMap()
        val targetRuleIds = replaceRules.filter { it.pattern.isNotEmpty() }.map { it.id }.toSet()
        val ruleMap = linkedMapOf<Long, ReplaceRule>()
        oldSnapshot?.let { snapshot ->
            rulesFrom(snapshot, replaceRules).forEach { rule ->
                ruleMap[rule.id] = rule
            }
            if (snapshot.completed) {
                val rules = sortRules(ruleMap.values, ruleOrders)
                return@coroutineScope ScanState(rules, snapshot.scannedCount, snapshot.scannedCount, true)
            }
        }

        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        fun sortedRules() = sortRules(ruleMap.values, ruleOrders)
        fun isAllRulesFound() = targetRuleIds.isNotEmpty() && ruleMap.keys.containsAll(targetRuleIds)
        suspend fun writeAndUpdate(scannedCount: Int, completed: Boolean) {
            val rules = sortedRules()
            val snapshot = Snapshot(
                key = key,
                ruleIds = rules.map { it.id },
                scannedCount = scannedCount,
                completed = completed
            )
            write(book, snapshot)
            onUpdate(ScanState(rules, scannedCount, chapters.size, completed))
        }
        val startScannedCount = oldSnapshot?.scannedCount?.coerceAtMost(chapters.size) ?: 0
        if (ruleMap.isNotEmpty()) {
            onUpdate(ScanState(sortedRules(), startScannedCount, chapters.size, false))
        }
        if (targetRuleIds.isEmpty() || isAllRulesFound()) {
            writeAndUpdate(startScannedCount, true)
            return@coroutineScope ScanState(sortedRules(), startScannedCount, chapters.size, true)
        }
        var scannedCount = startScannedCount
        var finalStateWritten = false
        val waitingChapters = chapters.drop(startScannedCount)
        for (batch in waitingChapters.chunked(SCAN_PARALLELISM)) {
            currentCoroutineContext().ensureActive()
            if (isAllRulesFound()) {
                break
            }
            val missingRuleIds = targetRuleIds - ruleMap.keys
            val lastMissingRuleIndex = replaceRules.indexOfLast { it.id in missingRuleIds }
            if (lastMissingRuleIndex < 0) {
                break
            }
            val rulesToScan = replaceRules.take(lastMissingRuleIndex + 1)
            val batchRules = batch.map { chapter ->
                async {
                    currentCoroutineContext().ensureActive()
                    val content = BookHelp.getContent(book, chapter)
                    if (content == null) {
                        emptyList()
                    } else {
                        processor.getEffectiveReplaceRules(
                            book,
                            chapter,
                            content,
                            missingRuleIds,
                            rulesToScan
                        )
                    }
                }
            }.awaitAll()
            batchRules.forEach { rules ->
                rules.forEach { rule ->
                    ruleMap[rule.id] = rule
                }
            }
            scannedCount += batch.size
            val completed = scannedCount >= chapters.size || isAllRulesFound()
            if (completed || scannedCount % 10 == 0) {
                writeAndUpdate(scannedCount, completed)
                finalStateWritten = completed
            }
            if (completed) {
                break
            }
        }

        val completed = scannedCount >= chapters.size || isAllRulesFound()
        if (!finalStateWritten) {
            writeAndUpdate(scannedCount, completed)
        }
        ScanState(sortedRules(), scannedCount, chapters.size, completed)
    }

    private fun sortRules(
        rules: Collection<ReplaceRule>,
        ruleOrders: Map<Long, Int>
    ): List<ReplaceRule> {
        return rules.sortedWith(compareBy({ ruleOrders[it.id] ?: Int.MAX_VALUE }, { it.name }))
    }

    private fun cacheFile(book: Book, create: Boolean = true): File {
        val file = File(cacheDir, "${MD5Utils.md5Encode16(book.bookUrl)}.json")
        return if (create) {
            FileUtils.createFileIfNotExist(file.absolutePath)
        } else {
            file
        }
    }
}
