package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "book_group_orders",
    primaryKeys = ["groupId", "bookUrl"],
    indices = [
        Index(value = ["bookUrl"]),
        Index(value = ["groupId"])
    ]
)
data class BookGroupOrder(
    val groupId: Long,
    val bookUrl: String,
    val order: Long
)
