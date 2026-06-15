package io.legado.app.ui.book.tag

import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookTag
import io.legado.app.help.book.BookTagSort
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.getInt
import io.legado.app.utils.setLayout
import io.legado.app.utils.putInt
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.onClick

class TagManageDialog() : BaseDialogFragment(R.layout.dialog_tag_manage) {

    private var tags: List<BookTag> = emptyList()
    private var tagCounts: Map<Long, Int> = emptyMap()
    private var filterKey: String = ""
    private var sortMode: SortMode = SortMode.from(LocalConfig.getInt(TAG_SORT_KEY))

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.toolbar)?.setBackgroundColor(primaryColor)
        val container = view.findViewById<GridLayout>(R.id.ll_tag_list) ?: return
        val filterEdit = view.findViewById<EditText>(R.id.edit_tag_filter)
        val sortGroup = view.findViewById<RadioGroup>(R.id.rg_tag_sort)
        sortGroup?.check(
            when (sortMode) {
                SortMode.NAME -> R.id.rb_tag_sort_name
                SortMode.COUNT -> R.id.rb_tag_sort_count
            }
        )
        sortGroup?.setOnCheckedChangeListener { _, checkedId ->
            val newSortMode = when (checkedId) {
                R.id.rb_tag_sort_count -> SortMode.COUNT
                else -> SortMode.NAME
            }
            if (sortMode != newSortMode) {
                sortMode = newSortMode
                LocalConfig.putInt(TAG_SORT_KEY, sortMode.value)
                renderTags(container)
            }
        }
        filterEdit?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterKey = s?.toString()?.trim().orEmpty()
                renderTags(container)
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
        view.findViewById<View>(R.id.btn_add)?.visibility = View.GONE
        view.findViewById<View>(R.id.btn_cancel)?.onClick { dismiss() }
        refreshTags(container)
    }

    private fun refreshTags(container: GridLayout) {
        lifecycleScope.launch {
            val (newTags, newCounts) = withContext(IO) {
                appDb.bookTagDao.getAll() to appDb.bookTagRefDao.getTagCounts()
                    .associate { it.tagId to it.count }
            }
            tags = newTags
            tagCounts = newCounts
            renderTags(container)
        }
    }

    private fun renderTags(container: GridLayout) {
        container.removeAllViews()
        val showTags = if (filterKey.isBlank()) {
            tags
        } else {
            tags.filter { it.name.contains(filterKey, ignoreCase = true) }
        }.sortByMode()
        showTags.forEach { tag ->
            val tagView = TextView(requireContext()).apply {
                text = "${tag.name} (${tag.bookCount})"
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(12, 12, 12, 12)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(4, 4, 4, 4)
                }
            }
            tagView.onClick {
                showEditTagDialog(tag) { refreshTags(container) }
            }
            container.addView(tagView)
        }
    }

    private fun List<BookTag>.sortByMode(): List<BookTag> {
        return when (sortMode) {
            SortMode.NAME -> this
            SortMode.COUNT -> sortedWith { left, right ->
                right.bookCount.compareTo(left.bookCount).takeIf { it != 0 }
                    ?: compareByName(left, right)
            }
        }
    }

    private fun compareByName(left: BookTag, right: BookTag): Int {
        val sorted = BookTagSort.sort(listOf(left, right))
        return when (sorted.firstOrNull()?.id) {
            left.id -> -1
            right.id -> 1
            else -> 0
        }
    }

    private val BookTag.bookCount: Int
        get() = tagCounts[id] ?: 0

    private fun showAddTagDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.tag_name)
        }
        alert(titleResource = R.string.new_tag) {
            customView { input }
            okButton {
                val names = splitTagNames(input.text.toString())
                if (names.isNotEmpty()) {
                    lifecycleScope.launch {
                        val createdCount = withContext(IO) {
                            var order = appDb.bookTagDao.maxOrder
                            var count = 0
                            names.forEach { name ->
                                if (appDb.bookTagDao.getByName(name) == null) {
                                    appDb.bookTagDao.insert(BookTag(name = name, order = ++order))
                                    count++
                                }
                            }
                            count
                        }
                        if (createdCount == 0) {
                            toastOnUi("标签名称已存在")
                        }
                        refreshTags(view?.findViewById<GridLayout>(R.id.ll_tag_list)!!)
                    }
                }
            }
            cancelButton()
        }
    }

    private fun splitTagNames(text: String): List<String> {
        return text.trim()
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun showEditTagDialog(
        tag: BookTag,
        onUpdated: () -> Unit
    ) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.tag_name)
            setText(tag.name)
            setSelection(text?.length ?: 0)
        }
        alert(title = tag.name) {
            customView { input }
            neutralButton(R.string.delete) {
                showDeleteTagDialog(tag, onUpdated)
            }
            positiveButton("保存") {
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && name != tag.name) {
                    lifecycleScope.launch {
                        withContext(IO) {
                            val exists = appDb.bookTagDao.getByName(name)
                            if (exists != null) {
                                appDb.bookTagRefDao.mergeTag(tag.id, exists.id)
                                appDb.bookTagDao.delete(tag)
                            } else {
                                tag.name = name
                                appDb.bookTagDao.update(tag)
                            }
                        }
                        onUpdated()
                    }
                }
            }
        }
    }

    private fun showDeleteTagDialog(
        tag: BookTag,
        onDeleted: () -> Unit
    ) {
        alert(titleResource = R.string.delete, messageResource = R.string.sure_del) {
            yesButton {
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.bookTagRefDao.deleteByTag(tag.id)
                        appDb.bookTagDao.delete(tag)
                    }
                    onDeleted()
                }
            }
            noButton()
        }
    }

    private enum class SortMode(val value: Int) {
        NAME(0),
        COUNT(1);

        companion object {
            fun from(value: Int): SortMode {
                return values().firstOrNull { it.value == value } ?: NAME
            }
        }
    }

    companion object {
        private const val TAG_SORT_KEY = "tagManageSort"
    }
}
