package io.legado.app.help.book

import io.legado.app.data.entities.Book
import java.util.Locale

object BookSort {

    private val wordCountRegex = Regex("""(\d+(?:\.\d+)?)\s*([\u4e07\u5343\u767e]?)""")
    private val randomRecommendWordRanges = listOf(
        0.0 to 50000.0,
        50000.0 to 100000.0,
        150000.0 to 250000.0,
        300000.0 to 450000.0,
        500000.0 to 950000.0,
        1000000.0 to 999999999.0
    )

    fun wordCountValue(book: Book): Double {
        val wordCount = book.wordCount ?: return 0.0
        val match = wordCountRegex.find(wordCount) ?: return 0.0
        val value = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return 0.0
        return value * when (match.groupValues.getOrNull(2)) {
            "\u4e07" -> 10000.0
            "\u5343" -> 1000.0
            "\u767e" -> 100.0
            else -> 1.0
        }
    }

    fun displayWordCount(book: Book): String? {
        val value = wordCountValue(book)
        if (value <= 0.0) {
            return null
        }
        return String.format(Locale.US, "%.1fw", value / 10000.0)
    }

    fun <T> pinnedFirst(list: List<T>, sorted: List<T>, bookOf: (T) -> Book?): List<T> {
        val pinnedUrls = list.asSequence()
            .mapNotNull(bookOf)
            .filter { it.pinTime > 0L }
            .sortedByDescending { it.pinTime }
            .map { it.bookUrl }
            .toList()
        if (pinnedUrls.isEmpty()) return sorted
        val pinnedUrlSet = pinnedUrls.toSet()
        val pinnedItems = pinnedUrls.mapNotNull { url ->
            list.firstOrNull { bookOf(it)?.bookUrl == url }
        }
        return pinnedItems + sorted.filterNot { bookOf(it)?.bookUrl in pinnedUrlSet }
    }

    fun matchesRandomRecommendWordRanges(book: Book, selectedRanges: Collection<Pair<Double, Double>>): Boolean {
        if (selectedRanges.isEmpty()) return true
        val value = wordCountValue(book)
        if (value <= 0.0) return false
        val range = randomRecommendWordRanges.firstOrNull { (min, max) ->
            value >= min && value <= max
        } ?: randomRecommendWordRanges.minBy { (min, max) ->
            when {
                value < min -> min - value
                value > max -> value - max
                else -> 0.0
            }
        }
        return selectedRanges.any { it.first == range.first && it.second == range.second }
    }

}
