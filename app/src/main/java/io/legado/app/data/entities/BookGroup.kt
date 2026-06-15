package io.legado.app.data.entities

import android.content.Context
import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import kotlinx.parcelize.Parcelize

@Suppress("ConstPropertyName")
@Parcelize
@Entity(tableName = "book_groups")
data class BookGroup(
    @PrimaryKey
    val groupId: Long = 0b1,
    var groupName: String = "",
    var cover: String? = null,
    var order: Int = 0,
    @ColumnInfo(defaultValue = "1")
    var enableRefresh: Boolean = true,
    @ColumnInfo(defaultValue = "1")
    var show: Boolean = true,
    @ColumnInfo(defaultValue = "-1")
    var bookSort: Int = -1,
    // 只更新已读
    @ColumnInfo(defaultValue = "0")
    var onlyUpdateRead: Boolean = false
) : Parcelable {

    companion object {
        const val IdRoot = -100L
        const val IdAll = -1L
        const val IdLocal = -2L
        const val IdAudio = -3L
        const val IdNetNone = -4L
        const val IdLocalNone = -5L
        const val IdVideo = -6L
        const val IdError = -11L
    }

    fun getManageName(context: Context): String {
        return when (groupId) {
            IdAll -> "$groupName(${context.getString(R.string.all)})"
            IdAudio -> "$groupName(${context.getString(R.string.audio)})"
            IdLocal -> "$groupName(${context.getString(R.string.local)})"
            IdNetNone -> "$groupName(${context.getString(R.string.net_no_group)})"
            IdLocalNone -> "$groupName(${context.getString(R.string.local_no_group)})"
            IdVideo -> "$groupName(${context.getString(R.string.video)})"
            IdError -> "$groupName(${context.getString(R.string.update_book_fail)})"
            else -> groupName
        }
    }

    fun getRealBookSort(): Int {
        val sort = if (bookSort < 0) AppConfig.bookshelfSort else bookSort
        // 手动排序(3)和综合(4)不再支持，回退到默认
        return if (sort == 1 || sort == 3 || sort == 4) 0 else sort
    }

    override fun hashCode(): Int {
        return groupId.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other is BookGroup) {
            return other.groupId == groupId
                    && other.groupName == groupName
                    && other.cover == cover
                    && other.bookSort == bookSort
                    && other.enableRefresh == enableRefresh
                    && other.onlyUpdateRead == onlyUpdateRead
                    && other.show == show
                    && other.order == order
        }
        return false
    }

}
