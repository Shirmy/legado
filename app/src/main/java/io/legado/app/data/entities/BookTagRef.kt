package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "book_tag_refs",
    primaryKeys = ["bookUrl", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["bookUrl"],
            childColumns = ["bookUrl"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BookTag::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("bookUrl"),
        Index("tagId"),
        Index(value = ["tagId", "bookUrl"])
    ]
)
data class BookTagRef(
    @ColumnInfo(defaultValue = "")
    val bookUrl: String,
    @ColumnInfo(defaultValue = "0")
    val tagId: Long
)
