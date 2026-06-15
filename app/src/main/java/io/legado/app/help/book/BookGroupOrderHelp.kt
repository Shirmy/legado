package io.legado.app.help.book

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroupOrder

object BookGroupOrderHelp {

    fun orderMap(groupId: Long): Map<String, Long> {
        if (groupId <= 0) return emptyMap()
        return appDb.bookGroupOrderDao.getByGroup(groupId).associate { it.bookUrl to it.order }
    }

    fun sync(bookUrl: String, oldGroup: Long, newGroup: Long) {
        val oldIds = groupIds(oldGroup)
        val newIds = groupIds(newGroup)
        (oldIds - newIds).forEach { groupId ->
            appDb.bookGroupOrderDao.delete(groupId, bookUrl)
        }
        addBookToGroups(bookUrl, newIds - oldIds)
    }

    fun syncAddedToGroup(groupId: Long, books: Collection<Book>) {
        if (groupId <= 0 || books.isEmpty()) return
        val startOrder = appDb.bookGroupOrderDao.minOrder(groupId) ?: 0L
        val refs = books
            .filter { it.group and groupId == 0L }
            .mapIndexed { index, book ->
                BookGroupOrder(groupId, book.bookUrl, startOrder - index - 1L)
            }
        if (refs.isNotEmpty()) {
            appDb.bookGroupOrderDao.insert(*refs.toTypedArray())
        }
    }

    fun syncRemovedFromGroup(groupId: Long, books: Collection<Book>) {
        if (groupId <= 0 || books.isEmpty()) return
        appDb.bookGroupOrderDao.deleteByGroupAndBooks(groupId, books.map { it.bookUrl })
    }

    private fun addBookToGroups(bookUrl: String, groupIds: Set<Long>) {
        groupIds.forEach { groupId ->
            val order = (appDb.bookGroupOrderDao.minOrder(groupId) ?: 0L) - 1L
            appDb.bookGroupOrderDao.insert(BookGroupOrder(groupId, bookUrl, order))
        }
    }

    private fun groupIds(group: Long): Set<Long> {
        if (group <= 0) return emptySet()
        return appDb.bookGroupDao.all
            .asSequence()
            .map { it.groupId }
            .filter { it > 0 && group and it > 0 }
            .toSet()
    }
}
