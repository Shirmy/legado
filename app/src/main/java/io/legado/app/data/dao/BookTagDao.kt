package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.BookTag
import io.legado.app.help.book.BookTagSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface BookTagDao {

    @Query(
        """
        SELECT * FROM book_tags
        ORDER BY
            CASE WHEN name LIKE '!%' THEN 0 ELSE 1 END,
            CASE
                WHEN (CASE WHEN name LIKE '!%' THEN substr(name, 2) ELSE name END) GLOB '[A-Za-z]*' THEN 0
                ELSE 1
            END,
            lower(CASE WHEN name LIKE '!%' THEN substr(name, 2) ELSE name END),
            CASE WHEN name LIKE '!%' THEN substr(name, 2) ELSE name END,
            `order`
        """
    )
    fun flowAllRaw(): Flow<List<BookTag>>

    fun flowAll(): Flow<List<BookTag>> {
        return flowAllRaw().map { BookTagSort.sort(it) }
    }

    @Query(
        """
        SELECT * FROM book_tags
        ORDER BY
            CASE WHEN name LIKE '!%' THEN 0 ELSE 1 END,
            CASE
                WHEN (CASE WHEN name LIKE '!%' THEN substr(name, 2) ELSE name END) GLOB '[A-Za-z]*' THEN 0
                ELSE 1
            END,
            lower(CASE WHEN name LIKE '!%' THEN substr(name, 2) ELSE name END),
            CASE WHEN name LIKE '!%' THEN substr(name, 2) ELSE name END,
            `order`
        """
    )
    fun getAllRaw(): List<BookTag>

    fun getAll(): List<BookTag> {
        return BookTagSort.sort(getAllRaw())
    }

    @Query("SELECT * FROM book_tags WHERE id = :id")
    fun getById(id: Long): BookTag?

    @Query("SELECT * FROM book_tags WHERE name = :name")
    fun getByName(name: String): BookTag?

    @get:Query("SELECT IFNULL(MAX(`order`), 0) FROM book_tags")
    val maxOrder: Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg tag: BookTag)

    @Update
    fun update(vararg tag: BookTag)

    @Delete
    fun delete(vararg tag: BookTag)
}
