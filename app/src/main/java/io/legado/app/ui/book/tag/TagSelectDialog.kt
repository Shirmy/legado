package io.legado.app.ui.book.tag

import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookTag
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.onClick

class TagSelectDialog() : BaseDialogFragment(R.layout.dialog_tag_select) {

    constructor(
        selectedTagIds: List<Long> = emptyList(),
        mode: Mode = Mode.MULTI_SELECT,
        callback: ((List<Long>) -> Unit)? = null
    ) : this() {
        arguments = Bundle().apply {
            putLongArray("selectedTagIds", selectedTagIds.toLongArray())
            putString("mode", mode.name)
        }
        this.callback = callback
    }

    enum class Mode { MULTI_SELECT, SINGLE_SELECT }

    private var callback: ((List<Long>) -> Unit)? = null
    private val selectedIds = linkedSetOf<Long>()
    private var tags: List<BookTag> = emptyList()
    private var filterKey: String = ""
    private var isSavingInput = false

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.85f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val mode = Mode.valueOf(arguments?.getString("mode") ?: Mode.MULTI_SELECT.name)
        selectedIds.clear()
        selectedIds.addAll(arguments?.getLongArray("selectedTagIds")?.toList() ?: emptyList())

        val container = view.findViewById<GridLayout>(R.id.ll_tag_list) ?: return
        val filterEdit = view.findViewById<EditText>(R.id.edit_tag_filter)
        view.findViewById<View>(R.id.toolbar)?.setBackgroundColor(primaryColor)

        filterEdit?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterKey = s?.toString()?.trim().orEmpty()
                renderTags(container, mode)
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
        filterEdit?.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                saveInputAndConfirm(filterEdit, mode)
                true
            } else {
                false
            }
        }

        view.findViewById<View>(R.id.btn_ok)?.onClick {
            saveInputAndConfirm(filterEdit, mode)
        }

        view.findViewById<View>(R.id.btn_cancel)?.onClick {
            dismiss()
        }

        view.findViewById<View>(R.id.btn_add)?.visibility = View.GONE

        lifecycleScope.launch {
            tags = withContext(IO) { appDb.bookTagDao.getAll() }
            renderTags(container, mode)
            if (tags.isEmpty()) {
                toastOnUi("暂无标签，可直接新建")
            }
        }
    }

    private fun renderTags(container: GridLayout, mode: Mode) {
        container.removeAllViews()
        val showTags = if (filterKey.isBlank()) {
            tags
        } else {
            tags.filter { it.name.contains(filterKey, ignoreCase = true) }
        }
        showTags.forEach { tag ->
            val cb = CheckBox(requireContext()).apply {
                text = tag.name
                this.tag = tag.id
                isChecked = tag.id in selectedIds
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(4, 4, 4, 4)
                }
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        if (mode == Mode.SINGLE_SELECT) {
                            selectedIds.clear()
                            for (i in 0 until container.childCount) {
                                val child = container.getChildAt(i) as? CheckBox
                                if (child != this) child?.isChecked = false
                            }
                        }
                        selectedIds.add(tag.id)
                    } else {
                        selectedIds.remove(tag.id)
                    }
                }
            }
            container.addView(cb)
        }
    }

    private suspend fun saveInputTagIfNeeded(name: String, mode: Mode): List<Long> {
        val names = splitTagNames(name)
        if (names.isEmpty()) return selectedIds.toList()
        val savedTags = withContext(IO) { saveTags(names) }
        if (mode == Mode.SINGLE_SELECT) {
            selectedIds.clear()
            savedTags.firstOrNull()?.let { selectedIds.add(it.id) }
        } else {
            selectedIds.addAll(savedTags.map { it.id })
        }
        return selectedIds.toList()
    }

    private fun saveInputAndConfirm(filterEdit: EditText?, mode: Mode) {
        if (isSavingInput) return
        isSavingInput = true
        val inputName = filterEdit?.text?.toString()?.trim().orEmpty()
        lifecycleScope.launch {
            try {
                val checkedIds = saveInputTagIfNeeded(inputName, mode)
                callback?.invoke(checkedIds)
                dismiss()
            } finally {
                isSavingInput = false
            }
        }
    }

    private fun splitTagNames(text: String): List<String> {
        return text.trim()
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun saveTags(names: List<String>): List<BookTag> {
        var order = appDb.bookTagDao.maxOrder
        return names.mapNotNull { name ->
            appDb.bookTagDao.getByName(name) ?: run {
                val tag = BookTag(name = name, order = ++order)
                appDb.bookTagDao.insert(tag)
                appDb.bookTagDao.getByName(name)
            }
        }
    }

    private fun showAddTagDialog(container: GridLayout, mode: Mode, defaultName: String) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.tag_name)
            setText(defaultName)
            setSelection(text?.length ?: 0)
        }
        alert(titleResource = R.string.new_tag) {
            customView { input }
            okButton {
                val names = splitTagNames(input.text.toString())
                if (names.isEmpty()) return@okButton
                lifecycleScope.launch {
                    val savedTags = withContext(IO) { saveTags(names) }
                    if (mode == Mode.SINGLE_SELECT) {
                        selectedIds.clear()
                        savedTags.firstOrNull()?.let { selectedIds.add(it.id) }
                    } else {
                        selectedIds.addAll(savedTags.map { it.id })
                    }
                    tags = withContext(IO) { appDb.bookTagDao.getAll() }
                    filterKey = ""
                    view?.findViewById<EditText>(R.id.edit_tag_filter)?.setText("")
                    renderTags(container, mode)
                }
            }
            cancelButton()
        }
    }
}
