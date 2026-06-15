package io.legado.app.ui.main.bookshelf.style2

import android.content.Context
import android.os.Parcelable
import android.view.LayoutInflater
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup

abstract class BaseBooksAdapter<VH : RecyclerView.ViewHolder>(
    val context: Context,
    val callBack: CallBack
) : RecyclerView.Adapter<VH>() {
    private companion object {
        const val DIRECT_NOTIFY_THRESHOLD = 3000
    }

    private val layoutStates = mutableMapOf<Long, Parcelable?>()
    private var currentGroupId: Long? = null
    private var layoutManager: RecyclerView.LayoutManager? = null
    private var directItems: List<Any>? = null
    protected val inflater: LayoutInflater = LayoutInflater.from(context)

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        layoutManager = recyclerView.layoutManager
    }

    private val diffItemCallback = object : DiffUtil.ItemCallback<Any>() {

        override fun areItemsTheSame(oldItem: Any, newItem: Any): Boolean {
            return when {
                oldItem is Book && newItem is Book -> {
                    oldItem.name == newItem.name
                            && oldItem.author == newItem.author
                }

                oldItem is BookGroup && newItem is BookGroup -> {
                    oldItem.groupId == newItem.groupId
                }

                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: Any, newItem: Any): Boolean {
            return when {
                oldItem is Book && newItem is Book -> {
                    oldItem.durChapterTime == newItem.durChapterTime &&
                            oldItem.name == newItem.name &&
                            oldItem.author == newItem.author &&
                            oldItem.durChapterTitle == newItem.durChapterTitle &&
                            oldItem.latestChapterTitle == newItem.latestChapterTitle &&
                            oldItem.wordCount == newItem.wordCount &&
                            oldItem.pinTime == newItem.pinTime &&
                            oldItem.getDisplayCover() == newItem.getDisplayCover()
                }

                oldItem is BookGroup && newItem is BookGroup -> {
                    oldItem.groupName == newItem.groupName &&
                            oldItem.cover == newItem.cover &&
                            oldItem.enableRefresh == newItem.enableRefresh &&
                            oldItem.onlyUpdateRead == newItem.onlyUpdateRead
                }

                else -> false
            }
        }

        override fun getChangePayload(oldItem: Any, newItem: Any): Any? {
            val bundle = bundleOf()
            when {
                oldItem is Book && newItem is Book -> {
                    if (oldItem.name != newItem.name) {
                        bundle.putString("name", newItem.name)
                    }
                    if (oldItem.author != newItem.author) {
                        bundle.putString("author", newItem.author)
                    }
                    if (oldItem.durChapterTitle != newItem.durChapterTitle) {
                        bundle.putString("dur", newItem.durChapterTitle)
                    }
                    if (oldItem.latestChapterTitle != newItem.latestChapterTitle) {
                        bundle.putString("last", newItem.latestChapterTitle)
                    }
                    if (oldItem.wordCount != newItem.wordCount) {
                        bundle.putString("wordCount", newItem.wordCount)
                    }
                    if (oldItem.pinTime != newItem.pinTime) {
                        bundle.putLong("pinTime", newItem.pinTime)
                    }
                    if (oldItem.getDisplayCover() != newItem.getDisplayCover()) {
                        bundle.putString("cover", newItem.getDisplayCover())
                    }
                    if (oldItem.durChapterTime != newItem.durChapterTime) {
                        bundle.putBoolean("refresh", true)
                    }
                }

                oldItem is BookGroup && newItem is BookGroup -> {
                    if (oldItem.groupName != newItem.groupName) {
                        bundle.putString("groupName", newItem.groupName)
                    }
                    if (oldItem.cover != newItem.cover) {
                        bundle.putString("cover", newItem.cover)
                    }
                    if (oldItem.enableRefresh != newItem.enableRefresh || oldItem.onlyUpdateRead != newItem.onlyUpdateRead) {
                        bundle.putBoolean("unviewable", true)
                    }
                }
            }
            if (bundle.isEmpty) return null
            return bundle
        }
    }

    private val asyncListDiffer by lazy {
        AsyncListDiffer(this, diffItemCallback).apply {
            addListListener { _, _ ->
                currentGroupId?.let {
                    layoutManager?.onRestoreInstanceState(layoutStates[it])
                    layoutStates[it] = null
                }
            }
        }
    }

    fun updateItems(groupId: Long) {
        currentGroupId?.let {
            layoutStates[it] = layoutManager?.onSaveInstanceState()
        }
        currentGroupId = groupId
        val items = callBack.getItems()
        if (items.size >= DIRECT_NOTIFY_THRESHOLD) {
            directItems = items.toList()
            notifyDataSetChanged()
            layoutManager?.onRestoreInstanceState(layoutStates[groupId])
            layoutStates[groupId] = null
        } else {
            directItems = null
            asyncListDiffer.submitList(items)
        }
    }

    fun notification(bookUrl: String) {
        for (i in 0 until itemCount) {
            getItem(i).let {
                if (it is Book && it.bookUrl == bookUrl) {
                    appDb.bookDao.getBook(bookUrl)?.let { dbBook ->
                        it.wordCount = dbBook.wordCount
                    }
                    notifyItemChanged(
                        i,
                        bundleOf(
                            Pair("wordCount", it.wordCount),
                            Pair("refresh", null)
                        )
                    )
                    return
                }
            }
        }
    }

    fun getItems() = directItems ?: asyncListDiffer.currentList

    fun getItem(position: Int) = getItems().getOrNull(position)

    override fun getItemCount(): Int {
        return getItems().size
    }

    override fun getItemViewType(position: Int): Int {
        if (getItem(position) is BookGroup) {
            return 1
        }
        return 0
    }

    final override fun onBindViewHolder(holder: VH, position: Int) {}


    interface CallBack {
        fun onItemClick(item: Any)
        fun onItemLongClick(item: Any)
        fun isUpdate(bookUrl: String): Boolean
        fun getItems(): List<Any>
    }
}
