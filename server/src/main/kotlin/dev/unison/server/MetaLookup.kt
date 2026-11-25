package dev.unison.server

import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject

/** What a catalogue outside the house says about a book: something an admin can take into the overlay, field by field. */
@Serializable class MetaCandidate(
    val source: String, val title: String, val subtitle: String = "", val authors: List<String> = emptyList(), val narrators: List<String> = emptyList(),
    val series: List<SeriesRef> = emptyList(), val description: String = "", val publishedYear: String = "", val publisher: String = "",
    val language: String = "", val genres: List<String> = emptyList(), val cover: String = "",
)

/**
 * Looks a book up by title and author for the metadata overlay: Audible's public catalogue (narrators, series
 * and numbers, the publisher's summary, a cover) for audiobooks that came without a sidecar, and Open Library
 * for the rest. Only the title and author typed by the admin leave the house, and nothing is saved until the
 * admin chooses what to keep.
 */
class MetaLookup(private val audible: String = "https://api.audible.com", private val openLibrary: String = "https://openlibrary.org") {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build()
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun get(url: String): String {
        val r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Accept", "application/json").header("User-Agent", "UnisonServer").build(), HttpResponse.BodyHandlers.ofString())
        if (r.statusCode() != 200) error("answered ${r.statusCode()}")
        return r.body()
    }
    private fun JSONObject.text(key: String) = if (isNull(key)) "" else optString(key).trim()
    private fun JSONArray?.names(key: String = "name") = if (this == null) emptyList() else List(length()) { optJSONObject(it)?.optString(key).orEmpty().trim() }.filter { it.isNotBlank() }

    /**
     * A summary that came as HTML, as text with its paragraphs kept (line breaks and paragraph ends become blank lines).
     * Audible's whole summary needs the `product_extended_attrs` group: without it only a teaser cut short comes back.
     */
    private fun plain(html: String): String =
        org.jsoup.Jsoup.parse(html.replace(Regex("(?i)<br\\s*/?>|</p>|</li>"), " ¶ ")).text().split("¶").map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n\n")

    /** Candidates from both catalogues, Audible first; a catalogue that fails is left out and named in `errors`. */
    fun search(title: String, author: String, errors: MutableList<String> = ArrayList()): List<MetaCandidate> {
        require(title.isNotBlank()) { "A title to look up" }
        val out = ArrayList<MetaCandidate>()
        runCatching { out += audible(title, author) }.onFailure { errors += "Audible: ${it.message}" }
        runCatching { out += openLibrary(title, author) }.onFailure { errors += "Open Library: ${it.message}" }
        return out
    }

    /**
     * The same two catalogues asked with whatever a person typed (a title, or a title and an author together), for
     * looking a book up before asking the library for it. Only those words leave the house.
     */
    fun find(words: String, errors: MutableList<String> = ArrayList()): List<MetaCandidate> {
        require(words.isNotBlank()) { "A title to look up" }
        val out = ArrayList<MetaCandidate>()
        runCatching { out += audible("", "", words) }.onFailure { errors += "Audible: ${it.message}" }
        runCatching { out += openLibrary("", "", words) }.onFailure { errors += "Open Library: ${it.message}" }
        return out
    }

    private fun audible(title: String, author: String, words: String? = null): List<MetaCandidate> {
        val url = "$audible/1.0/catalog/products?num_results=6&products_sort_by=Relevance&response_groups=product_desc,product_extended_attrs,product_attrs,contributors,series,media,category_ladders&" + (if (words != null) "keywords=${enc(words)}" else "title=${enc(title)}" + (if (author.isNotBlank()) "&author=${enc(author)}" else ""))
        val products = JSONObject(get(url)).optJSONArray("products") ?: return emptyList()
        return List(products.length()) { products.getJSONObject(it) }.filter { it.text("title").isNotBlank() }.map { p ->
            val series = p.optJSONArray("series")?.let { a -> List(a.length()) { a.getJSONObject(it) }.map { s -> SeriesRef(s.text("title"), Regex("[0-9]+(?:\\.[0-9]+)?").find(s.text("sequence"))?.value?.toDoubleOrNull()) }.filter { it.name.isNotBlank() } } ?: emptyList()
            val genres = p.optJSONArray("category_ladders")?.let { a -> List(a.length()) { a.getJSONObject(it) }.flatMap { l -> l.optJSONArray("ladder").names() } }?.distinct()?.take(6) ?: emptyList()
            val images = p.optJSONObject("product_images")
            MetaCandidate(
                "Audible", p.text("title"), p.text("subtitle"), p.optJSONArray("authors").names(), p.optJSONArray("narrators").names(), series,
                plain(p.text("publisher_summary").ifBlank { p.text("merchandising_summary") }),
                (p.text("release_date").ifBlank { p.text("issue_date") }).take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty(),
                p.text("publisher_name"), p.text("language").replaceFirstChar { it.uppercase() }, genres,
                images?.let { i -> i.keys().asSequence().sortedByDescending { it.toIntOrNull() ?: 0 }.firstOrNull()?.let { i.optString(it) } }.orEmpty(),
            )
        }
    }

    private fun openLibrary(title: String, author: String, words: String? = null): List<MetaCandidate> {
        val url = "$openLibrary/search.json?limit=4&fields=key,title,subtitle,author_name,first_publish_year,publisher,subject,cover_i&" + (if (words != null) "q=${enc(words)}" else "title=${enc(title)}" + (if (author.isNotBlank()) "&author=${enc(author)}" else ""))
        val docs = JSONObject(get(url)).optJSONArray("docs") ?: return emptyList()
        fun strings(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.optString(it).trim() }.filter { it.isNotBlank() }
        return List(docs.length()) { docs.getJSONObject(it) }.filter { it.text("title").isNotBlank() }.map { d ->
            // The description lives on the work's own record; one more request per candidate, skipped quietly when it fails.
            val description = runCatching {
                val w = JSONObject(get("$openLibrary${d.text("key")}.json"))
                (w.optJSONObject("description")?.optString("value") ?: w.text("description")).replace(Regex("\\[(.*?)]\\[\\d+]|\\(\\[.*?]\\[\\d+]\\)"), "$1").substringBefore("\n----").trim()
            }.getOrDefault("")
            MetaCandidate(
                "Open Library", d.text("title"), d.text("subtitle"), strings(d.optJSONArray("author_name")), description = description,
                publishedYear = d.optInt("first_publish_year", 0).takeIf { it > 0 }?.toString().orEmpty(), publisher = strings(d.optJSONArray("publisher")).firstOrNull().orEmpty(),
                genres = strings(d.optJSONArray("subject")).filter { it.length <= 30 && ':' !in it && '=' !in it }.take(6),
                cover = d.optInt("cover_i", 0).takeIf { it > 0 }?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" }.orEmpty(),
            )
        }
    }

    /**
     * Fetches a cover a lookup offered into the server's own data folder. Only the image hosts of the two
     * catalogues are fetched from, over https, and only a real JPEG or PNG of a sane size is kept.
     */
    fun fetchCover(url: String, target: File): File {
        val (bytes, png) = image(url)
        target.parentFile.mkdirs()
        val file = File(target.parentFile, target.nameWithoutExtension + if (png) ".png" else ".jpg")
        file.writeBytes(bytes)
        return file
    }

    /** The bytes of a cover a lookup offered, and whether it is a PNG (else a JPEG). The admin's browser shows candidates through this, since the page loads images from here only. */
    fun image(url: String): Pair<ByteArray, Boolean> {
        val uri = runCatching { URI.create(url) }.getOrNull() ?: throw IllegalArgumentException("Not an address")
        require(uri.scheme == "https" && uri.host != null && COVER_HOSTS.any { uri.host == it || uri.host.endsWith(".$it") }) { "Covers come from Audible or Open Library only" }
        val r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("User-Agent", "UnisonServer").build(), HttpResponse.BodyHandlers.ofByteArray())
        require(r.statusCode() == 200) { "The cover's host answered ${r.statusCode()}" }
        val bytes = r.body()
        require(bytes.size in 1000..8_000_000) { "That is not a usable cover" }
        val png = bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte(); val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        require(png || jpeg) { "That is not a JPEG or PNG" }
        return bytes to png
    }

    companion object { val COVER_HOSTS = listOf("media-amazon.com", "ssl-images-amazon.com", "covers.openlibrary.org", "archive.org") }
}
