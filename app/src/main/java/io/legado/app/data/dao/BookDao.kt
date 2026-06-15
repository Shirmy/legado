package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isNotShelf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface BookDao {

    fun flowByGroup(groupId: Long): Flow<List<Book>> {
        return when (groupId) {
            BookGroup.IdRoot -> flowRoot()
            BookGroup.IdAll -> flowAll()
            BookGroup.IdLocal -> flowLocal()
            BookGroup.IdAudio -> flowAudio()
            BookGroup.IdNetNone -> flowNetNoGroup()
            BookGroup.IdLocalNone -> flowLocalNoGroup()
            BookGroup.IdVideo -> flowVideo()
            BookGroup.IdError -> flowUpdateError()
            else -> flowByUserGroup(groupId)
        }.map { list ->
            list.filterNot { it.isNotShelf }
        }
    }

    @Query(
        """
        select * from books where type & ${BookType.text} > 0
        and type & ${BookType.local} = 0
        and ((SELECT sum(groupId) FROM book_groups where groupId > 0) & `group`) = 0
        and (select show from book_groups where groupId = ${BookGroup.IdNetNone}) != 1
        """
    )
    fun flowRoot(): Flow<List<Book>>

    @Query("SELECT * FROM books order by durChapterTime desc")
    fun flowAll(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE type & ${BookType.audio} > 0")
    fun flowAudio(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE type & ${BookType.video} > 0")
    fun flowVideo(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE type & ${BookType.local} > 0")
    fun flowLocal(): Flow<List<Book>>

    @Query(
        """
        select * from books where type & ${BookType.audio} = 0 and type & ${BookType.local} = 0 and type & ${BookType.video} = 0
        and ((SELECT sum(groupId) FROM book_groups where groupId > 0) & `group`) = 0
        """
    )
    fun flowNetNoGroup(): Flow<List<Book>>

    @Query(
        """
        select * from books where type & ${BookType.local} > 0
        and ((SELECT sum(groupId) FROM book_groups where groupId > 0) & `group`) = 0
        """
    )
    fun flowLocalNoGroup(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE (`group` & :group) > 0")
    fun flowByUserGroup(group: Long): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE name like '%'||:key||'%' or author like '%'||:key||'%'")
    fun flowSearch(key: String): Flow<List<Book>>

    @Query(
        """
        SELECT * FROM books
        WHERE type & ${BookType.local} > 0
        AND type & ${BookType.notShelf} = 0
        AND (
            name like '%'||:key||'%'
            or author like '%'||:key||'%'
            or originName like '%'||:key||'%'
            or exists (
                select 1 from book_tag_refs btr
                inner join book_tags bt on bt.id = btr.tagId
                where btr.bookUrl = books.bookUrl
                and bt.name like '%'||:key||'%'
            )
        )
        ORDER BY durChapterTime DESC
        """
    )
    fun flowSearchLocal(key: String): Flow<List<Book>>

    @Query(
        """
        SELECT * FROM books
        WHERE type & ${BookType.local} > 0
        AND type & ${BookType.notShelf} = 0
        AND name like '%'||:key||'%'
        ORDER BY durChapterTime DESC
        """
    )
    fun flowSearchLocalByName(key: String): Flow<List<Book>>

    @Query(
        """
        SELECT * FROM books
        WHERE type & ${BookType.local} > 0
        AND type & ${BookType.notShelf} = 0
        AND author like '%'||:key||'%'
        ORDER BY durChapterTime DESC
        """
    )
    fun flowSearchLocalByAuthor(key: String): Flow<List<Book>>

    @Query(
        """
        SELECT * FROM books
        WHERE type & ${BookType.local} > 0
        AND type & ${BookType.notShelf} = 0
        AND exists (
            select 1 from book_tag_refs btr
            inner join book_tags bt on bt.id = btr.tagId
            where btr.bookUrl = books.bookUrl
            and bt.name like '%'||:key||'%'
        )
        ORDER BY durChapterTime DESC
        """
    )
    fun flowSearchLocalByTag(key: String): Flow<List<Book>>

    @Query("SELECT * FROM books where type & ${BookType.updateError} > 0 order by durChapterTime desc")
    fun flowUpdateError(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE (`group` & :group) > 0")
    fun getBooksByGroup(group: Long): List<Book>

    @Query("SELECT * FROM books WHERE type & ${BookType.local} > 0")
    fun getAllLocalBooks(): List<Book>

    @Query("SELECT originName FROM books WHERE type & ${BookType.local} > 0")
    fun getAllLocalFileNames(): List<String>

    @Query("SELECT * FROM books WHERE `name` in (:names)")
    fun findByName(vararg names: String): List<Book>

    @Query("select * from books where originName = :fileName")
    fun getBookByFileName(fileName: String): Book?

    @Query("select * from books where type & ${BookType.local} > 0 and originName = :fileName")
    fun getLocalBooksByFileName(fileName: String): List<Book>

    @Query("SELECT * FROM books WHERE bookUrl = :bookUrl")
    fun getBook(bookUrl: String): Book?

    @Query("SELECT * FROM books WHERE name = :name and author = :author")
    fun getBook(name: String, author: String): Book?

    @Query("""select distinct bs.* from books, book_sources bs 
        where origin == bookSourceUrl and origin not like '${BookType.localTag}%' 
        and origin not like '${BookType.webDavTag}%'""")
    fun getAllUseBookSource(): List<BookSource>

    @Query("SELECT * FROM books WHERE name = :name and origin = :origin")
    fun getBookByOrigin(name: String, origin: String): Book?

    @get:Query("select count(bookUrl) from books where (SELECT sum(groupId) FROM book_groups)")
    val noGroupSize: Int

    @get:Query("SELECT * FROM books where type & ${BookType.local} = 0")
    val webBooks: List<Book>

    @get:Query("SELECT * FROM books where type & ${BookType.local} = 0 and canUpdate = 1")
    val hasUpdateBooks: List<Book>

    fun getRandomLocalBookByGroup(groupId: Long, excludeBookUrl: String? = null): Book? {
        return when (groupId) {
            BookGroup.IdLocalNone -> getRandomLocalBookNoGroup(excludeBookUrl)
            BookGroup.IdVideo -> getRandomLocalBookByType(BookType.video, excludeBookUrl)
            BookGroup.IdAudio -> getRandomLocalBookByType(BookType.audio, excludeBookUrl)
            else -> {
                if (groupId > 0) {
                    getRandomLocalBookByUserGroup(groupId, excludeBookUrl)
                } else {
                    getRandomLocalBook(excludeBookUrl)
                }
            }
        }
    }

    fun getBookCountByGroup(groupId: Long): Int {
        return when (groupId) {
            BookGroup.IdRoot -> countRoot()
            BookGroup.IdAll -> countAll()
            BookGroup.IdLocal -> countLocal()
            BookGroup.IdAudio -> countAudio()
            BookGroup.IdNetNone -> countNetNoGroup()
            BookGroup.IdLocalNone -> countLocalNoGroup()
            BookGroup.IdVideo -> countVideo()
            BookGroup.IdError -> countUpdateError()
            else -> countByUserGroup(groupId)
        }
    }

    @Query(
        """
        select count(*) from books where type & ${BookType.text} > 0
        and type & ${BookType.local} = 0
        and type & ${BookType.notShelf} = 0
        and ((SELECT sum(groupId) FROM book_groups where groupId > 0) & `group`) = 0
        and (select show from book_groups where groupId = ${BookGroup.IdNetNone}) != 1
        """
    )
    fun countRoot(): Int

    @Query("SELECT count(*) FROM books WHERE type & ${BookType.notShelf} = 0")
    fun countAll(): Int

    @Query("SELECT count(*) FROM books WHERE type & ${BookType.local} > 0 and type & ${BookType.notShelf} = 0")
    fun countLocal(): Int

    @Query("SELECT count(*) FROM books WHERE type & ${BookType.audio} > 0 and type & ${BookType.notShelf} = 0")
    fun countAudio(): Int

    @Query("SELECT count(*) FROM books WHERE type & ${BookType.video} > 0 and type & ${BookType.notShelf} = 0")
    fun countVideo(): Int

    @Query(
        """
        select count(*) from books where type & ${BookType.audio} = 0
        and type & ${BookType.local} = 0
        and type & ${BookType.video} = 0
        and type & ${BookType.notShelf} = 0
        and ((SELECT sum(groupId) FROM book_groups where groupId > 0) & `group`) = 0
        """
    )
    fun countNetNoGroup(): Int

    @Query(
        """
        select count(*) from books where type & ${BookType.local} > 0
        and type & ${BookType.notShelf} = 0
        and ((SELECT sum(groupId) FROM book_groups where groupId > 0) & `group`) = 0
        """
    )
    fun countLocalNoGroup(): Int

    @Query("SELECT count(*) FROM books WHERE type & ${BookType.updateError} > 0 and type & ${BookType.notShelf} = 0")
    fun countUpdateError(): Int

    @Query("SELECT count(*) FROM books WHERE (`group` & :group) > 0 and type & ${BookType.notShelf} = 0")
    fun countByUserGroup(group: Long): Int

    fun getRandomLocalBooksByGroupAndTags(
        groupId: Long,
        excludeBookUrl: String?,
        tagIds: List<Long>
    ): List<Book> {
        val queryTagIds = tagIds.ifEmpty { listOf(-1L) }
        val tagCount = tagIds.size
        return when (groupId) {
            BookGroup.IdLocalNone -> getRandomLocalBooksNoGroupWithTags(excludeBookUrl, queryTagIds, tagCount)
            BookGroup.IdVideo -> getRandomLocalBooksByTypeWithTags(BookType.video, excludeBookUrl, queryTagIds, tagCount)
            BookGroup.IdAudio -> getRandomLocalBooksByTypeWithTags(BookType.audio, excludeBookUrl, queryTagIds, tagCount)
            else -> {
                if (groupId > 0) {
                    getRandomLocalBooksByUserGroupWithTags(groupId, excludeBookUrl, queryTagIds, tagCount)
                } else {
                    getRandomLocalBooksWithTags(excludeBookUrl, queryTagIds, tagCount)
                }
            }
        }
    }

    @Query(
        """
        select * from books where type & ${BookType.local} > 0
        and (:excludeBookUrl is null or bookUrl != :excludeBookUrl)
        order by random() limit 1
        """
    )
    fun getRandomLocalBook(excludeBookUrl: String? = null): Book?

    @Query(
        """
        select * from books where type & ${BookType.local} > 0
        and ((SELECT sum(groupId) FROM book_groups where groupId > 0) & `group`) = 0
        and (:excludeBookUrl is null or bookUrl != :excludeBookUrl)
        order by random() limit 1
        """
    )
    fun getRandomLocalBookNoGroup(excludeBookUrl: String? = null): Book?

    @Query(
        """
        select * from books where type & ${BookType.local} > 0
        and type & :type > 0
        and (:excludeBookUrl is null or bookUrl != :excludeBookUrl)
        order by random() limit 1
        """
    )
    fun getRandomLocalBookByType(type: Int, excludeBookUrl: String? = null): Book?

    @Query(
        """
        select * from books where type & ${BookType.local} > 0
        and (`group` & :groupId) > 0
        and (:excludeBookUrl is null or bookUrl != :excludeBookUrl)
        order by random() limit 1
        """
    )
    fun getRandomLocalBookByUserGroup(groupId: Long, excludeBookUrl: String? = null): Book?

    @Query(
        """
        select * from books where type & ${BookType.local} > 0
        and (:excludeBookUrl is null or bookUrl != :excludeBookUrl)
        and (:tagCount = 0 or exists (
            select 1 from book_tag_refs
            where book_tag_refs.bookUrl = books.bookUrl
            and tagId in (:tagIds)
        ))
        order by random()
        """
    )
    fun getRandomLocalBooksWithTags(
        excludeBookUrl: String?,
        tagIds: List<Long>,
        tagCount: Int
    ): List<Book>

    @Query(
        """
        select * from books where type & ${BookType.local} > 0
        and ((SELECT sum(groupId) FROM book_groups where groupId > 0) & `group`) = 0
        and (:excludeBookUrl is null or bookUrl != :excludeBookUrl)
        and (:tagCount = 0 or exists (
            select 1 from book_tag_refs
            where book_tag_refs.bookUrl = books.bookUrl
            and tagId in (:tagIds)
        ))
        order by random()
        """
    )
    fun getRandomLocalBooksNoGroupWithTags(
        excludeBookUrl: String?,
        tagIds: List<Long>,
        tagCount: Int
    ): List<Book>

    @Query(
        """
        select * from books where type & ${BookType.local} > 0
        and type & :type > 0
        and (:excludeBookUrl is null or bookUrl != :excludeBookUrl)
        and (:tagCount = 0 or exists (
            select 1 from book_tag_refs
            where book_tag_refs.bookUrl = books.bookUrl
            and tagId in (:tagIds)
        ))
        order by random()
        """
    )
    fun getRandomLocalBooksByTypeWithTags(
        type: Int,
        excludeBookUrl: String?,
        tagIds: List<Long>,
        tagCount: Int
    ): List<Book>

    @Query(
        """
        select * from books where type & ${BookType.local} > 0
        and (`group` & :groupId) > 0
        and (:excludeBookUrl is null or bookUrl != :excludeBookUrl)
        and (:tagCount = 0 or exists (
            select 1 from book_tag_refs
            where book_tag_refs.bookUrl = books.bookUrl
            and tagId in (:tagIds)
        ))
        order by random()
        """
    )
    fun getRandomLocalBooksByUserGroupWithTags(
        groupId: Long,
        excludeBookUrl: String?,
        tagIds: List<Long>,
        tagCount: Int
    ): List<Book>

    @get:Query("SELECT * FROM books")
    val all: List<Book>

    @Query("SELECT * FROM books where type & :type > 0 and type & ${BookType.local} = 0")
    fun getByTypeOnLine(type: Int): List<Book>

    @get:Query("SELECT * FROM books where type & ${BookType.text} > 0 ORDER BY durChapterTime DESC limit 1")
    val lastReadBook: Book?

    @get:Query("SELECT bookUrl FROM books")
    val allBookUrls: List<String>

    @get:Query("SELECT COUNT(*) FROM books")
    val allBookCount: Int

    @get:Query("select min(`order`) from books")
    val minOrder: Int

    @get:Query("select max(`order`) from books")
    val maxOrder: Int

    @Query("select exists(select 1 from books where bookUrl = :bookUrl)")
    fun has(bookUrl: String): Boolean

    @Query("select exists(select 1 from books where name = :name and author = :author)")
    fun has(name: String, author: String): Boolean

    @Query(
        """select exists(select 1 from books where type & ${BookType.local} > 0 
        and (originName = :fileName or (origin != '${BookType.localTag}' and origin like '%' || :fileName)))"""
    )
    fun hasFile(fileName: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg book: Book)

    @Update
    fun update(vararg book: Book)

    @Delete
    fun delete(vararg book: Book)

    @Transaction
    fun replace(oldBook: Book, newBook: Book) {
        delete(oldBook)
        insert(newBook)
    }

    @Query("update books set durChapterPos = :pos where bookUrl = :bookUrl")
    fun upProgress(bookUrl: String, pos: Int)

    @Query("update books set `group` = :newGroupId where `group` = :oldGroupId")
    fun upGroup(oldGroupId: Long, newGroupId: Long)

    @Query("update books set `group` = `group` - :group where `group` & :group > 0")
    fun removeGroup(group: Long)

    @Query("delete from books where type & ${BookType.notShelf} > 0")
    fun deleteNotShelfBook()
}
