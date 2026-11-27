package dev.homescreenreader

import android.content.Context
import android.net.Uri
import android.webkit.*
import org.json.*

class FormattedReader(
    context: Context,
    private val book: Book,
    val isCacheRenderer: Boolean = false,
    private val selection: (Int) -> Unit,
) : WebView(context) {
    var isReaderReady = false
        private set
    var onTap: () -> Unit = {}
    var onReady: () -> Unit = {}
    var onPageChanged: (String) -> Unit = {}
    var onCachePage: (Int, Int, String) -> Unit = { _, _, _ -> }
    var onCacheDone: (Int) -> Unit = {}
    var onTurnReady: () -> Unit = {}
    var onPreviewReady: (Int, Boolean) -> Unit = { _, _ -> }
    var onPreviewResolved: (Int) -> Unit = {}
    var onLocation: (String, Double) -> Unit = { _, _ -> }
    /** The reader chose a sentence deliberately (long-press selection), as opposed to a page relocation. */
    var onChosen: (Int) -> Unit = {}
    /** The selection menu's Highlight: the first and last sentence the selection touches. */
    var onHighlight: (Int, Int) -> Unit = { _, _ -> }
    /** The selection menu's Note: the first and last sentence the selection touches. */
    var onNote: (Int, Int) -> Unit = { _, _ -> }
    /** The selection menu's Listen (only in a book with audio): the first and last sentence the selection touches. */
    var onListen: (Int, Int) -> Unit = { _, _ -> }
    /** When true, the page flows as one scrolled column regardless of the saved reading mode. */
    var scrollOverride = false

    companion object {
        /**
         * Page-grid shift (section index to extra top padding in px) for each open book, chosen when a
         * turn leaves smooth follow so the next page begins where the screen ended. Shared with the
         * cache renderer through the style, and dropped when the book is opened again.
         */
        private val gridShifts = HashMap<String, Pair<Int, Double>>()
    }
    /**
     * The menu over a long-press selection gains Define and Translate, answered by the phone's own
     * dictionary and translator (the system's define and translate actions).
     */
    override fun startActionMode(callback: android.view.ActionMode.Callback, type: Int): android.view.ActionMode? =
        super.startActionMode(if (isCacheRenderer) callback else LookupMenu(callback), type)

    private inner class LookupMenu(private val inner: android.view.ActionMode.Callback) : android.view.ActionMode.Callback2() {
        private val define = 0x55AA01; private val translate = 0x55AA02; private val highlight = 0x55AA03; private val note = 0x55AA04; private val listen = 0x55AA05
        private fun add(menu: android.view.Menu) {
            if (menu.findItem(highlight) == null) menu.add(android.view.Menu.NONE, highlight, 0, "Highlight").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
            if (menu.findItem(note) == null) menu.add(android.view.Menu.NONE, note, 1, "Note").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
            if (book.tracks.isNotEmpty() && menu.findItem(listen) == null) menu.add(android.view.Menu.NONE, listen, 2, "Listen").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
            if (menu.findItem(define) == null) menu.add(android.view.Menu.NONE, define, 3, "Define").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
            if (menu.findItem(translate) == null) menu.add(android.view.Menu.NONE, translate, 4, "Translate").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        /**
         * The floating toolbar fills its first row in menu order and sends items marked NEVER to the overflow, so an app's
         * items cannot jump ahead of the system's by order alone: the first row is Copy, Highlight, Note and Listen, and
         * every other item (Share, Select all, Define, Translate, whatever the system adds) goes behind the arrow. Nothing is removed.
         */
        private fun arrange(menu: android.view.Menu) {
            val items = (0 until menu.size()).map { menu.getItem(it) }
            val ours = setOf(highlight, note, listen, define, translate)
            val copy = items.firstOrNull { it.itemId == android.R.id.copy }
                ?: items.firstOrNull { it.itemId !in ours && runCatching { resources.getResourceEntryName(it.itemId) }.getOrNull()?.contains("copy", ignoreCase = true) == true }
                ?: items.firstOrNull { it.itemId !in ours }
            for (item in items) {
                if (item === copy) continue
                if (item.itemId == highlight || item.itemId == note || item.itemId == listen) item.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                else item.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
            }
        }
        override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = inner.onCreateActionMode(mode, menu).also { if (it) { add(menu); arrange(menu) } }
        override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = inner.onPrepareActionMode(mode, menu).also { add(menu); arrange(menu) }
        override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
            if (item.itemId == highlight) { selectedSpan({ mode.finish() }, onHighlight); return true }
            if (item.itemId == note) { selectedSpan({ mode.finish() }, onNote); return true }
            if (item.itemId == listen) { selectedSpan({ mode.finish() }, onListen); return true }
            if (item.itemId != define && item.itemId != translate) return inner.onActionItemClicked(mode, item)
            // The menu closes (and the selection with it) only after the page has answered with the selected words.
            lookUp(item.itemId == translate) { mode.finish() }; return true
        }
        override fun onDestroyActionMode(mode: android.view.ActionMode) = inner.onDestroyActionMode(mode)
        override fun onGetContentRect(mode: android.view.ActionMode, view: android.view.View, outRect: android.graphics.Rect) {
            (inner as? android.view.ActionMode.Callback2)?.onGetContentRect(mode, view, outRect) ?: super.onGetContentRect(mode, view, outRect)
        }
    }

    /** Closes the menu, then tells [then] the first and last sentence the selection touches. */
    private fun selectedSpan(done: () -> Unit, then: (Int, Int) -> Unit) = evaluateJavascript("window.readerSelectionSpan()") { raw ->
        val span = runCatching { JSONArray(raw) }.getOrNull()
        done()
        val first = span?.optInt(0, -1) ?: -1; val last = span?.optInt(1, -1) ?: -1
        if (first >= 0 && last >= first) then(first, last)
    }

    private fun lookUp(translate: Boolean, done: () -> Unit) = evaluateJavascript("window.readerSelection()") { raw ->
        val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        done()
        if (text.isEmpty()) return@evaluateJavascript
        val tries = if (translate) listOf(
            android.content.Intent(android.content.Intent.ACTION_TRANSLATE).putExtra(android.content.Intent.EXTRA_TEXT, text),
            android.content.Intent(android.content.Intent.ACTION_PROCESS_TEXT).setType("text/plain").setPackage("com.google.android.apps.translate")
                .putExtra(android.content.Intent.EXTRA_PROCESS_TEXT, text).putExtra(android.content.Intent.EXTRA_PROCESS_TEXT_READONLY, true),
        ) else listOf(
            android.content.Intent(android.content.Intent.ACTION_DEFINE).putExtra(android.content.Intent.EXTRA_TEXT, text),
            android.content.Intent(android.content.Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, "define $text"),
        )
        // Its own task: opened inside the reader's task, the Google dictionary keeps the Back key and the book cannot be reached.
        if (tries.none { runCatching { context.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess })
            android.widget.Toast.makeText(context, if (translate) "No translator on this phone" else "No dictionary on this phone", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun gridShiftKey() =gridShifts[book.id]?.let { "${it.first}:${it.second}" } ?: ""

    init {
        if (!isCacheRenderer) gridShifts.remove(book.id)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        setBackgroundColor(ReadingColors.background(context, ReadingState(context)))
        addJavascriptInterface(
            object {
                @JavascriptInterface
                fun select(p: Int) {
                    if (p in book.passages.indices) post { selection(p) }
                }

                @JavascriptInterface
                fun chosen(p: Int) {
                    if (p in book.passages.indices) post { onChosen(p) }
                }

                @JavascriptInterface
                fun ready() {
                    post { isReaderReady = true; onReady() }
                }

                @JavascriptInterface fun tap() { post { onTap() } }
                @JavascriptInterface fun turned() { post { onTurnReady() } }
                @JavascriptInterface fun pageChanged(cfi: String) { post { onPageChanged(cfi) } }
                @JavascriptInterface fun cachePage(id: Int, offset: Int, cfi: String) { post { onCachePage(id, offset, cfi) } }
                @JavascriptInterface fun cacheDone(id: Int) { post { onCacheDone(id) } }
                @JavascriptInterface fun previewReady(token: Int, changed: Boolean) { post { onPreviewReady(token, changed) } }
                @JavascriptInterface fun previewResolved(token: Int) { post { onPreviewResolved(token) } }
                @JavascriptInterface fun gridShift(index: Int, px: Double) { post { if (index >= 0 && px > 0) gridShifts[book.id] = index to px else gridShifts.remove(book.id) } }
                @JavascriptInterface fun location(chapter: String, fraction: Double) {
                    post { onLocation(chapter, fraction.coerceIn(0.0, 1.0)) }
                }

                @JavascriptInterface
                fun error(message: String) {
                    android.util.Log.e("ReaderWeb", message)
                }
            },
            "ReaderBridge",
        )
        webChromeClient =
            object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    android.util.Log.d("ReaderWeb", message.message())
                    return true
                }
            }
        webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    request.isForMainFrame && request.url.host != "reader.local"

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse {
                    return try {
                        require(request.url.host == "reader.local")
                        val path = request.url.path.orEmpty()
                        when (path) {
                            "/book.epub" ->
                                WebResourceResponse(
                                    "application/octet-stream",
                                    null,
                                    context.contentResolver.openInputStream(
                                        Uri.parse(book.ebookUri)
                                    ),
                                )
                            "/book-info" -> {
                                val s = ReadingState(context)
                                val color: (Int) -> String = { v -> "#%06x".format(v and 0xffffff) }
                                val j =
                                    JSONObject()
                                        .put("kindle", book.ebookFormat != "epub")
                                        .put("cacheOnly", isCacheRenderer)
                                        .put("title", book.title)
                                        .put("selected", s.passage(book.id))
                                        .put("highlightActive", s.follow(book.id) && PlaybackService.playingBook() == book.id && PlaybackService.playing())
                                        .put("font", s.font)
                                        .put("serif", s.serif)
                                        .put("style", appearance(s))
                                        .put(
                                            "background",
                                            color(ReadingColors.background(context, s)),
                                        )
                                        .put(
                                            "foreground",
                                            color(ReadingColors.foreground(context, s)),
                                        )
                                        .put("highlight", color(ReadingColors.highlight(context)))
                                        .put(
                                            "passages",
                                            JSONArray(
                                                (if (isCacheRenderer) emptyList() else book.passages).mapIndexed { i, p ->
                                                    JSONObject()
                                                        .put("id", i)
                                                        .put("section", p.section)
                                                        .put("text", p.text)
                                                }
                                            ),
                                        )
                                WebResourceResponse(
                                    "application/json",
                                    "utf-8",
                                    j.toString().byteInputStream(),
                                )
                            }
                            else -> {
                                require(!path.contains(".."))
                                WebResourceResponse(
                                    when { path.endsWith(".js") -> "text/javascript"; path.endsWith(".ttf") -> "font/ttf"; else -> "text/html" },
                                    "utf-8",
                                    context.assets.open(path.removePrefix("/")),
                                ).apply { responseHeaders = mapOf("Access-Control-Allow-Origin" to "*") }
                            }
                        }
                    } catch (e: Exception) {
                        WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            404,
                            "Unavailable",
                            emptyMap(),
                            byteArrayOf().inputStream(),
                        )
                    }
                }
            }
        loadUrl("https://reader.local/reader.html")
    }

    /** Moves to (and, while narration plays, highlights) a sentence; returns whether it was highlighted. */
    fun go(p: Int): Boolean {
        val highlight = ReadingState(context).follow(book.id) && PlaybackService.playingBook() == book.id && PlaybackService.playing()
        if (isReaderReady) evaluateJavascript("window.readerGo($p,$highlight)", null)
        return highlight && isReaderReady
    }

    /** Asks the page for its first and last visible sentence; -1 values mean unknown. */
    fun visible(callback: (Int, Int) -> Unit) {
        if (!isReaderReady) { callback(-1, -1); return }
        evaluateJavascript("window.readerVisible()") { raw ->
            val pair = runCatching { org.json.JSONArray(org.json.JSONTokener(raw).nextValue().toString()) }.getOrNull()
            callback(pair?.optInt(0, -1) ?: -1, pair?.optInt(1, -1) ?: -1)
        }
    }

    /** The person's own highlighted sentences, drawn in the page. */
    fun setMarks(ids: List<Int>) {
        if (isReaderReady) evaluateJavascript("window.readerMarks(${JSONArray(ids)})", null)
    }

    /** Marks the sentence a search result opened; -1 clears it. */
    fun found(p: Int) {
        if (isReaderReady) evaluateJavascript("window.readerFound($p)", null)
    }

    fun clearHighlight() {
        if (isReaderReady) evaluateJavascript("window.readerClear()", null)
    }

    /** Drops a long-press text selection, which also closes the Copy menu riding on it. */
    fun clearSelection() {
        if (isReaderReady) evaluateJavascript("window.readerClearSelection()", null)
    }

    /** Turns a page; with [exitFollow], the smooth-follow flow gives way to pages underneath the turn. */
    fun step(direction: Int, exitFollow: Boolean = false) {
        evaluateJavascript("window.readerStep($direction,$exitFollow)", null)
    }

    fun preview(direction: Int, token: Int, target: String? = null, exitFollow: Boolean = false) =
        evaluateJavascript("window.readerPreview($direction,$token,${target?.let(JSONObject::quote) ?: "null"},$exitFollow)", null)
    fun cacheRenderer() = FormattedReader(context, book, true) {}
    fun prepareCache(cfi: String, token: Int, offsets: List<Int>) = evaluateJavascript(
        "window.readerCache(${JSONObject.quote(cfi)},$token,${JSONArray(offsets)},${appearance(ReadingState(context))})", null)
    fun cacheCaptured(token: Int) = evaluateJavascript("window.readerCacheCaptured($token)", null)
    fun cancelCache() = evaluateJavascript("window.readerCancelCache?.()", null)
    fun resolvePreview(token: Int, commit: Boolean) = evaluateJavascript("window.readerResolve($token,$commit)", null)
    fun afterPaint(action: () -> Unit) {
        postVisualStateCallback(0, object : VisualStateCallback() {
            override fun onComplete(requestId: Long) { postOnAnimation { action() } }
        })
    }

    fun restyle() {
        val state = ReadingState(context)
        setBackgroundColor(ReadingColors.background(context, state))
        if (isReaderReady) evaluateJavascript("window.readerStyle(${appearance(state)})", null)
    }

    private fun appearance(s: ReadingState): JSONObject {
        fun color(value: Int) = "#%06x".format(value and 0xffffff)
        return JSONObject().put("font", s.font).put("typeface", s.typeface)
            .put("lineHeight", s.lineHeight / 100.0).put("margin", s.margin)
            .put("paragraphSpace", s.paragraphSpace).put("justify", s.justify)
            .put("scroll", s.scrollMode || scrollOverride).put("background", color(ReadingColors.background(context, s)))
            .put("shiftIndex", gridShifts[book.id]?.first ?: -1).put("shiftPx", gridShifts[book.id]?.second ?: 0.0)
            .put("foreground", color(ReadingColors.foreground(context, s)))
            .put("muted", color(androidx.core.graphics.ColorUtils.blendARGB(ReadingColors.foreground(context, s), ReadingColors.background(context, s), .4f)))
            .put("highlight", color(ReadingColors.highlight(context)))
    }

    fun contents() {
        evaluateJavascript("window.readerToc()") { result ->
            runCatching {
                val labels = JSONArray(JSONTokener(result).nextValue().toString())
                com.google.android.material.dialog
                    .MaterialAlertDialogBuilder(context)
                    .setTitle("Contents")
                    .setItems(Array(labels.length()) { labels.getString(it) }) { _, index ->
                        evaluateJavascript("window.readerTocGo($index)", null)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
}
