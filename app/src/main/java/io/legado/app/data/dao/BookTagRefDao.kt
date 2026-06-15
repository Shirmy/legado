package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagRef
import io.legado.app.help.book.BookTagSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class BookTagCount(
    val tagId: Long,
    val count: Int
)

@Dao
interface BookTagRefDao {

    @Query(
        """
        SELECT bt.* FROM book_tags bt INNER JOIN book_tag_refs btr ON bt.id = btr.tagId
        WHERE btr.bookUrl = :bookUrl
        ORDER BY
            CASE WHEN bt.name LIKE '!%' THEN 0 ELSE 1 END,
            CASE
                WHEN (CASE WHEN bt.name LIKE '!%' THEN substr(bt.name, 2) ELSE bt.name END) GLOB '[A-Za-z]*' THEN 0
                ELSE 1
            END,
            lower(CASE WHEN bt.name LIKE '!%' THEN substr(bt.name, 2) ELSE bt.name END),
            CASE WHEN bt.name LIKE '!%' THEN substr(bt.name, 2) ELSE bt.name END,
            bt.`order`
        """
    )
    fun flowTagsByBookRaw(bookUrl: String): Flow<List<BookTag>>

    fun flowTagsByBook(bookUrl: String): Flow<List<BookTag>> {
        return flowTagsByBookRaw(bookUrl).map { BookTagSort.sort(it) }
    }

    @Query(
        """
        SELECT bt.* FROM book_tags bt INNER JOIN book_tag_refs btr ON bt.id = btr.tagId
        WHERE btr.bookUrl = :bookUrl
        ORDER BY
            CASE WHEN bt.name LIKE '!%' THEN 0 ELSE 1 END,
            CASE
                WHEN (CASE WHEN bt.name LIKE '!%' THEN substr(bt.name, 2) ELSE bt.name END) GLOB '[A-Za-z]*' THEN 0
                ELSE 1
            END,
            lower(CASE WHEN bt.name LIKE '!%' THEN substr(bt.name, 2) ELSE bt.name END),
            CASE WHEN bt.name LIKE '!%' THEN substr(bt.name, 2) ELSE bt.name END,
            bt.`order`
        """
    )
    fun getTagsByBookRaw(bookUrl: String): List<BookTag>

    fun getTagsByBook(bookUrl: String): List<BookTag> {
        return BookTagSort.sort(getTagsByBookRaw(bookUrl))
    }

    @Query("SELECT btr.tagId FROM book_tag_refs btr WHERE btr.bookUrl = :bookUrl")
    fun getTagIdsByBook(bookUrl: String): List<Long>

    @Query("SELECT DISTINCT b.bookUrl FROM book_tag_refs b WHERE b.tagId IN (:tagIds) GROUP BY b.bookUrl HAVING COUNT(DISTINCT b.tagId) = :requiredCount")
    fun getBookUrlsByAllTags(tagIds: List<Long>, requiredCount: Int): List<String>

    @Query("SELECT tagId, COUNT(DISTINCT bookUrl) AS count FROM book_tag_refs GROUP BY tagId")
    fun getTagCounts(): List<BookTagCount>

    @Query("SELECT COUNT(*) FROM book_tag_refs WHERE bookUrl = :bookUrl AND tagId = :tagId")
    fun hasTag(bookUrl: String, tagId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg ref: BookTagRef)

    @Query("DELETE FROM book_tag_refs WHERE bookUrl = :bookUrl AND tagId = :tagId")
    fun delete(bookUrl: String, tagId: Long)

    @Query("DELETE FROM book_tag_refs WHERE bookUrl = :bookUrl")
    fun deleteByBook(bookUrl: String)

    @Query(
        """
        INSERT OR IGNORE INTO book_tag_refs(bookUrl, tagId)
        SELECT bookUrl, :newTagId FROM book_tag_refs WHERE tagId = :oldTagId
        """
    )
    fun copyRefsToTag(oldTagId: Long, newTagId: Long)

    @Query("DELETE FROM book_tag_refs WHERE tagId = :tagId")
    fun deleteByTag(tagId: Long)

    @Transaction
    fun mergeTag(oldTagId: Long, newTagId: Long) {
        copyRefsToTag(oldTagId, newTagId)
        deleteByTag(oldTagId)
    }
}
