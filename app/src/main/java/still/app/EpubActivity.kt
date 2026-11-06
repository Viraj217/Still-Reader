@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package still.app

import android.app.AlertDialog
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.util.zip.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.readium.r2.navigator.*
import org.readium.r2.navigator.epub.*
import org.readium.r2.navigator.preferences.*
import org.readium.r2.shared.publication.*
import org.readium.r2.shared.util.*
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

class EpubActivity : FragmentActivity() {
    private val store by lazy { Store(this) }
    private lateinit var book: ShelfBook
    private var nav: EpubNavigatorFragment? = null
    private var publication: Publication? = null
    private lateinit var controls: LinearLayout

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = column()
        controls = LinearLayout(this)
        val frame = FrameLayout(this).apply { id = View.generateViewId() }
        root.addView(controls)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        lifecycleScope.launch {
            try {
                book =
                    withContext(Dispatchers.IO) {
                        store.books().first { it.id == intent.getStringExtra("id") }
                    }
                val safe = withContext(Dispatchers.IO) { safeEpub(File(book.path)) }
                val http = DefaultHttpClient()
                val retriever = AssetRetriever(contentResolver, http)
                val asset = retriever.retrieve(safe.toUrl()).getOrElse { error(it.toString()) }
                val pub =
                    PublicationOpener(
                            DefaultPublicationParser(
                                this@EpubActivity,
                                http,
                                retriever,
                                pdfFactory = null,
                            )
                        )
                        .open(asset, allowUserInteraction = false)
                        .getOrElse { error(it.toString()) }
                publication = pub
                val factory =
                    EpubNavigatorFactory(pub)
                        .createFragmentFactory(
                            initialLocator =
                                book.position
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { Locator.fromJSON(JSONObject(it)) },
                            initialPreferences = preferences(),
                            configuration =
                                EpubNavigatorFragment.Configuration(
                                    selectionActionModeCallback =
                                        object : ActionMode.Callback {
                                            override fun onCreateActionMode(
                                                mode: ActionMode,
                                                menu: Menu,
                                            ): Boolean {
                                                menu.add("Highlight")
                                                menu.add("Note")
                                                return true
                                            }

                                            override fun onPrepareActionMode(
                                                mode: ActionMode,
                                                menu: Menu,
                                            ) = false

                                            override fun onActionItemClicked(
                                                mode: ActionMode,
                                                item: MenuItem,
                                            ): Boolean {
                                                annotate(item.title == "Note")
                                                return true
                                            }

                                            override fun onDestroyActionMode(mode: ActionMode) {}
                                        }
                                ),
                        )
                supportFragmentManager.fragmentFactory = factory
                val fragment =
                    factory.instantiate(classLoader, EpubNavigatorFragment::class.java.name)
                        as EpubNavigatorFragment
                supportFragmentManager.beginTransaction().replace(frame.id, fragment).commitNow()
                nav = fragment
                controls.addView(button("‹") { finish() })
                controls.addView(
                    button("Contents") {
                        val links = pub.tableOfContents.ifEmpty { pub.readingOrder }
                        AlertDialog.Builder(this@EpubActivity)
                            .setItems(
                                links.map { it.title ?: it.href.toString() }.toTypedArray()
                            ) { _, i ->
                                fragment.go(links[i])
                            }
                            .show()
                    }
                )
                controls.addView(button("Aa") { appearance() })
                controls.addView(
                    button("Bookmark") { saveMark("bookmark", fragment.currentLocator.value, "") }
                )
                controls.addView(button("Notes") { marks() })
                controls.addView(button("Find") { find() })
                controls.addView(button("Hide") { controls.visibility = View.GONE })
                fragment.addInputListener(
                    object : org.readium.r2.navigator.input.InputListener {
                        override fun onTap(
                            event: org.readium.r2.navigator.input.TapEvent
                        ): Boolean {
                            controls.visibility =
                                if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                            return true
                        }
                    }
                )
                highlights()
                launch {
                    fragment.currentLocator.collect { loc ->
                        withContext(Dispatchers.IO) {
                            store.progress(book.id, loc.toJSON().toString())
                        }
                    }
                }
            } catch (e: Exception) {
                root.addView(label("Cannot open EPUB: ${e.message}"))
                root.addView(button("Back") { finish() })
            }
        }
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }

    private fun preferences(): EpubPreferences {
        val p = getSharedPreferences("reader", 0)
        return EpubPreferences(
            // Readium fontSize range: 0.1..5.0 (1.0 = 100%). UI stores integer percent values.
            fontSize = p.getInt("font", 100) / 100.0,
            fontFamily = p.getString("family", "")!!.takeIf { it.isNotBlank() }?.let { FontFamily(it) },
            // Readium lineHeight range: 1.0..2.0. UI stores tenths (15 = 1.5).
            lineHeight = p.getInt("line", 15) / 10.0,
            pageMargins = p.getInt("margin", 10) / 10.0,
            publisherStyles = p.getBoolean("publisher_styles", true),
            theme =
                when (p.getInt("theme", 0)) {
                    1 -> Theme.DARK
                    2 -> Theme.SEPIA
                    else -> Theme.LIGHT
                },
            scroll = p.getBoolean("scroll", false),
        )
    }

    private fun appearance() {
        val options =
            arrayOf(
                "Larger type",
                "Smaller type",
                "Light / Dark / Sepia",
                "Serif / Sans / Default",
                "Line height",
                "Margins",
                "Scroll / Pages",
                "Publisher styles on/off",
            )
        AlertDialog.Builder(this)
            .setItems(options) { _, i ->
                val p = getSharedPreferences("reader", 0)
                val e = p.edit()
                when (i) {
                    // Font stored as integer percent (10–500); Readium scale = stored/100.0
                    0 -> e.putInt("font", (p.getInt("font", 100) + 10).coerceAtMost(500))
                    1 -> e.putInt("font", (p.getInt("font", 100) - 10).coerceAtLeast(10))
                    2 -> e.putInt("theme", (p.getInt("theme", 0) + 1) % 3)
                    3 -> {
                        val current = p.getString("family", "")!!
                        val next = when (current) {
                            "" -> "serif"
                            "serif" -> "sans-serif"
                            else -> ""
                        }
                        e.putString("family", next)
                    }
                    // Line height stored as tenths (10–20); Readium range 1.0..2.0
                    4 ->
                        e.putInt(
                            "line",
                            if (p.getInt("line", 15) >= 20) 10 else p.getInt("line", 15) + 2,
                        )
                    5 ->
                        e.putInt(
                            "margin",
                            if (p.getInt("margin", 10) >= 20) 5 else p.getInt("margin", 10) + 5,
                        )
                    6 -> e.putBoolean("scroll", !p.getBoolean("scroll", false))
                    7 -> e.putBoolean("publisher_styles", !p.getBoolean("publisher_styles", true))
                }
                e.apply()
                nav?.submitPreferences(preferences())
            }
            .show()
    }

    private fun annotate(note: Boolean) {
        lifecycleScope.launch {
            val loc = nav?.currentSelection()?.locator ?: return@launch
            if (note) prompt("Note") { saveMark("note", loc, it) }
            else saveMark("highlight", loc, loc.text.highlight ?: "")
        }
    }

    private fun saveMark(kind: String, loc: Locator?, text: String) {
        if (loc == null) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { store.mark(book.id, kind, loc.toJSON().toString(), text) }
            highlights()
            Toast.makeText(this@EpubActivity, "Saved", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun highlights() {
        val marks = withContext(Dispatchers.IO) { store.marks(book.id) }
        nav?.applyDecorations(
            marks
                .filter { it.kind != "bookmark" }
                .mapNotNull { m ->
                    Locator.fromJSON(JSONObject(m.location))?.let {
                        Decoration(
                            m.id.toString(),
                            it,
                            Decoration.Style.Highlight(android.graphics.Color.rgb(220, 192, 115)),
                        )
                    }
                },
            "notes",
        )
    }

    private fun marks() {
        lifecycleScope.launch {
            val marks = withContext(Dispatchers.IO) { store.marks(book.id) }
            AlertDialog.Builder(this@EpubActivity)
                .setItems(
                    marks.map { "${it.kind}: ${it.text.ifBlank {"Saved position"}}" }.toTypedArray()
                ) { _, i ->
                    Locator.fromJSON(JSONObject(marks[i].location))?.let { nav?.go(it) }
                }
                .setNeutralButton("Export JSON") { _, _ -> exportMarks() }
                .show()
        }
    }

    private fun exportMarks() {
        startActivityForResult(
            android.content
                .Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
                .setType("application/json")
                .putExtra(android.content.Intent.EXTRA_TITLE, "${book.title.take(50)}-notes.json"),
            44,
        )
    }

    @Deprecated("Android callback")
    override fun onActivityResult(request: Int, result: Int, data: android.content.Intent?) {
        super.onActivityResult(request, result, data)
        if (request == 44 && result == RESULT_OK)
            data?.data?.let { uri ->
                lifecycleScope.launch(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(
                            org.json
                                .JSONArray(
                                    store.marks(book.id).map { m ->
                                        JSONObject()
                                            .put("kind", m.kind)
                                            .put("locator", JSONObject(m.location))
                                            .put("text", m.text)
                                    }
                                )
                                .toString(2)
                        )
                    }
                }
            }
    }

    private fun find() {
        prompt("Search book") { query ->
            lifecycleScope.launch {
                val pub = publication ?: return@launch
                val found =
                    withContext(Dispatchers.IO) {
                        ZipFile(safeEpub(File(book.path))).use { zip ->
                            pub.readingOrder.filter { link ->
                                val path =
                                    java.net.URLDecoder.decode(
                                        link.href.toString().substringBefore('#'),
                                        "UTF-8",
                                    )
                                zip.getEntry(path)?.let { e ->
                                    zip.getInputStream(e).bufferedReader().use {
                                        org.jsoup.Jsoup.parse(it.readText())
                                            .text()
                                            .contains(query, true)
                                    }
                                } ?: false
                            }
                        }
                    }
                AlertDialog.Builder(this@EpubActivity)
                    .setTitle("Chapters containing “$query”")
                    .setItems(found.map { it.title ?: it.href.toString() }.toTypedArray()) { _, i ->
                        nav?.go(found[i])
                    }
                    .show()
            }
        }
    }

    private fun safeEpub(file: File): File {
        val target = File(cacheDir, file.nameWithoutExtension + "-safe.epub")
        // Invalidate the cached copy if the source file is newer (e.g. re-downloaded)
        if (target.exists() && target.lastModified() >= file.lastModified()) return target
        val temp = File(target.path + ".tmp")
        ZipFile(file).use { zip ->
            ZipOutputStream(temp.outputStream()).use { out ->
                // EPUB spec §3.4: mimetype must be the first file, uncompressed (STORED), no extra fields
                zip.getEntry("mimetype")?.let { mimetypeEntry ->
                    val data = zip.getInputStream(mimetypeEntry).use { it.readBytes() }
                    val stored = ZipEntry("mimetype").apply {
                        method = ZipEntry.STORED
                        size = data.size.toLong()
                        compressedSize = data.size.toLong()
                        crc = java.util.zip.CRC32().also { it.update(data) }.value
                    }
                    out.putNextEntry(stored)
                    out.write(data)
                    out.closeEntry()
                }
                zip.entries().asSequence().forEach { entry ->
                    if (entry.name == "mimetype") return@forEach // already written first
                    if (!entry.isDirectory) {
                        val data = zip.getInputStream(entry).use { it.readBytes() }
                        val isHtml =
                            entry.name.endsWith(".xhtml") ||
                                entry.name.endsWith(".html") ||
                                entry.name.endsWith(".htm")
                        val clean =
                            if (isHtml) {
                                val doc =
                                    org.jsoup.Jsoup.parse(
                                        data.toString(Charsets.UTF_8),
                                        "",
                                        org.jsoup.parser.Parser.xmlParser(),
                                    )
                                doc.select("script,iframe,object,embed,form").remove()
                                doc.allElements.forEach { el ->
                                    el.attributes()
                                        .asList()
                                        .filter {
                                            it.key.startsWith("on", true) ||
                                                it.value.trim().startsWith("javascript:", true)
                                        }
                                        .forEach { el.removeAttr(it.key) }
                                }
                                doc.select("head")
                                    .prepend(
                                        "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; font-src 'self'; script-src 'unsafe-inline'\" />"
                                    )
                                doc.outerHtml().toByteArray()
                            } else data
                        out.putNextEntry(ZipEntry(entry.name))
                        out.write(clean)
                        out.closeEntry()
                    }
                }
            }
        }
        check(temp.renameTo(target))
        return target
    }

    override fun onDestroy() {
        super.onDestroy()
        publication?.close()
        store.close()
    }
}
