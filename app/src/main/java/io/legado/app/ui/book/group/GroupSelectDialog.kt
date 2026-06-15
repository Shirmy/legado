package io.legado.app.ui.book.group

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.DialogBookGroupPickerBinding
import io.legado.app.databinding.ItemGroupSelectBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyTint
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch


class GroupSelectDialog() : BaseDialogFragment(R.layout.dialog_book_group_picker),
    Toolbar.OnMenuItemClickListener {

    constructor(
        groupId: Long,
        requestCode: Int = -1,
        onlyUserGroups: Boolean = false,
        includeLocalGroups: Boolean = false,
        includeLocalNone: Boolean = false,
        singleSelect: Boolean = false
    ) : this() {
        arguments = Bundle().apply {
            putLong("groupId", groupId)
            putInt("requestCode", requestCode)
            putBoolean("onlyUserGroups", onlyUserGroups)
            putBoolean("includeLocalGroups", includeLocalGroups)
            putBoolean("includeLocalNone", includeLocalNone)
            putBoolean("singleSelect", singleSelect)
        }
    }

    private val binding by viewBinding(DialogBookGroupPickerBinding::bind)
    private var requestCode: Int = -1
    private val viewModel: GroupViewModel by viewModels()
    private val adapter by lazy { GroupAdapter(requireContext()) }
    private val callBack get() = (activity as? CallBack)
    private var groupId: Long = 0
    private var onlyUserGroups: Boolean = false
    private var includeLocalGroups: Boolean = false
    private var includeLocalNone: Boolean = false
    private var singleSelect: Boolean = false

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        arguments?.let {
            groupId = it.getLong("groupId")
            requestCode = it.getInt("requestCode", -1)
            onlyUserGroups = it.getBoolean("onlyUserGroups")
            includeLocalGroups = it.getBoolean("includeLocalGroups")
            includeLocalNone = it.getBoolean("includeLocalNone")
            singleSelect = it.getBoolean("singleSelect")
        }
        initView()
        initData()
    }

    private fun initView() {
        binding.toolBar.title = getString(R.string.group_select)
        binding.toolBar.inflateMenu(R.menu.book_group_manage)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener(this)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.adapter = adapter
        val itemTouchCallback = ItemTouchCallback(adapter)
        itemTouchCallback.isCanDrag = true
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(binding.recyclerView)
        binding.tvCancel.setOnClickListener {
            dismissAllowingStateLoss()
        }
        binding.tvOk.setTextColor(requireContext().accentColor)
        binding.tvOk.setOnClickListener {
            if (includeLocalGroups) {
                (activity as? LocalGroupCallBack)?.upLocalGroup(requestCode, groupId, includeLocalNone)
                    ?: callBack?.upGroup(requestCode, groupId)
            } else {
                callBack?.upGroup(requestCode, groupId)
            }
            dismissAllowingStateLoss()
        }
    }

    private fun initData() {
        lifecycleScope.launch {
            val groupFlow = if (includeLocalGroups) {
                appDb.bookGroupDao.flowAll()
            } else {
                appDb.bookGroupDao.flowSelect()
            }
            groupFlow.conflate().collect {
                val groups = when {
                    includeLocalGroups -> it.filter { group ->
                        group.groupId == BookGroup.IdLocal ||
                                group.groupId == BookGroup.IdLocalNone ||
                                group.groupId > 0
                    }
                    onlyUserGroups -> it.filter { group -> group.groupId > 0 }
                    else -> it
                }
                if (singleSelect && groupId > 0 && groups.none { group -> group.groupId == groupId }) {
                    groupId = groups.firstOrNull { group ->
                        group.groupId > 0 && group.groupId and groupId > 0
                    }?.groupId ?: 0L
                }
                adapter.setItems(groups)
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_add -> showDialogFragment(
                GroupEditDialog()
            )
        }
        return true
    }

    private inner class GroupAdapter(context: Context) :
        RecyclerAdapter<BookGroup, ItemGroupSelectBinding>(context),
        ItemTouchCallback.Callback {

        private var isMoved: Boolean = false

        override fun getViewBinding(parent: ViewGroup): ItemGroupSelectBinding {
            return ItemGroupSelectBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemGroupSelectBinding,
            item: BookGroup,
            payloads: MutableList<Any>
        ) {
            binding.run {
                root.setBackgroundColor(context.backgroundColor)
                cbGroup.text = item.groupName
                cbGroup.isChecked = isGroupChecked(item.groupId)
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemGroupSelectBinding) {
            binding.run {
                cbGroup.setOnUserCheckedChangeListener { isChecked ->
                    getItem(holder.layoutPosition)?.let {
                        if (includeLocalGroups && it.groupId == BookGroup.IdLocal) {
                            groupId = if (isChecked) BookGroup.IdLocal else 0L
                            includeLocalNone = false
                        } else if (includeLocalGroups && it.groupId == BookGroup.IdLocalNone) {
                            includeLocalNone = isChecked
                            if (isChecked && groupId == BookGroup.IdLocal) {
                                groupId = 0L
                            }
                        } else if (singleSelect) {
                            groupId = if (isChecked) it.groupId else 0L
                            adapter.notifyItemRangeChanged(0, adapter.itemCount)
                        } else {
                            val oldGroupId = groupId.takeIf { id -> id > 0 } ?: 0L
                            groupId = if (isChecked) {
                                oldGroupId + it.groupId
                            } else {
                                oldGroupId - it.groupId
                            }
                        }
                        if (includeLocalGroups) {
                            adapter.notifyItemRangeChanged(0, adapter.itemCount)
                        }
                    }
                }
                tvEdit.setOnClickListener {
                    showDialogFragment(
                        GroupEditDialog(getItem(holder.layoutPosition))
                    )
                }
            }
        }

        override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
            swapItem(srcPosition, targetPosition)
            isMoved = true
            return true
        }

        override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            if (isMoved) {
                for ((index, item) in getItems().withIndex()) {
                    item.order = index + 1
                }
                viewModel.upGroup(*getItems().toTypedArray())
            }
            isMoved = false
        }

        private fun isGroupChecked(itemGroupId: Long): Boolean {
            return when {
                includeLocalGroups && itemGroupId == BookGroup.IdLocal -> groupId == BookGroup.IdLocal
                includeLocalGroups && itemGroupId == BookGroup.IdLocalNone -> includeLocalNone
                includeLocalGroups && groupId < 0 -> false
                else -> (groupId and itemGroupId) > 0
            }
        }
    }

    interface CallBack {
        fun upGroup(requestCode: Int, groupId: Long)
    }

    interface LocalGroupCallBack {
        fun upLocalGroup(requestCode: Int, groupId: Long, includeLocalNone: Boolean)
    }
}
