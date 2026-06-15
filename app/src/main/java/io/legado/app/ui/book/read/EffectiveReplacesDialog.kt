package io.legado.app.ui.book.read

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.Item1lineTextAndCloseBinding
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.searchContent.SearchContentViewModel
import io.legado.app.ui.replace.edit.ReplaceEditActivity
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 璧锋晥鐨勬浛鎹㈣鍒? */
class EffectiveReplacesDialog : BaseDialogFragment(R.layout.dialog_recycler_view) {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val viewModel by activityViewModels<ReadBookViewModel>()
    private val adapter by lazy { ReplaceAdapter(requireContext()) }
    private val chineseConvert by lazy { ReplaceRule(0, "绻佺畝杞崲") }

    private var isEdit = false
    private var scanJob: Job? = null

    private val editActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == AppCompatActivity.RESULT_OK) {
                isEdit = true
            }
        }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.run {
            toolBar.setBackgroundColor(primaryColor)
            toolBar.setTitle(R.string.effective_replaces)
            recyclerView.layoutManager = LinearLayoutManager(requireContext())
            recyclerView.adapter = adapter
        }
        if (AppConfig.effectiveReplaceFull) {
            loadFullEffectiveReplaces()
        } else {
            val effectiveReplaceRules = ReadBook.curTextChapter?.effectiveReplaceRules ?: emptyList()
            setItems(effectiveReplaceRules)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        scanJob?.cancel()
        super.onDismiss(dialog)
        if (isEdit) {
            (activity as? ReadBookActivity)?.onReplaceRuleGlobalStateChanged()
                ?: viewModel.replaceRuleChanged()
        }
    }
    
    private fun showChineseConvertAlert() {
        alert(titleResource = R.string.chinese_converter) {
            items(resources.getStringArray(R.array.chinese_mode).toList()) { _, i ->
                if (AppConfig.chineseConverterType != i) {
                    AppConfig.chineseConverterType = i
                    isEdit = true
                }
            }
        }
    }

    private fun loadFullEffectiveReplaces() {
        binding.toolBar.title = "${getString(R.string.effective_replaces)}..."
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            val book = ReadBook.book ?: return@launch
            val cacheKey = withContext(IO) {
                val processor = ContentProcessor.get(book)
                val replaceRules = processor.getContentReplaceRules()
                EffectiveReplaceFullCache.buildKey(book, replaceRules)
            }
            viewModel.startEffectiveReplaceFullScan(book)
            viewModel.effectiveReplaceScanState.collect { scanState ->
                if (scanState?.key != cacheKey) {
                    return@collect
                }
                val state = scanState.state
                binding.toolBar.title =
                    if (state.completed) {
                        "${getString(R.string.effective_replaces)} 完成(${state.rules.size})"
                    } else {
                        "${getString(R.string.effective_replaces)} ${state.scannedCount}/${state.totalCount}(${state.rules.size})"
                    }
                if (state.completed || adapter.getItems().map { it.id } != state.rules.map { it.id }) {
                    setItems(state.rules)
                }
                if (state.completed && state.rules.isEmpty()) {
                    ReadBook.curTextChapter?.effectiveReplaceRules?.takeIf { it.isNotEmpty() }?.let {
                        setItems(it)
                    }
                }
            }
        }
    }
    private fun setItems(effectiveReplaceRules: List<ReplaceRule>) {
        if (AppConfig.chineseConverterType > 0) {
            adapter.setItems(effectiveReplaceRules + chineseConvert)
        } else {
            adapter.setItems(effectiveReplaceRules)
        }
    }

    private inner class ReplaceAdapter(context: Context) :
        RecyclerAdapter<ReplaceRule, Item1lineTextAndCloseBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): Item1lineTextAndCloseBinding {
            return Item1lineTextAndCloseBinding.inflate(inflater, parent, false)
        }

        override fun registerListener(holder: ItemViewHolder, binding: Item1lineTextAndCloseBinding) {
            binding.root.setOnClickListener {
                getCurrentItem(holder)?.let { item ->
                    if (item == chineseConvert) {
                        showChineseConvertAlert()
                        return@let
                    }
                    if (item.pattern.isNotBlank()) {
                        SearchContentViewModel.regexReplace = item.isRegex
                        scanJob?.cancel()
                        val readBookActivity = activity as? ReadBookActivity
                        dismissAllowingStateLoss()
                        readBookActivity?.openSearchActivity(item.pattern, item.id, item.name)
                    }
                }
            }
            binding.root.setOnLongClickListener {
                getCurrentItem(holder)?.let { item ->
                    if (item == chineseConvert) {
                        return@setOnLongClickListener true
                    }
                    scanJob?.cancel()
                    editActivity.launch(ReplaceEditActivity.startIntent(requireContext(), item.id))
                }
                true
            }
            binding.icClose.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    return@setOnClickListener
                }
                getItem(position)?.let { item ->
                    scanJob?.cancel()
                    removeItem(position)
                    if (item == chineseConvert) {
                        AppConfig.chineseConverterType = 0
                        isEdit = true
                        return@let
                    }
                    item.isEnabled = false
                    appDb.replaceRuleDao.insert(item)
                    isEdit = true
                }
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: Item1lineTextAndCloseBinding,
            item: ReplaceRule,
            payloads: MutableList<Any>
        ) {
            val position = holder.layoutPosition
            binding.textView.text = if (position != RecyclerView.NO_POSITION && item != chineseConvert) {
                "${position + 1}. ${item.name}"
            } else {
                item.name
            }
        }

        private fun getCurrentItem(holder: ItemViewHolder): ReplaceRule? {
            val position = holder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) {
                return null
            }
            return getItem(position)
        }

    }

}
