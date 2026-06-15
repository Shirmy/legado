package io.legado.app.model.webBook

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.mapParallelSafe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx
import java.util.concurrent.Executors
import kotlin.math.min

class SearchModel(private val scope: CoroutineScope, private val callBack: CallBack) {
    val threadCount = AppConfig.threadCount
    private var searchPool: ExecutorCoroutineDispatcher? = null
    private var mSearchId = 0L
    private var searchPage = 1
    private var searchKey: String = ""
    private var bookSourceParts = emptyList<BookSourcePart>()
    private var searchBooks = arrayListOf<SearchBook>()
    private var searchJob: Job? = null
    private var workingState = MutableStateFlow(true)


    private fun initSearchPool() {
        searchPool?.close()
        searchPool = Executors
            .newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    }

    fun search(searchId: Long, key: String) {
        if (searchId != mSearchId) {
            if (key.isEmpty()) {
                return
            }
            searchKey = key
            if (mSearchId != 0L) {
                close()
            }
            searchBooks.clear()
            bookSourceParts = callBack.getSearchScope().getBookSourceParts()
            if (bookSourceParts.isEmpty()) {
                callBack.onSearchCancel(searchId, NoStackTraceException("启用书源为空"))
                return
            }
            mSearchId = searchId
            searchPage = 1
            initSearchPool()
        } else {
            searchPage++
        }
        startSearch(searchId)
    }

    private fun startSearch(searchId: Long) {
        val precision = appCtx.getPrefBoolean(PreferKey.precisionSearch)
        var hasMore = false
        searchJob = scope.launch(searchPool!!) {
            flow {
                for (bs in bookSourceParts) {
                    bs.getBookSource()?.let {
                        emit(it)
                    }
                    workingState.first { it }
                }
            }.onStart {
                callBack.onSearchStart(searchId)
            }.mapParallelSafe(threadCount) {
                withTimeout(30000L) {
                    WebBook.searchBookAwait(
                        it, searchKey, searchPage,
                        filter = { name, author, kind ->
                            !precision || name.contains(searchKey) ||
                                    author.contains(searchKey) ||
                                    kind?.contains(searchKey) == true
                        })
                }
            }.onEach { items ->
                for (book in items) {
                    book.releaseHtmlData()
                }
                hasMore = hasMore || items.isNotEmpty()
                appDb.searchBookDao.insert(*items.toTypedArray())
                mergeItems(items, precision)
                currentCoroutineContext().ensureActive()
                callBack.onSearchSuccess(searchId, searchBooks)
            }.onCompletion {
                if (it == null) callBack.onSearchFinish(searchId, searchBooks.isEmpty(), hasMore)
            }.catch {
                AppLog.put("书源搜索出错\n${it.localizedMessage}", it)
            }.collect()
        }
    }

    private suspend fun mergeItems(newDataS: List<SearchBook>, precision: Boolean) {
        if (newDataS.isNotEmpty()) {
            val copyData = ArrayList(searchBooks)
            val equalData = arrayListOf<SearchBook>()
            val containsData = arrayListOf<SearchBook>()
            val tagsData = arrayListOf<SearchBook>()
            val otherData = arrayListOf<SearchBook>()
            val equalMap = hashMapOf<String, ArrayList<SearchBook>>()
            val containsMap = hashMapOf<String, ArrayList<SearchBook>>()
            val tagsMap = hashMapOf<String, ArrayList<SearchBook>>()
            val otherMap = hashMapOf<String, ArrayList<SearchBook>>()
            fun mergeKey(book: SearchBook) = book.name + '\u0000' + book.author
            fun addBook(
                data: ArrayList<SearchBook>,
                index: HashMap<String, ArrayList<SearchBook>>,
                book: SearchBook
            ) {
                data.add(book)
                index.getOrPut(mergeKey(book)) { arrayListOf() }.add(book)
            }
            fun mergeBook(
                data: ArrayList<SearchBook>,
                index: HashMap<String, ArrayList<SearchBook>>,
                book: SearchBook
            ) {
                val sameBooks = index[mergeKey(book)]
                if (sameBooks.isNullOrEmpty()) {
                    addBook(data, index, book)
                } else {
                    sameBooks.forEach { it.addOrigin(book.origin) }
                }
            }
            copyData.forEach {
                currentCoroutineContext().ensureActive()
                if (it.name == searchKey || it.author == searchKey) {
                    addBook(equalData, equalMap, it)
                } else if (it.kind?.contains(searchKey) == true) {
                    addBook(tagsData, tagsMap, it)
                } else if (it.name.contains(searchKey) || it.author.contains(searchKey)) {
                    addBook(containsData, containsMap, it)
                } else {
                    addBook(otherData, otherMap, it)
                }
            }
            newDataS.forEach { nBook ->
                currentCoroutineContext().ensureActive()
                if (nBook.name == searchKey || nBook.author == searchKey) {
                    mergeBook(equalData, equalMap, nBook)
                } else if (nBook.kind?.contains(searchKey) == true) {
                    mergeBook(tagsData, tagsMap, nBook)
                } else if (nBook.name.contains(searchKey) || nBook.author.contains(searchKey)) {
                    mergeBook(containsData, containsMap, nBook)
                } else if (!precision) {
                    mergeBook(otherData, otherMap, nBook)
                }
            }
            currentCoroutineContext().ensureActive()
            equalData.sortByDescending { it.origins.size }
            equalData.addAll(tagsData.sortedByDescending { it.origins.size })
            equalData.addAll(containsData.sortedByDescending { it.origins.size })
            if (!precision) {
                equalData.addAll(otherData)
            }
            currentCoroutineContext().ensureActive()
            searchBooks = equalData
        }
    }

    fun pause() {
        workingState.value = false
    }

    fun resume() {
        workingState.value = true
    }

    fun cancelSearch() {
        val searchId = mSearchId
        close()
        callBack.onSearchCancel(searchId)
    }

    fun close() {
        searchJob?.cancel()
        searchPool?.close()
        searchPool = null
        mSearchId = 0L
    }

    interface CallBack {
        fun getSearchScope(): SearchScope
        fun onSearchStart(searchId: Long)
        fun onSearchSuccess(searchId: Long, searchBooks: List<SearchBook>)
        fun onSearchFinish(searchId: Long, isEmpty: Boolean, hasMore: Boolean)
        fun onSearchCancel(searchId: Long, exception: Throwable? = null)
    }

}
