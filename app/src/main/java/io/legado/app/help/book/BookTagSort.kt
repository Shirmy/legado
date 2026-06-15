package io.legado.app.help.book

import io.legado.app.data.entities.BookTag
import io.legado.app.utils.cnCompare

object BookTagSort {

    fun sort(tags: List<BookTag>): List<BookTag> {
        return tags.sortedWith { left, right ->
            compare(left.name, right.name).takeIf { it != 0 }
                ?: left.order.compareTo(right.order).takeIf { it != 0 }
                ?: left.id.compareTo(right.id)
        }
    }

    private fun compare(left: String, right: String): Int {
        val leftInfo = SortInfo(left)
        val rightInfo = SortInfo(right)
        return leftInfo.top.compareTo(rightInfo.top).takeIf { it != 0 }
            ?: leftInfo.kind.compareTo(rightInfo.kind).takeIf { it != 0 }
            ?: leftInfo.sortName.cnCompare(rightInfo.sortName).takeIf { it != 0 }
            ?: left.cnCompare(right)
    }

    private class SortInfo(name: String) {
        val top = if (name.startsWith("!")) 0 else 1
        val sortName = name.removePrefix("!").trim()
        val kind = when {
            sortName.firstOrNull()?.isLetterAscii() == true -> 0
            sortName.firstOrNull()?.isChinese() == true -> 2
            else -> 1
        }
    }

    private fun Char.isLetterAscii(): Boolean {
        return this in 'A'..'Z' || this in 'a'..'z'
    }

    private fun Char.isChinese(): Boolean {
        return this in '\u4e00'..'\u9fff'
    }
}
