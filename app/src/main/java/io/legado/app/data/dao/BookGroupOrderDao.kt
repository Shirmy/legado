package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.BookGroupOrder

@Dao
interface BookGroupOrderDao {

    @Query("SELECT * FROM book_group_orders WHERE groupId = :groupId")
    fun getByGroup(groupId: Long): List<BookGroupOrder>

    @Query("SELECT MIN(`order`) FROM book_group_orders WHERE groupId = :groupId")
    fun minOrder(groupId: Long): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(vararg order: BookGroupOrder)

    @Query("DELETE FROM book_group_orders WHERE groupId = :groupId AND bookUrl = :bookUrl")
    fun delete(groupId: Long, bookUrl: String)

    @Query("DELETE FROM book_group_orders WHERE groupId = :groupId AND bookUrl IN (:bookUrls)")
    fun deleteByGroupAndBooks(groupId: Long, bookUrls: List<String>)

    @Query("DELETE FROM book_group_orders WHERE bookUrl = :bookUrl")
    fun deleteByBook(bookUrl: String)
}
