package io.legado.app.ui.book.searchContent

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.core.view.allViews
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ActivitySearchContentBinding
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.widget.recycler.UpLinearLayoutManager
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarMargin
import io.legado.app.utils.applyTint
import io.legado.app.utils.invisible
import io.legado.app.utils.mapAsyncIndexed
import io.legado.app.utils.observeEvent
import io.legado.app.utils.showSoftInput
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect


class SearchContentActivity :
    VMBaseActivity<ActivitySearchContentBinding, SearchContentViewModel>(),
    SearchContentAdapter.Callback {

    override val binding by viewBinding(ActivitySearchContentBinding::inflate)
    override val viewModel by viewModels<SearchContentViewModel>()
    private val adapter by lazy { SearchContentAdapter(this, this) }
    private val mLayoutManager by lazy { UpLinearLayoutManager(this) }
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private var durChapterIndex = 0
    private var searchJob: Job? = null
    private var initJob: Job? = null
    private var hasReturnResult = false

    private val searchChapterThreads: Int
        get() = AppConfig.threadCount.coerceIn(1, 2)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val bbg = bottomBackground
        val btc = getPrimaryTextColor(ColorUtils.isColorLight(bbg))
        binding.llSearchBaseInfo.setBackgroundColor(bbg)
        binding.llSearchBaseInfo.applyNavigationBarMargin()
        binding.tvCurrentSearchInfo.setTextColor(btc)
        binding.ivSearchContentTop.setColorFilter(btc)
        binding.ivSearchContentBottom.setColorFilter(btc)
        val searchResultList = if (intent.getBooleanExtra("useSearchResultList", false)) {
            IntentData.get<List<SearchResult>>(
                intent.getStringExtra("searchResultListKey") ?: "searchResultList"
            )
        } else {
            null
        }
        val position = intent.getIntExtra("searchResultIndex", 0)
        val noSearchResult = searchResultList == null
        initSearchView(noSearchResult)
        initRecyclerView()
        initView()
        val bookUrl = intent.getStringExtra("bookUrl") ?: return
        viewModel.effectiveReplaceRuleId = intent.getLongExtra("effectiveReplaceRuleId", -1L)
            .takeIf { it >= 0L }
        viewModel.effectiveReplaceRuleName = intent.getStringExtra("effectiveReplaceRuleName")
        viewModel.initBook(bookUrl) {
            initSearchResultList(searchResultList, position)
            initBook(noSearchResult)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.content_search, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_enable_replace)?.isChecked = SearchContentViewModel.replaceEnabled
        menu.findItem(R.id.menu_enable_regex)?.isChecked = SearchContentViewModel.regexReplace
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_enable_replace -> {
                SearchContentViewModel.replaceEnabled = !SearchContentViewModel.replaceEnabled
                item.isChecked = SearchContentViewModel.replaceEnabled
            }
            R.id.menu_enable_regex -> {
                SearchContentViewModel.regexReplace = !SearchContentViewModel.regexReplace
                item.isChecked = SearchContentViewModel.regexReplace
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initSearchResultList(list: List<SearchResult>?, position: Int) {
        list ?: return
        viewModel.searchResultList.addAll(list)
        viewModel.searchResultCounts = list.size
        adapter.setItems(list)
        binding.recyclerView.scrollToPosition(position)
    }

    private fun initSearchView(requestFocus: Boolean) {
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.search)
        if (requestFocus) searchView.isIconified = false
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                startContentSearch(query.trim())
                searchView.clearFocus()
                return false
            }

            override fun onQueryTextChange(newText: String): Boolean {
                return false
            }
        })
    }

    private fun initRecyclerView() {
        binding.recyclerView.layoutManager = mLayoutManager
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.adapter = adapter
    }

    private fun initView() {
        binding.ivSearchContentTop.setOnClickListener {
            mLayoutManager.scrollToPositionWithOffset(0, 0)
        }
        binding.ivSearchContentBottom.setOnClickListener {
            if (adapter.itemCount > 0) {
                mLayoutManager.scrollToPositionWithOffset(adapter.itemCount - 1, 0)
            }
        }
        binding.tvCurrentSearchInfo.setOnClickListener {
            searchView.allViews.forEach { view ->
                if (view is EditText) {
                    view.showSoftInput()
                    return@setOnClickListener
                }
            }
        }
        binding.fbStop.setOnClickListener {
            searchJob?.cancel()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initBook(submit: Boolean = true) {
        binding.tvCurrentSearchInfo.text =
            this.getString(R.string.search_content_size) + ": ${viewModel.searchResultCounts}"
        viewModel.book?.let {
            initCacheFileNames(it)
            durChapterIndex = it.durChapterIndex
            intent.getStringExtra("searchWord")?.let { searchWord ->
                searchView.setQuery(searchWord, submit)
            }
        }
    }

    private fun initCacheFileNames(book: Book) {
        initJob = lifecycleScope.launch {
            withContext(IO) {
                viewModel.cacheChapterNames.addAll(BookHelp.getChapterFiles(book))
            }
            adapter.notifyItemRangeChanged(0, adapter.itemCount, true)
        }
    }

    override fun observeLiveBus() {
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.book?.bookUrl?.let { bookUrl ->
                if (book.bookUrl == bookUrl) {
                    viewModel.cacheChapterNames.add(chapter.getFileName())
                    adapter.notifyItemChanged(chapter.index, true)
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun startContentSearch(query: String) {
        if (query.isBlank()) return
        searchJob?.cancel()
        adapter.clearItems()
        viewModel.searchResultList.clear()
        viewModel.searchResultCounts = 0
        viewModel.lastQuery = query
        binding.refreshProgressBar.isAutoLoading = true
        binding.fbStop.visible()
        searchJob = lifecycleScope.launch(IO) {
            initJob?.join()
            val searchQuery = query
            val pendingSearchResults = arrayListOf<SearchResult>()
            fun flushPendingSearchResults() {
                if (pendingSearchResults.isEmpty()) {
                    return
                }
                val results = ArrayList(pendingSearchResults)
                val resultCounts = viewModel.searchResultCounts
                pendingSearchResults.clear()
                binding.tvCurrentSearchInfo.post {
                    if (viewModel.lastQuery != searchQuery) {
                        return@post
                    }
                    binding.tvCurrentSearchInfo.text =
                        this@SearchContentActivity.getString(R.string.search_content_size) + ": ${resultCounts}"
                    adapter.addItems(results)
                }
            }
            kotlin.runCatching {
                appDb.bookChapterDao.getChapterList(viewModel.bookUrl)
                    .asFlow()
                    .mapAsyncIndexed(searchChapterThreads) { _, bookChapter ->
                        ensureActive()
                        val searchResults = if (isLocalBook
                            || viewModel.cacheChapterNames.contains(bookChapter.getFileName())
                        ) {
                            viewModel.searchChapter(query, bookChapter)
                        } else {
                            emptyList()
                        }
                        bookChapter to searchResults
                    }
                    .collect { (_, searchResults) ->
                        ensureActive()
                        if (searchResults.isNotEmpty()) {
                            viewModel.searchResultList.addAll(searchResults)
                            viewModel.searchResultCounts += searchResults.size
                            pendingSearchResults.addAll(searchResults)
                            if (pendingSearchResults.size >= 100) {
                                flushPendingSearchResults()
                            }
                        }
                    }
                flushPendingSearchResults()
                if (viewModel.searchResultCounts == 0) {
                    val noSearchResult =
                        SearchResult(resultText = getString(R.string.search_content_empty))
                    binding.tvCurrentSearchInfo.post {
                        if (viewModel.lastQuery != searchQuery) {
                            return@post
                        }
                        adapter.addItem(noSearchResult)
                    }
                    }
            }.onFailure {
                if (it is CancellationException) {
                    return@onFailure
                }
                AppLog.put("全文搜索出错\n${it.localizedMessage}", it)
            }
            flushPendingSearchResults()
            binding.tvCurrentSearchInfo.post {
                if (viewModel.lastQuery != searchQuery) {
                    return@post
                }
                binding.fbStop.invisible()
                binding.refreshProgressBar.isAutoLoading = false
            }
        }
    }

    private val isLocalBook: Boolean
        get() = viewModel.book?.isLocal == true

    override fun openSearchResult(searchResult: SearchResult, index: Int) {
        viewModel.lastQuery = ""
        searchJob?.cancel()
        val searchData = Intent()
        val key = System.currentTimeMillis()
        IntentData.put("searchResult$key", searchResult)
        val listKey = "searchResultList$key"
        IntentData.put(listKey, viewModel.searchResultList)
        searchData.putExtra("key", key)
        searchData.putExtra("searchResultListKey", listKey)
        searchData.putExtra("index", index)
        searchData.putExtra("effectiveReplaceRuleId", viewModel.effectiveReplaceRuleId ?: -1L)
        searchData.putExtra(
            "effectiveReplaceRuleName",
            searchResult.effectiveReplaceRuleName ?: viewModel.effectiveReplaceRuleName
        )
        hasReturnResult = true
        setResult(RESULT_OK, searchData)
        finish()
    }

    override fun finish() {
        if (!hasReturnResult && viewModel.effectiveReplaceRuleId != null) {
            hasReturnResult = true
            val searchData = Intent()
            searchData.putExtra("showEffectiveReplaces", true)
            searchData.putExtra("effectiveReplaceRuleId", viewModel.effectiveReplaceRuleId ?: -1L)
            searchData.putExtra("effectiveReplaceRuleName", viewModel.effectiveReplaceRuleName)
            setResult(RESULT_OK, searchData)
        }
        super.finish()
    }

    override fun durChapterIndex(): Int {
        return durChapterIndex
    }

}
