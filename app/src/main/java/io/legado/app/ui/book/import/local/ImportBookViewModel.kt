package io.legado.app.ui.book.import.local

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.model.localBook.LocalBook
import io.legado.app.help.book.BookGroupOrderHelp
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.FileDoc
import io.legado.app.utils.delete
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.list
import io.legado.app.utils.mapParallel
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import java.util.Collections

class ImportBookViewModel(application: Application) : BaseViewModel(application) {
    var rootDoc: FileDoc? = null
    val subDocs = arrayListOf<FileDoc>()
    var sort = context.getPrefInt(PreferKey.localBookImportSort)
    var groupId: Long = context.getPrefLong(PreferKey.localBookImportGroupId)
    var groupName: String? = null
    var dataCallback: DataCallback? = null
    var dataFlowStart: (() -> Unit)? = null
    var filterKey: String? = null
    private val shelfFileGroups = Collections.synchronizedMap(mutableMapOf<String, Long>())
    val dataFlow = callbackFlow<List<ImportBook>> {

        val fileDocs = Collections.synchronizedList(ArrayList<FileDoc>())

        fun emitItems() {
            trySend(
                fileDocs.filter(::shouldShowDoc).map {
                    ImportBook(it, isOnBookShelf(it))
                }
            )
        }

        dataCallback = object : DataCallback {

            override fun setItems(newFileDocs: List<FileDoc>) {
                fileDocs.clear()
                fileDocs.addAll(newFileDocs)
                emitItems()
            }

            override fun addItems(newFileDocs: List<FileDoc>) {
                fileDocs.addAll(newFileDocs)
                emitItems()
            }

            override fun clear() {
                fileDocs.clear()
                trySend(emptyList())
            }

            override fun upAdapter() {
                emitItems()
            }
        }

        withContext(IO) {
            refreshShelfFileGroups()
        }
        withContext(Main) {
            dataFlowStart?.invoke()
        }

        awaitClose {
            dataCallback = null
        }

    }.map { docList ->
        val docList = docList.toList()
        val filterKey = filterKey
        val skipFilter = filterKey.isNullOrBlank()
        val comparator = when (sort) {
            2 -> compareBy<ImportBook>({ !it.isDir }, { -it.lastModified })
            1 -> compareBy({ !it.isDir }, { -it.size })
            else -> compareBy { !it.isDir }
        } then compareBy(AlphanumComparator) { it.name }
        docList.asSequence().filter {
            skipFilter || it.name.contains(filterKey)
        }.sortedWith(comparator).toList()
    }.flowOn(IO)

    fun addToBookshelf(bookList: HashSet<ImportBook>, finally: () -> Unit) {
        execute {
            val fileUris = bookList.map {
                it.file.uri
            }
            fileUris.forEach { uri ->
                LocalBook.importFiles(uri).forEach { book ->
                    if (groupId > 0 && groupId != book.group) {
                        BookGroupOrderHelp.sync(book.bookUrl, book.group, groupId)
                        book.group = groupId
                        appDb.bookDao.update(book)
                    }
                }
            }
            refreshShelfFileGroups()
        }.onError {
            context.toastOnUi("添加书架失败，请尝试重新选择文件夹")
            AppLog.put("添加书架失败\n${it.localizedMessage}", it)
        }.onSuccess {
            context.toastOnUi("添加书架成功")
        }.onFinally {
            finally.invoke()
        }
    }

    fun deleteDoc(bookList: HashSet<ImportBook>, finally: () -> Unit) {
        execute {
            bookList.forEach {
                it.file.delete()
            }
        }.onFinally {
            finally.invoke()
        }
    }

    fun loadDoc(fileDoc: FileDoc) {
        execute {
            val docList = fileDoc.list { item ->
                when {
                    item.name.startsWith(".") -> false
                    item.isDir -> true
                    else -> item.name.matches(bookFileRegex) || item.name.matches(archiveFileRegex)
                }
            }
            dataCallback?.setItems(docList!!)
        }.onError {
            context.toastOnUi("获取文件列表出错\n${it.localizedMessage}")
        }
    }

    suspend fun scanDoc(fileDoc: FileDoc) {
        dataCallback?.clear()
        val channel = Channel<FileDoc>(UNLIMITED)
        var n = 1
        channel.trySend(fileDoc)
        val list = arrayListOf<FileDoc>()
        channel.consumeAsFlow()
            .mapParallel(16) { fileDoc ->
                fileDoc.list()!!
            }.onEach { fileDocs ->
                n--
                list.clear()
                fileDocs.forEach {
                    if (it.isDir) {
                        n++
                        channel.trySend(it)
                    } else if (it.name.matches(bookFileRegex)
                        || it.name.matches(archiveFileRegex)
                    ) {
                        list.add(it)
                    }
                }
                dataCallback?.addItems(list)
            }.takeWhile {
                n > 0
            }.catch {
                context.toastOnUi("扫描文件夹出错\n${it.localizedMessage}")
            }.collect()
    }

    fun updateCallBackFlow(filterKey: String?) {
        this.filterKey = filterKey
        dataCallback?.upAdapter()
    }

    private fun shouldShowDoc(fileDoc: FileDoc): Boolean {
        return fileDoc.isDir || !isOnBookShelf(fileDoc)
    }

    private fun isOnBookShelf(fileDoc: FileDoc): Boolean {
        if (fileDoc.isDir) return false
        val bookGroup = shelfFileGroups[fileDoc.name] ?: return false
        return groupId <= 0L || bookGroup and groupId > 0L
    }

    fun refreshShelfFileGroups() {
        shelfFileGroups.clear()
        appDb.bookDao.getAllLocalBooks().forEach { book ->
            shelfFileGroups[book.originName] = book.group
            shelfFileGroups[book.originName.replace("%25", "%")] = book.group
        }
    }

    interface DataCallback {

        fun setItems(fileDocs: List<FileDoc>)

        fun addItems(fileDocs: List<FileDoc>)

        fun clear()

        fun upAdapter()

    }

}
