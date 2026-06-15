package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.model.localBook.LocalBook

object LocalBookMissingChecker {

    private val checkedBookUrls = mutableSetOf<String>()
    private val promptedBookUrls = mutableSetOf<String>()
    private val checkingBookUrls = mutableSetOf<String>()

    @Synchronized
    private fun takePendingBooks(books: List<Book>, force: Boolean): List<Book> {
        return books.filter { book ->
            book.isLocal &&
                    (force || !checkedBookUrls.contains(book.bookUrl)) &&
                    (force || !promptedBookUrls.contains(book.bookUrl)) &&
                    checkingBookUrls.add(book.bookUrl)
        }
    }

    fun findMissing(books: List<Book>, force: Boolean = false): List<Book> {
        val pendingBooks = takePendingBooks(books, force)
        val missingBooks = pendingBooks.filter { book ->
            LocalBook.getLastModified(book).isFailure
        }
        synchronized(this) {
            val pendingBookUrls = pendingBooks.map { it.bookUrl }.toSet()
            checkingBookUrls.removeAll(pendingBookUrls)
            checkedBookUrls.addAll(pendingBookUrls)
            promptedBookUrls.addAll(missingBooks.map { it.bookUrl })
        }
        return missingBooks
    }

}
