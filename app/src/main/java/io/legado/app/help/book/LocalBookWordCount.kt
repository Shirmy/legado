package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object LocalBookWordCount {

    private const val BOOK_DELAY = 250L
    private const val BATCH_SIZE = 20
    private const val BATCH_DELAY = 1500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handledBookUrls = mutableSetOf<String>()
    private val pendingBooks = linkedMapOf<String, Book>()
    private var workerRunning = false

    @Synchronized
    private fun enqueue(book: Book): Boolean {
        if (!book.isLocal ||
            !book.wordCount.isNullOrBlank() ||
            handledBookUrls.contains(book.bookUrl) ||
            pendingBooks.containsKey(book.bookUrl)
        ) {
            return false
        }
        pendingBooks[book.bookUrl] = book
        return true
    }

    @Synchronized
    private fun pollBook(): Book? {
        val entry = pendingBooks.entries.firstOrNull() ?: return null
        pendingBooks.remove(entry.key)
        return entry.value
    }

    @Synchronized
    private fun markHandled(bookUrl: String) {
        handledBookUrls.add(bookUrl)
        pendingBooks.remove(bookUrl)
    }

    @Synchronized
    private fun startWorkerIfNeeded() {
        if (workerRunning) {
            return
        }
        workerRunning = true
        scope.launch {
            processQueue()
        }
    }

    @Synchronized
    private fun stopWorkerIfQueueEmpty(): Boolean {
        if (pendingBooks.isNotEmpty()) {
            return false
        }
        workerRunning = false
        return true
    }

    fun refreshMissing(books: List<Book>) {
        if (books.none { it.isLocal && it.wordCount.isNullOrBlank() }) {
            return
        }
        var hasNewBook = false
        books.forEach {
            if (enqueue(it)) {
                hasNewBook = true
            }
        }
        if (hasNewBook) {
            startWorkerIfNeeded()
        }
    }

    private suspend fun processQueue() {
        var processedCount = 0
        while (true) {
            val book = pollBook()
            if (book == null) {
                if (stopWorkerIfQueueEmpty()) {
                    return
                }
                continue
            }
            try {
                refreshBook(book)
            } finally {
                markHandled(book.bookUrl)
            }
            processedCount++
            delay(if (processedCount % BATCH_SIZE == 0) BATCH_DELAY else BOOK_DELAY)
        }
    }

    private fun refreshBook(book: Book) {
        kotlin.runCatching {
            val oldWordCount = book.wordCount
            val oldLatestChapterTime = book.latestChapterTime
            val chapters = LocalBook.getChapterList(book)
            book.latestChapterTime = oldLatestChapterTime
            if (book.wordCount != oldWordCount) {
                appDb.bookDao.update(book)
                postEvent(EventBus.UP_BOOKSHELF, book.bookUrl)
            }
            if (appDb.bookChapterDao.getChapterCount(book.bookUrl) == 0) {
                appDb.bookChapterDao.insert(*chapters.toTypedArray())
            }
        }.onFailure {
            AppLog.put("刷新本地书籍字数失败\n${book.name}: ${it.localizedMessage}", it)
        }
    }

}
