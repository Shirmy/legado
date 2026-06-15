package io.legado.app.ui.book.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.databinding.ActivityBookSearchBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.help.book.isLocal
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarMargin
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.transaction
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import kotlin.math.abs

class SearchActivity : VMBaseActivity<ActivityBookSearchBinding, SearchViewModel>(),
    BookAdapter.CallBack,
    HistoryKeyAdapter.CallBack,
    SearchScopeDialog.Callback,
    SearchAdapter.CallBack,
    GroupSelectDialog.CallBack,
    GroupSelectDialog.LocalGroupCallBack {

    override val binding by viewBinding(ActivityBookSearchBinding::inflate)
    override val viewModel by viewModels<SearchViewModel>()

    private val adapter by lazy { SearchAdapter(this, this) }
    private val bookAdapter by lazy {
        BookAdapter(this, this).apply {
            setHasStableIds(true)
        }
    }
    private val historyKeyAdapter by lazy {
        HistoryKeyAdapter(this, this).apply {
            setHasStableIds(true)
        }
    }
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private var menu: Menu? = null
    private var groups: List<String>? = null
    private var historyFlowJob: Job? = null
    private var booksFlowJob: Job? = null
    private var precisionSearchMenuItem: MenuItem? = null
    private var isManualStopSearch = false
    private var localOnly = false
    private var localSearchMode = LOCAL_SEARCH_ALL
    private var localSearchGroupId = BookGroup.IdLocal
    private var localSearchIncludeLocalNone = false
    private val bookInfoActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            clearSearchState()
        }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        localOnly = intent.getBooleanExtra("localOnly", false)
        binding.llInputHelp.setBackgroundColor(backgroundColor)
        initRecyclerView()
        initSearchView()
        initOtherView()
        initData()
        receiptIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiptIntent(intent)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_search, menu)
        this.menu = menu
        precisionSearchMenuItem = menu.findItem(R.id.menu_precision_search)
        precisionSearchMenuItem?.isChecked = getPrefBoolean(PreferKey.precisionSearch)
        menu.findItem(R.id.menu_source_manage)?.isVisible = false
        menu.findItem(R.id.menu_search_scope)?.isVisible = false
        if (localOnly) {
            precisionSearchMenuItem?.isVisible = false
            menu.setGroupVisible(R.id.menu_group_local_search, true)
            menu.findItem(R.id.menu_select_group)?.isVisible = true
            menu.findItem(localSearchMenuId(localSearchMode))?.isChecked = true
            upLocalGroupMenuTitle(menu.findItem(R.id.menu_select_group))
        }
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        if (localOnly) {
            menu.removeGroup(R.id.menu_group_1)
            menu.removeGroup(R.id.menu_group_2)
            return super.onMenuOpened(featureId, menu)
        }
        menu.transaction {
            menu.removeGroup(R.id.menu_group_1)
            menu.removeGroup(R.id.menu_group_2)
            var hasChecked = false
            val searchScopeNames = viewModel.searchScope.displayNames
            if (viewModel.searchScope.isSource()) {
                menu.add(R.id.menu_group_1, Menu.NONE, Menu.NONE, searchScopeNames.first()).apply {
                    isChecked = true
                    hasChecked = true
                }
            }
            groups?.forEach {
                if (searchScopeNames.contains(it)) {
                    menu.add(R.id.menu_group_1, Menu.NONE, Menu.NONE, it).apply {
                        isChecked = true
                        hasChecked = true
                    }
                } else {
                    menu.add(R.id.menu_group_2, Menu.NONE, Menu.NONE, it)
                }
            }
            if (!hasChecked) {
                viewModel.searchScope.update("")
            }
            menu.setGroupCheckable(R.id.menu_group_1, true, false)
            menu.setGroupCheckable(R.id.menu_group_2, true, true)
        }
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_precision_search -> {
                putPrefBoolean(
                    PreferKey.precisionSearch,
                    !getPrefBoolean(PreferKey.precisionSearch)
                )
                precisionSearchMenuItem?.isChecked = getPrefBoolean(PreferKey.precisionSearch)
                searchView.query?.toString()?.trim()?.let {
                    searchView.setQuery(it, true)
                }
            }

            R.id.menu_search_scope -> alertSearchScope()
            R.id.menu_source_manage -> startActivity<BookSourceActivity>()
            R.id.menu_log -> showDialogFragment(AppLogDialog())
            R.id.menu_search_all -> changeLocalSearchMode(LOCAL_SEARCH_ALL, item)
            R.id.menu_search_name -> changeLocalSearchMode(LOCAL_SEARCH_NAME, item)
            R.id.menu_search_author -> changeLocalSearchMode(LOCAL_SEARCH_AUTHOR, item)
            R.id.menu_search_tag -> changeLocalSearchMode(LOCAL_SEARCH_TAG, item)
            R.id.menu_select_group -> showDialogFragment(
                GroupSelectDialog(
                    localSearchGroupId,
                    includeLocalGroups = true,
                    includeLocalNone = localSearchIncludeLocalNone
                )
            )
            R.id.menu_1 -> viewModel.searchScope.update("")
            else -> {
                if (item.groupId == R.id.menu_group_1) {
                    viewModel.searchScope.remove(item.title.toString())
                } else if (item.groupId == R.id.menu_group_2) {
                    viewModel.searchScope.update(item.title.toString())
                }
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.search_book_key)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                searchView.clearFocus()
                query.trim().let { searchKey ->
                    if (localOnly) {
                        upHistory(searchKey)
                        visibleInputHelp(true)
                    } else {
                        isManualStopSearch = false
                        viewModel.saveSearchKey(searchKey)
                        viewModel.searchKey = ""
                        viewModel.search(searchKey)
                        visibleInputHelp(false)
                    }
                }
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                viewModel.stop()
                binding.fbStartStop.invisible()
                upHistory(newText.trim())
                return false
            }
        })
        searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
            if (binding.refreshProgressBar.isAutoLoading || (!hasFocus && adapter.isNotEmpty() && searchView.query.isNotBlank())) {
                visibleInputHelp(false)
            } else {
                visibleInputHelp(true)
            }
        }
        visibleInputHelp(true)
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.rvBookshelfSearch.setEdgeEffectColor(primaryColor)
        binding.rvHistoryKey.setEdgeEffectColor(primaryColor)
        binding.rvBookshelfSearch.layoutManager = FlexboxLayoutManager(this)
        binding.rvBookshelfSearch.adapter = bookAdapter
        binding.rvBookshelfSearch.applyNavigationBarMargin()
        binding.rvHistoryKey.layoutManager = FlexboxLayoutManager(this)
        binding.rvHistoryKey.adapter = historyKeyAdapter
        binding.rvHistoryKey.applyNavigationBarMargin()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.applyNavigationBarPadding()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                if (positionStart == 0) {
                    binding.recyclerView.scrollToPosition(0)
                }
            }

            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                super.onItemRangeMoved(fromPosition, toPosition, itemCount)
                if (toPosition == 0) {
                    binding.recyclerView.scrollToPosition(0)
                }
            }
        })
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (!recyclerView.canScrollVertically(1)) {
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val lastPosition = layoutManager.findLastCompletelyVisibleItemPosition()
                    if (lastPosition == RecyclerView.NO_POSITION) {
                        return
                    }
                    val lastView = layoutManager.findViewByPosition(lastPosition)
                    if (lastView == null) {
                        scrollToBottom()
                        return
                    }
                    val bottom =
                        abs(lastView.bottom - recyclerView.height) - recyclerView.paddingBottom
                    if (bottom <= 1) {
                        scrollToBottom()
                    }
                }
            }
        })
    }

    private fun initOtherView() {
        binding.fbStartStop.backgroundTintList =
            Selector.colorBuild()
                .setDefaultColor(accentColor)
                .setPressedColor(ColorUtils.darkenColor(accentColor))
                .create()
        binding.fbStartStop.setOnClickListener {
            if (localOnly) {
                return@setOnClickListener
            }
            if (viewModel.isSearchLiveData.value == true) {
                isManualStopSearch = true
                viewModel.stop()
                binding.refreshProgressBar.isAutoLoading = false
            } else {
                viewModel.search("")
            }
        }
        binding.fbStartStop.applyNavigationBarMargin(true)
        binding.tvClearHistory.setOnClickListener { alertClearHistory() }
        if (localOnly) {
            binding.fbStartStop.gone()
            binding.refreshProgressBar.gone()
            binding.tvHistory.gone()
            binding.tvClearHistory.gone()
            binding.rvHistoryKey.gone()
        }
    }

    private fun initData() {
        viewModel.searchScope.stateLiveData.observe(this) {
            if (!binding.llInputHelp.isVisible) {
                searchView.query?.toString()?.trim()?.let {
                    searchView.setQuery(it, true)
                }
            }
        }
        viewModel.isSearchLiveData.observe(this) {
            if (it) {
                startSearch()
            } else {
                searchFinally()
            }
        }
        viewModel.searchBookLiveData.observe(this) {
            adapter.setItems(it)
        }
        lifecycleScope.launch {
            appDb.bookSourceDao.flowEnabledGroups().collect {
                groups = it
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.resume()
                try {
                    awaitCancellation()
                } finally {
                    viewModel.pause()
                }
            }
        }
    }

    /**
     * 处理传入数据
     */
    private fun receiptIntent(intent: Intent? = null) {
        localOnly = intent?.getBooleanExtra("localOnly", false) == true
        localSearchMode = intent?.getIntExtra("localSearchMode", localSearchMode)
            ?: localSearchMode
        val savedGroupId = getPrefLong(PreferKey.localBookSearchGroupId, BookGroup.IdLocal)
        localSearchGroupId = normalizeLocalSearchGroup(
            savedGroupId
        )
        localSearchIncludeLocalNone = savedGroupId == BookGroup.IdLocalNone ||
                getPrefBoolean(PreferKey.localBookSearchIncludeLocalNone)
        normalizeLocalSearchSelection()
        val searchScope = intent?.getStringExtra("searchScope")
        searchScope?.let {
            viewModel.searchScope.update(searchScope, postValue = false, save = false)
        }
        val key = intent?.getStringExtra("key")
        if (key.isNullOrBlank()) {
            searchView.findViewById<TextView>(androidx.appcompat.R.id.search_src_text)
                .requestFocus()
        } else {
            searchView.setQuery(key, true)
        }
    }

    /**
     * 滚动到底部事件
     */
    private fun scrollToBottom() {
        if (localOnly) {
            return
        }
        if (isManualStopSearch) {
            return
        }
        if (viewModel.isSearchLiveData.value == false
            && viewModel.searchKey.isNotEmpty()
            && viewModel.hasMore
        ) {
            viewModel.search("")
        }
    }

    /**
     * 打开关闭输入帮助
     */
    private fun visibleInputHelp(visible: Boolean) {
        if (visible) {
            upHistory(searchView.query.toString())
            binding.llInputHelp.visibility = VISIBLE
        } else {
            binding.llInputHelp.visibility = GONE
        }
    }

    /**
     * 更新搜索历史
     */
    private fun upHistory(key: String? = null) {
        booksFlowJob?.cancel()
        booksFlowJob = lifecycleScope.launch {
            if (key.isNullOrBlank()) {
                binding.tvBookShow.gone()
                binding.rvBookshelfSearch.gone()
            } else {
                val booksFlow = if (localOnly) {
                    if (localSearchGroupId == BookGroup.IdLocal && !localSearchIncludeLocalNone) {
                        when (localSearchMode) {
                            LOCAL_SEARCH_NAME -> appDb.bookDao.flowSearchLocalByName(key)
                            LOCAL_SEARCH_AUTHOR -> appDb.bookDao.flowSearchLocalByAuthor(key)
                            LOCAL_SEARCH_TAG -> appDb.bookDao.flowSearchLocalByTag(key)
                            else -> appDb.bookDao.flowSearchLocal(key)
                        }
                    } else {
                        localSearchGroupFlow().map { list ->
                            list.filter { book ->
                                book.isLocal && when (localSearchMode) {
                                    LOCAL_SEARCH_NAME -> book.name.contains(key)
                                    LOCAL_SEARCH_AUTHOR -> book.author.contains(key)
                                    LOCAL_SEARCH_TAG -> appDb.bookTagRefDao.getTagsByBook(book.bookUrl)
                                        .any { it.name.contains(key) }
                                    else -> book.name.contains(key) ||
                                            book.author.contains(key) ||
                                            book.originName.contains(key) ||
                                            appDb.bookTagRefDao.getTagsByBook(book.bookUrl)
                                                .any { it.name.contains(key) }
                                }
                            }
                        }
                    }
                } else {
                    appDb.bookDao.flowSearch(key)
                }
                booksFlow.flowOn(IO).conflate().collect {
                    if (it.isEmpty()) {
                        binding.tvBookShow.gone()
                        binding.rvBookshelfSearch.gone()
                    } else {
                        binding.tvBookShow.visible()
                        binding.rvBookshelfSearch.visible()
                    }
                    bookAdapter.setItems(it)
                }
            }
        }
        historyFlowJob?.cancel()
        if (localOnly) {
            return
        }
        historyFlowJob = lifecycleScope.launch {
            when {
                key.isNullOrBlank() -> appDb.searchKeywordDao.flowByTime()
                else -> appDb.searchKeywordDao.flowSearch(key)
            }.catch {
                AppLog.put("搜索界面获取搜索历史数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                historyKeyAdapter.setItems(it)
                if (it.isEmpty()) {
                    binding.tvClearHistory.invisible()
                } else {
                    binding.tvClearHistory.visible()
                }
            }
        }
    }

    /**
     * 开始搜索
     */
    private fun startSearch() {
        binding.refreshProgressBar.visible()
        binding.refreshProgressBar.isAutoLoading = true
        binding.fbStartStop.setImageResource(R.drawable.ic_stop_black_24dp)
        binding.fbStartStop.visible()
    }

    /**
     * 搜索结束
     */
    private fun searchFinally() {
        binding.refreshProgressBar.isAutoLoading = false
        binding.refreshProgressBar.gone()
        if (!isManualStopSearch && viewModel.hasMore) {
            binding.fbStartStop.setImageResource(R.drawable.ic_play_24dp)
        } else {
            binding.fbStartStop.invisible()
        }
    }

    override fun observeLiveBus() {
        viewModel.upAdapterLiveData.observe(this) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount, bundleOf(it to null))
        }
        viewModel.searchFinishLiveData.observe(this) { isEmpty ->
            if (!isEmpty || viewModel.searchScope.isAll()) return@observe
            alert("搜索结果为空") {
                val precisionSearch = appCtx.getPrefBoolean(PreferKey.precisionSearch)
                val displayScope = viewModel.searchScope.display
                if (precisionSearch) {
                    setMessage("${displayScope}分组搜索结果为空，是否关闭精准搜索？")
                    yesButton {
                        appCtx.putPrefBoolean(PreferKey.precisionSearch, false)
                        precisionSearchMenuItem?.isChecked = false
                        viewModel.searchKey = ""
                        viewModel.search(searchView.query.toString())
                    }
                } else {
                    setMessage("${displayScope}分组搜索结果为空，是否切换到全部分组？")
                    yesButton {
                        viewModel.searchScope.update("")
                    }
                }
                noButton()
            }
        }
    }

    /**
     * 显示书籍详情
     */
    override fun showBookInfo(name: String, author: String, bookUrl: String) {
        bookInfoActivity.launch(
            Intent(this, BookInfoActivity::class.java)
                .putExtra("name", name)
                .putExtra("author", author)
                .putExtra("bookUrl", bookUrl)
        )
    }

    private fun clearSearchState() {
        viewModel.clearSearchState()
        isManualStopSearch = false
        adapter.setItems(emptyList())
        bookAdapter.setItems(emptyList())
        binding.tvBookShow.gone()
        binding.rvBookshelfSearch.gone()
        binding.refreshProgressBar.isAutoLoading = false
        binding.refreshProgressBar.gone()
        binding.fbStartStop.invisible()
        searchView.setQuery("", false)
        visibleInputHelp(true)
    }

    /**
     * 是否已经加入书架
     */
    override fun isInBookshelf(book: SearchBook): Boolean {
        return viewModel.isInBookShelf(book)
    }

    /**
     * 显示书籍详情
     */
    override fun showBookInfo(book: Book) {
        showBookInfo(book.name, book.author, book.bookUrl)
    }

    /**
     * 点击历史关键字
     */
    override fun searchHistory(key: String) {
        lifecycleScope.launch {
            when {
                searchView.query.toString() == key -> {
                    searchView.setQuery(key, true)
                }

                withContext(IO) { appDb.bookDao.findByName(key).isEmpty() } -> {
                    searchView.setQuery(key, true)
                }

                else -> {
                    searchView.setQuery(key, false)
                }
            }
        }
    }

    /**
     * 删除搜索记录
     */
    override fun deleteHistory(searchKeyword: SearchKeyword) {
        viewModel.deleteHistory(searchKeyword)
    }


    override fun onSearchScopeOk(searchScope: SearchScope) {
        viewModel.searchScope.update(searchScope.toString())
    }

    private fun alertSearchScope() {
        showDialogFragment<SearchScopeDialog>()
    }

    private fun alertClearHistory() {
        alert(R.string.draw) {
            setMessage(R.string.sure_clear_search_history)
            yesButton {
                viewModel.clearHistory()
            }
            noButton()
        }
    }

    private fun changeLocalSearchMode(mode: Int, item: MenuItem) {
        localSearchMode = mode
        item.isChecked = true
        searchView.query?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
            upHistory(it)
        }
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        upLocalGroup(requestCode, groupId, false)
    }

    override fun upLocalGroup(requestCode: Int, groupId: Long, includeLocalNone: Boolean) {
        localSearchGroupId = normalizeLocalSearchGroup(groupId)
        localSearchIncludeLocalNone = includeLocalNone
        normalizeLocalSearchSelection()
        putPrefLong(PreferKey.localBookSearchGroupId, localSearchGroupId)
        putPrefBoolean(PreferKey.localBookSearchIncludeLocalNone, localSearchIncludeLocalNone)
        upLocalGroupMenuTitle(menu?.findItem(R.id.menu_select_group))
        searchView.query?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
            upHistory(it)
        }
    }

    private fun upLocalGroupMenuTitle(item: MenuItem?) {
        item ?: return
        lifecycleScope.launch {
            val names = mutableListOf<String>()
            if (localSearchGroupId == BookGroup.IdLocal && !localSearchIncludeLocalNone) {
                names.add(getLocalSearchGroupName(BookGroup.IdLocal))
            } else {
                if (localSearchIncludeLocalNone) {
                    names.add(getLocalSearchGroupName(BookGroup.IdLocalNone))
                }
                val groupName = if (localSearchGroupId > 0) {
                    withContext(IO) {
                        appDb.bookGroupDao.getGroupNames(localSearchGroupId).joinToString(",")
                            .ifBlank { appDb.bookGroupDao.getByID(localSearchGroupId)?.groupName.orEmpty() }
                    }
                } else {
                    ""
                }
                if (groupName.isNotBlank()) {
                    names.add(groupName)
                }
            }
            if (names.isEmpty()) {
                localSearchGroupId = BookGroup.IdLocal
                localSearchIncludeLocalNone = false
                putPrefLong(PreferKey.localBookSearchGroupId, localSearchGroupId)
                putPrefBoolean(PreferKey.localBookSearchIncludeLocalNone, false)
                names.add(getLocalSearchGroupName(BookGroup.IdLocal))
            }
            val name = names.joinToString(",")
            item.title = "${getString(R.string.group_select)}${name.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}"
        }
    }

    private suspend fun getLocalSearchGroupName(groupId: Long): String {
        val groupName = withContext(IO) {
            appDb.bookGroupDao.getByID(groupId)?.groupName.orEmpty()
        }
        return groupName.ifBlank {
            when (groupId) {
                BookGroup.IdLocal -> getString(R.string.local)
                BookGroup.IdLocalNone -> getString(R.string.local_no_group)
                else -> ""
            }
        }
    }

    private fun normalizeLocalSearchGroup(groupId: Long): Long {
        return groupId.takeIf {
            it == BookGroup.IdLocal || it > 0
        } ?: 0L
    }

    private fun normalizeLocalSearchSelection() {
        if (localSearchGroupId == BookGroup.IdLocal) {
            localSearchIncludeLocalNone = false
        } else if (localSearchGroupId <= 0L && !localSearchIncludeLocalNone) {
            localSearchGroupId = BookGroup.IdLocal
        }
    }

    private fun localSearchGroupFlow() = when {
        localSearchGroupId > 0 && localSearchIncludeLocalNone -> {
            appDb.bookDao.flowByGroup(localSearchGroupId)
                .combine(appDb.bookDao.flowByGroup(BookGroup.IdLocalNone)) { groupBooks, noneBooks ->
                    (groupBooks + noneBooks).distinctBy { it.bookUrl }
                }
        }
        localSearchGroupId > 0 -> appDb.bookDao.flowByGroup(localSearchGroupId)
        localSearchIncludeLocalNone -> appDb.bookDao.flowByGroup(BookGroup.IdLocalNone)
        else -> appDb.bookDao.flowByGroup(BookGroup.IdLocal)
    }

    override fun finish() {
        if (searchView.hasFocus()) {
            searchView.clearFocus()
            return
        }
        super.finish()
    }

    companion object {
        private const val LOCAL_SEARCH_ALL = 0
        private const val LOCAL_SEARCH_NAME = 1
        private const val LOCAL_SEARCH_AUTHOR = 2
        private const val LOCAL_SEARCH_TAG = 3

        private fun localSearchMenuId(mode: Int): Int {
            return when (mode) {
                LOCAL_SEARCH_NAME -> R.id.menu_search_name
                LOCAL_SEARCH_AUTHOR -> R.id.menu_search_author
                LOCAL_SEARCH_TAG -> R.id.menu_search_tag
                else -> R.id.menu_search_all
            }
        }

        fun start(context: Context, key: String?, searchScope: String? = null) {
            context.startActivity<SearchActivity> {
                putExtra("key", key)
                putExtra("searchScope", searchScope)
            }
        }

        fun startLocal(fragment: Fragment, key: String? = null, @Suppress("UNUSED_PARAMETER") groupId: Long = BookGroup.IdLocal) {
            fragment.startActivity<SearchActivity> {
                putExtra("key", key)
                putExtra("localOnly", true)
            }
        }

        fun startLocalAuthor(context: Context, key: String? = null) {
            context.startActivity<SearchActivity> {
                putExtra("key", key)
                putExtra("localOnly", true)
                putExtra("localSearchMode", LOCAL_SEARCH_AUTHOR)
            }
        }

        fun startLocalName(context: Context, key: String? = null) {
            context.startActivity<SearchActivity> {
                putExtra("key", key)
                putExtra("localOnly", true)
                putExtra("localSearchMode", LOCAL_SEARCH_NAME)
            }
        }

        fun start(context: Context, source: BookSource, key: String? = null) {
            context.startActivity<SearchActivity> {
                putExtra("key", key)
                putExtra("searchScope", SearchScope(source).toString())
            }
        }

        fun start(context: Context, source: BookSourcePart, key: String? = null) {
            context.startActivity<SearchActivity> {
                putExtra("key", key)
                putExtra("searchScope", SearchScope(source).toString())
            }
        }

    }
}
