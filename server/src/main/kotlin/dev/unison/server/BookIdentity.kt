package dev.unison.server

import java.text.Normalizer

/** Comparison aliases, never persistence keys. Existing work IDs and manual choices stay stable. */
internal object BookIdentity {
    data class Book(val title: String, val authors: List<String>, val series: List<SeriesRef> = emptyList())

    private val differentText = Regex("\\b(abridged|dramati[sz]ed|radio drama|full cast|adaptation|graphic novel|illustrated)\\b", RegexOption.IGNORE_CASE)
    private val collection = Regex("\\b(\\d+) (?:short stories|dark tales|stories|tales)\\b", RegexOption.IGNORE_CASE)
    private fun letters(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKD).lowercase()
        .replace("&", " and ").replace(Regex("[^a-z0-9]"), "")

    fun author(value: String): String {
        val parts = value.split(',')
        val name = if (parts.size == 2 && !parts[0].trim().contains(' ')) "${parts[1]} ${parts[0]}" else value
        return letters(name)
    }

    fun authors(values: List<String>): Set<String> = values.flatMap { it.split(Regex("\\s+[&;]\\s+")) }
        .map(::author).filter { it.isNotBlank() && it !in setOf("unknown", "unknownauthor") }.toSet()

    private fun title(value: String): String {
        var t = value.trim().replace(Regex("^(?:book\\s+)?\\d+(?:\\.\\d+)?\\s*[-–:]\\s*", RegexOption.IGNORE_CASE), "")
        repeat(3) {
            t = t.replace(Regex("\\s*\\((?:unabridged|retail)\\)\\s*$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*\\[\\d[\\d-]{8,16}[\\dXx]\\]\\s*$"), "")
                .replace(Regex("\\s*[:,-]?\\s*(?:a novel|(?:special )?\\d+(?:st|nd|rd|th) anniversary edition|\\(?\\d+ (?:short stories|dark tales|stories|tales)\\)?)\\s*$", RegexOption.IGNORE_CASE), "").trim()
        }
        return letters(t.replace(Regex("^(the|a|an)\\s+", RegexOption.IGNORE_CASE), ""))
    }

    private fun titles(book: Book, knownSeries: List<SeriesRef>): Set<String> {
        var name = book.title
        val segments = name.split(" - ")
        if (segments.size > 1 && author(segments.first()) in authors(book.authors)) name = segments.drop(1).joinToString(" - ")
        val candidates = mutableSetOf(title(name))
        for (s in knownSeries) {
            val sequence = s.sequence ?: continue
            val series = s.name.replace(Regex("^(the|a|an)\\s+", RegexOption.IGNORE_CASE), "").removeSuffix(" Series")
            if (series.isBlank()) continue
            val number = sequence.toString().removeSuffix(".0")
            val marker = "(?:the\\s+)?${Regex.escape(series)}(?:\\s+series)?\\s*,?\\s*(?:book\\s*|bk\\s*|#\\s*)${Regex.escape(number)}(?:\\.0)?"
            candidates += title(name.replace(Regex("\\s*[:,-]?\\s+$marker\\s*$", RegexOption.IGNORE_CASE), ""))
            candidates += title(name.replace(Regex("^$marker\\s*[-:]\\s*", RegexOption.IGNORE_CASE), ""))
        }
        return candidates.filter { it.isNotBlank() }.toSet()
    }

    private fun compatible(a: Book, b: Book): Boolean {
        fun edition(title: String) = differentText.findAll(title).map { it.value.lowercase().replace("dramatised", "dramatized") }.toSet()
        if (edition(a.title) != edition(b.title)) return false
        val ac = collection.find(a.title)?.groupValues?.get(1)
        val bc = collection.find(b.title)?.groupValues?.get(1)
        if (ac != null && bc != null && ac != bc) return false
        return a.series.none { x -> b.series.any { y -> title(x.name) == title(y.name) && x.sequence != null && y.sequence != null && x.sequence != y.sequence } }
    }

    fun sameBook(a: Book, b: Book): Boolean {
        if (!compatible(a, b) || authors(a.authors).intersect(authors(b.authors)).isEmpty()) return false
        val series = a.series + b.series
        if (titles(a, series).intersect(titles(b, series)).isNotEmpty()) return true
        // A series-only folder is useful only with the exact known series and volume.
        return series.any { s ->
            val n = s.sequence ?: return@any false
            val marker = Regex("^(?:the\\s+)?${Regex.escape(s.name.removePrefix("The "))}(?:\\s+series)?\\s*,?\\s*(?:book\\s*|bk\\s*|#\\s*)${Regex.escape(n.toString().removeSuffix(".0"))}(?:\\.0)?$", RegexOption.IGNORE_CASE)
            (marker.matches(a.title.trim()) && s in b.series) || (marker.matches(b.title.trim()) && s in a.series) ||
                (a.series.any { title(it.name) == title(s.name) && it.sequence == n } && b.series.any { title(it.name) == title(s.name) && it.sequence == n })
        }
    }

    fun matches(work: Work, requestedTitle: String, requestedAuthors: List<String>): Boolean {
        val a = Book(requestedTitle, requestedAuthors)
        val b = Book(work.title, work.authors + work.author, work.series)
        if (!compatible(a, b)) return false
        val expected = authors(requestedAuthors)
        if (expected.isNotEmpty() && !authors(b.authors).containsAll(expected)) return false
        return requestedTitle.isNotBlank() && (Scanner.titleKey(requestedTitle) == work.titleKey ||
            titles(a, b.series).intersect(titles(b, b.series)).isNotEmpty())
    }
}
