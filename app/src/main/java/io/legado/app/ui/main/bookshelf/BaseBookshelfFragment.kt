package io.legado.app.ui.main.bookshelf

import android.annotation.SuppressLint
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.core.view.indices
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.DialogBookshelfConfigBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogRandomRecommendFilterBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.ConvertUtils
import io.legado.app.help.book.BookSort
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.book.LocalBookMissingChecker
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.import.local.ImportBookActivity
import io.legado.app.ui.book.import.remote.RemoteBookActivity
import io.legado.app.ui.book.manage.BookshelfManageActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.checkByIndex
import io.legado.app.utils.getCheckedIndex
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.postEvent
import io.legado.app.utils.readText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class BaseBookshelfFragment(layoutId: Int) : VMBaseFragment<BookshelfViewModel>(layoutId),
    MainFragmentInterface {

    override val position: Int? get() = arguments?.getInt("position")

    val activityViewModel by activityViewModels<MainViewModel>()
    override val viewModel by viewModels<BookshelfViewModel>()

    private val importBookshelf = registerForActivityResult(HandleFileContract()) {
        kotlin.runCatching {
            it.uri?.readText(requireContext())?.let { text ->
                viewModel.importBookshelf(text, groupId)
            }
        }.onFailure {
            toastOnUi(it.localizedMessage ?: "ERROR")
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            alert(R.string.export_success) {
                if (uri.toString().isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary())
                }
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = getString(R.string.path)
                    editView.setText(uri.toString())
                }
                customView { alertBinding.root }
                okButton {
                    requireContext().sendToClip(uri.toString())
                }
            }
        }
    }
    abstract val groupId: Long
    abstract val books: List<Book>
    abstract var onlyUpdateRead: Boolean
    private var groupsLiveData: LiveData<List<BookGroup>>? = null
    private val waitDialog by lazy {
        WaitDialog(requireContext()).apply {
            setOnCancelListener {
                viewModel.addBookJob?.cancel()
            }
        }
    }

    abstract fun gotoTop()

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.main_bookshelf, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        super.onCompatOptionsItemSelected(item)
        when (item.itemId) {
            R.id.menu_remote -> startActivity<RemoteBookActivity>()
            R.id.menu_search -> openLocalBookSearch()
            R.id.menu_random_recommend -> randomRecommend()
            R.id.menu_update_toc -> activityViewModel.upToc(books, false)
            R.id.menu_refresh_group_books -> activityViewModel.refreshBookInfo(books)
            R.id.menu_bookshelf_layout -> configBookshelf()
            R.id.menu_check_missing_local_books -> checkMissingLocalBooksManually()
            R.id.menu_clear_invalid_cache -> clearInvalidCache()
            R.id.menu_group_manage -> showDialogFragment<GroupManageDialog>()
            R.id.menu_add_local -> startActivity<ImportBookActivity>()
            R.id.menu_add_url -> showAddBookByUrlAlert()
            R.id.menu_bookshelf_manage -> startActivity<BookshelfManageActivity> {
                putExtra("groupId", groupId)
            }

            R.id.menu_download -> startActivity<CacheActivity> {
                putExtra("groupId", groupId)
            }

            R.id.menu_export_bookshelf -> viewModel.exportBookshelf(books) { file ->
                exportResult.launch {
                    mode = HandleFileContract.EXPORT
                    fileData =
                        HandleFileContract.FileData("bookshelf.json", file, "application/json")
                }
            }

            R.id.menu_import_bookshelf -> importBookshelfAlert(groupId)
            R.id.menu_tag_manage -> showDialogFragment<io.legado.app.ui.book.tag.TagManageDialog>()
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
    }

    protected fun openLocalBookSearch(query: String? = null) {
        SearchActivity.startLocal(this, query, groupId)
    }

    private fun clearInvalidCache() {
        waitDialog.setText(getString(R.string.clear_cache))
        waitDialog.show()
        lifecycleScope.launch {
            val clearSize = withContext(IO) { BookHelp.scanInvalidCacheSize() }
            waitDialog.dismiss()
            alert(R.string.clear_cache) {
                setMessage("可清理缓存: ${ConvertUtils.formatFileSize(clearSize)}")
                yesButton {
                    clearInvalidCache(clearSize)
                }
                noButton()
            }
        }
    }

    private fun clearInvalidCache(scanSize: Long) {
        waitDialog.setText(getString(R.string.clear_cache))
        waitDialog.show()
        lifecycleScope.launch {
            val clearSize = withContext(IO) { BookHelp.clearInvalidCache() }
            waitDialog.dismiss()
            toastOnUi(
                "${getString(R.string.clear_cache_success)}: ${
                    ConvertUtils.formatFileSize(maxOf(clearSize, scanSize))
                }"
            )
        }
    }

    protected fun initBookGroupData() {
        groupsLiveData?.removeObservers(viewLifecycleOwner)
        groupsLiveData = appDb.bookGroupDao.show.apply {
            observe(viewLifecycleOwner) {
                upGroup(it)
            }
        }
    }

    abstract fun upGroup(data: List<BookGroup>)

    abstract fun upSort()

    private fun randomRecommend() {
        lifecycleScope.launch {
            val tags = withContext(IO) { appDb.bookTagDao.getAll() }
            showRandomRecommendFilterDialog(tags)
        }
    }

    @SuppressLint("InflateParams")
    private fun showRandomRecommendFilterDialog(allTags: List<io.legado.app.data.entities.BookTag>) {
        val dialogBinding = DialogRandomRecommendFilterBinding.inflate(layoutInflater)
        // Populate tag checkboxes
        allTags.forEach { tag ->
            val cb = android.widget.CheckBox(requireContext()).apply {
                text = tag.name
                this.tag = tag.id
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = android.widget.GridLayout.spec(
                        android.widget.GridLayout.UNDEFINED,
                        1f
                    )
                }
            }
            dialogBinding.llTags.addView(cb)
        }
        alert(titleResource = R.string.random_recommend) {
            customView { dialogBinding.root }
            okButton {
                val selectedWordCountCbs = listOf(
                    dialogBinding.cbWordShort,
                    dialogBinding.cbWordMediumShort,
                    dialogBinding.cbWordMediumLong,
                    dialogBinding.cbWordMediumLong2,
                    dialogBinding.cbWordLong,
                    dialogBinding.cbWordSuperLong
                ).filter { it.isChecked }
                val selectedTagIds = (0 until dialogBinding.llTags.childCount)
                    .map { dialogBinding.llTags.getChildAt(it) as android.widget.CheckBox }
                    .filter { it.isChecked }
                    .map { it.tag as Long }
                doRandomRecommend(selectedWordCountCbs, selectedTagIds)
            }
            cancelButton()
        }
    }

    private fun doRandomRecommend(
        wordCountCbs: List<android.widget.CheckBox>,
        tagIds: List<Long>
    ) {
        lifecycleScope.launch {
            val book = withContext(IO) {
                var filtered = appDb.bookDao.getRandomLocalBooksByGroupAndTags(
                    groupId = groupId,
                    excludeBookUrl = null,
                    tagIds = tagIds
                )
                // Filter by word count
                if (wordCountCbs.isNotEmpty()) {
                    val ranges = wordCountCbs.map { cb ->
                        val parts = (cb.tag as String).split(",")
                        parts[0].toDouble() to parts[1].toDouble()
                    }
                    filtered = filtered.filter { book ->
                        BookSort.matchesRandomRecommendWordRanges(book, ranges)
                    }
                }
                if (filtered.isEmpty()) null
                else filtered.first()
            }
            if (book != null) {
                val wcRanges = wordCountCbs.map { it.tag as String }.joinToString(",")
                startActivity<BookInfoActivity> {
                    putExtra("name", book.name)
                    putExtra("author", book.author)
                    putExtra("bookUrl", book.bookUrl)
                    putExtra("randomRecommendGroupId", groupId)
                    if (wcRanges.isNotEmpty()) putExtra("randomRecommendWordCount", wcRanges)
                    if (tagIds.isNotEmpty()) putExtra("randomRecommendTagIds", tagIds.toLongArray())
                }
            } else {
                withContext(Dispatchers.Main) {
                    if (wordCountCbs.isEmpty() && tagIds.isEmpty())
                        toastOnUi(R.string.no_local_book)
                    else
                        toastOnUi(R.string.no_local_book_filter)
                }
            }
        }
    }

    override fun observeLiveBus() {
        viewModel.addBookProgressLiveData.observe(this) { count ->
            if (count < 0) {
                waitDialog.dismiss()
            } else {
                waitDialog.setText("添加中... ($count)")
            }
        }
    }

    @SuppressLint("InflateParams")
    fun showAddBookByUrlAlert() {
        alert(titleResource = R.string.add_book_url) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    waitDialog.setText("添加中...")
                    waitDialog.show()
                    viewModel.addBookByUrl(it)
                }
            }
            cancelButton()
        }
    }

    @SuppressLint("InflateParams")
    fun configBookshelf() {
        alert(titleResource = R.string.bookshelf_layout) {
            var bookshelfLayout = AppConfig.bookshelfLayout
            var bookshelfSort = AppConfig.bookshelfSort
            var showBookname = AppConfig.showBookname
            val alertBinding =
                DialogBookshelfConfigBinding.inflate(layoutInflater)
                    .apply {
                        if (AppConfig.bookGroupStyle !in 0..<spGroupStyle.count) {
                            AppConfig.bookGroupStyle = 0
                        }
                        if (bookshelfLayout !in rgLayout.indices) {
                            bookshelfLayout = 0
                            AppConfig.bookshelfLayout = 0
                        }
                        // 手动排序(3)和综合(4)不再支持，回退到默认
                    if (bookshelfSort == 3 || bookshelfSort == 4) {
                        bookshelfSort = 0
                        AppConfig.bookshelfSort = 0
                    }
                    if (bookshelfSort !in setOf(0, 2, 5, 6, 7, 8)) {
                        bookshelfSort = 0
                        AppConfig.bookshelfSort = 0
                    }
                    // 通过tag找到排序值对应的RadioButton
                    val sortRbId = when (bookshelfSort) {
                        0 -> R.id.rb_sort_0
                        2 -> R.id.rb_sort_2
                        5 -> R.id.rb_sort_5
                        6 -> R.id.rb_sort_6
                        7 -> R.id.rb_sort_7
                        8 -> R.id.rb_sort_8
                        else -> R.id.rb_sort_0
                    }
                    rgSort.check(sortRbId)
                        if (showBookname !in rgbLayout.indices) {
                            showBookname = 0
                            AppConfig.showBookname = 0
                        }
                        spGroupStyle.setSelection(AppConfig.bookGroupStyle)
                        swShowUnread.isChecked = AppConfig.showUnread
                        swShowLastUpdateTime.isChecked = AppConfig.showLastUpdateTime
                        swShowWaitUpBooks.isChecked = AppConfig.showWaitUpCount
                        swShowBookshelfFastScroller.isChecked = AppConfig.showBookshelfFastScroller
                        rgLayout.checkByIndex(bookshelfLayout)
                        rgbLayout.checkByIndex(showBookname)
                        if (bookshelfLayout < 2) {
                            bookNameChoice.visibility = View.GONE
                        }
                        rgLayout.setOnCheckedChangeListener { group, checkedId ->
                            val index = group.getCheckedIndex()
                            bookNameChoice.visibility = if (index > 1) View.VISIBLE else View.GONE
                        }
                        margin.progress = AppConfig.bookshelfMargin
                    }
            customView { alertBinding.root }
            okButton {
                alertBinding.apply {
                    var notifyMain = false
                    var recreate = false
                    if (AppConfig.bookGroupStyle != spGroupStyle.selectedItemPosition) {
                        AppConfig.bookGroupStyle = spGroupStyle.selectedItemPosition
                        notifyMain = true
                    }
                    if (showBookname != rgbLayout.getCheckedIndex()) {
                        AppConfig.showBookname = rgbLayout.getCheckedIndex()
                        recreate = true
                    }
                    if (AppConfig.bookshelfMargin != margin.progress) {
                        AppConfig.bookshelfMargin = margin.progress
                        recreate = true
                    }
                    if (AppConfig.showUnread != swShowUnread.isChecked) {
                        AppConfig.showUnread = swShowUnread.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (AppConfig.showLastUpdateTime != swShowLastUpdateTime.isChecked) {
                        AppConfig.showLastUpdateTime = swShowLastUpdateTime.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (AppConfig.showWaitUpCount != swShowWaitUpBooks.isChecked) {
                        AppConfig.showWaitUpCount = swShowWaitUpBooks.isChecked
                        activityViewModel.postUpBooksLiveData(true)
                    }
                    if (AppConfig.showBookshelfFastScroller != swShowBookshelfFastScroller.isChecked) {
                        AppConfig.showBookshelfFastScroller = swShowBookshelfFastScroller.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    val newSort = (rgSort.findViewById<android.widget.RadioButton>(rgSort.checkedRadioButtonId)?.tag as? String)?.toIntOrNull() ?: 0
                    if (bookshelfSort != newSort) {
                        AppConfig.bookshelfSort = newSort
                        upSort()
                    }
                    if (bookshelfLayout != rgLayout.getCheckedIndex()) {
                        AppConfig.bookshelfLayout = rgLayout.getCheckedIndex()
                        if (AppConfig.bookshelfLayout < 2) {
                            activityViewModel.booksGridRecycledViewPool.clear()
                        } else {
                            activityViewModel.booksListRecycledViewPool.clear()
                        }
                        recreate = true
                    }
                    if (recreate) {
                        postEvent(EventBus.RECREATE, "")
                    } else if (notifyMain) {
                        postEvent(EventBus.NOTIFY_MAIN, false)
                    }
                }
            }
            cancelButton()
        }
    }

    private fun checkMissingLocalBooksManually() {
        lifecycleScope.launch {
            val missingBooks = withContext(IO) {
                LocalBookMissingChecker.findMissing(books, force = true)
            }
            if (missingBooks.isEmpty()) {
                toastOnUi(R.string.no_missing_local_books)
                return@launch
            }
            val detail = missingBooks.joinToString("\n") { "《${it.name}》" }
            alert(
                title = getString(R.string.missing_local_book_title),
                message = getString(
                    R.string.missing_local_book_detail_message,
                    missingBooks.size,
                    detail
                )
            ) {
                positiveButton(R.string.remove_missing_local_books) {
                    lifecycleScope.launch(IO) {
                        missingBooks.forEach { it.delete() }
                    }
                }
                cancelButton()
            }
        }
    }


    private fun importBookshelfAlert(groupId: Long) {
        alert(titleResource = R.string.import_bookshelf) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url/json"
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    viewModel.importBookshelf(it, groupId)
                }
            }
            cancelButton()
            neutralButton(R.string.select_file) {
                importBookshelf.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("txt", "json")
                }
            }
        }
    }

}
