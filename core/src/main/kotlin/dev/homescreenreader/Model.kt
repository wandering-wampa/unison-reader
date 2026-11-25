package dev.homescreenreader

/** A sentence of the book. `block` groups the sentences of one paragraph; -1 when unknown (older catalogs). */
data class Passage(val text: String, val html: String, val section: Int, val offset: Int, val block: Int = -1)

data class Track(val uri: String, val name: String, val duration: Long)

data class Chapter(val title: String, val start: Long)

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val narrator: String = "",
    val passages: List<Passage> = emptyList(),
    val toc: List<Pair<String, Int>> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val cover: String = "",
    val ebookUri: String = "",
    val ebookFormat: String = "epub",
) {
    val duration
        get() = tracks.sumOf { it.duration }

    fun reordered(ordered: List<Track>): Book {
        require(ordered.map { it.uri }.sorted() == tracks.map { it.uri }.sorted())
        val original = mutableMapOf<String, Long>()
        var offset = 0L
        tracks.forEach {
            original[it.uri] = offset
            offset += it.duration
        }
        offset = 0
        val moved =
            ordered.flatMap { track ->
                val start = original.getValue(track.uri)
                val result =
                    chapters
                        .filter { it.start >= start && it.start < start + track.duration }
                        .map { it.copy(start = offset + it.start - start) }
                offset += track.duration
                result
            }
        return copy(tracks = ordered, chapters = moved)
    }
}

data class Anchor(val passage: Int, val start: Long, val end: Long, val confidence: Double)
