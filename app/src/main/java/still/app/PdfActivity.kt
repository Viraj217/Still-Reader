package still.app

import android.app.AlertDialog
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.*
import android.view.*
import android.widget.*
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlinx.coroutines.*
import org.json.*

class PdfActivity : FragmentActivity() {
    private val store by lazy { Store(this) }
    private lateinit var book: ShelfBook
    private var renderer: PdfRenderer? = null
    private lateinit var canvas: PageView
    private lateinit var bar: LinearLayout
    private lateinit var pageLabel: TextView
    private var page = 0
    private var renderJob: Job? = null
    private val lock = Any()
    private var vertical = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PDFBoxResourceLoader.init(applicationContext)
        val root = column()
        bar = LinearLayout(this)
        root.addView(bar)
        canvas = PageView()
        root.addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        bar.addView(button("‹") { finish() })
        bar.addView(button("Tools") { tools() })
        bar.addView(button("Previous") { show(page - 1) })
        pageLabel = label("Loading")
        bar.addView(pageLabel)
        bar.addView(button("Next") { show(page + 1) })
        bar.addView(button("Hide") { bar.visibility = View.GONE })
        lifecycleScope.launch {
            try {
                book =
                    withContext(Dispatchers.IO) {
                        store.books().first { it.id == intent.getStringExtra("id") }
                    }
                renderer =
                    withContext(Dispatchers.IO) {
                        PdfRenderer(
                            ParcelFileDescriptor.open(
                                File(book.path),
                                ParcelFileDescriptor.MODE_READ_ONLY,
                            )
                        )
                    }
                page = state?.getInt("page") ?: book.position.toIntOrNull() ?: 0
                show(page)
            } catch (e: Exception) {
                pageLabel.text = "Cannot open: ${e.message}"
            }
        }
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }

    private fun show(n: Int) {
        val r = renderer ?: return
        if (n !in 0 until r.pageCount) return
        page = n
        pageLabel.text = "${page+1} / ${r.pageCount}"
        renderJob?.cancel()
        renderJob =
            lifecycleScope.launch {
                // Fetch marks outside the synchronized render block to avoid holding the lock
                val pageMarks =
                    withContext(Dispatchers.IO) {
                        store.marks(book.id).filter { it.location == n.toString() }
                    }
                val bitmap =
                    withContext(Dispatchers.IO) {
                        synchronized(lock) {
                            r.openPage(n).use { p ->
                                // Scale to screen density: target full display width at device DPI,
                                // capped at 2160 wide to bound memory on this tablet.
                                val displayMetrics = resources.displayMetrics
                                val screenW = displayMetrics.widthPixels
                                val targetW = screenW.coerceAtMost(2160)
                                val targetH =
                                    (targetW.toDouble() * p.height / p.width)
                                        .toInt()
                                        .coerceAtMost(3840)
                                val bmp =
                                    Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                                val prefs = getSharedPreferences("reader", 0)
                                val bgColor = when (prefs.getInt("theme", 0)) {
                                    1 -> Color.rgb(28, 28, 28)   // dark
                                    2 -> Color.rgb(251, 243, 226) // sepia
                                    else -> Color.WHITE
                                }
                                bmp.eraseColor(bgColor)
                                p.render(
                                    bmp,
                                    null,
                                    null,
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                )
                                bmp
                            }
                        }
                    }
                if (isActive) {
                    canvas.bitmap?.recycle()
                    canvas.bitmap = bitmap
                    canvas.marks = pageMarks
                    canvas.reset()
                    withContext(Dispatchers.IO) { store.progress(book.id, n.toString()) }
                } else bitmap.recycle()
            }
    }

    private fun tools() {
        val items =
            arrayOf(
                "Navigate to page",
                "Bookmark",
                "Note",
                "Draw ink",
                "Draw highlight",
                "Pan / read",
                "Annotations",
                "Search text",
                "Select page text",
                "Export annotations (JSON)",
                if (vertical) "Horizontal page gestures" else "Vertical page gestures",
                "Light / Dark / Sepia theme",
            )
        AlertDialog.Builder(this)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> prompt("Page number") { it.toIntOrNull()?.let { n -> show(n - 1) } }
                    1 -> save("bookmark", "")
                    2 -> prompt("Page note") { save("note", it) }
                    3 -> canvas.mode = "ink"
                    4 -> canvas.mode = "highlight"
                    5 -> canvas.mode = "read"
                    6 -> annotations()
                    7 -> prompt("Find text") { find(it) }
                    8 -> pageText()
                    9 ->
                        startActivityForResult(
                            android.content
                                .Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
                                .setType("application/json")
                                .putExtra(android.content.Intent.EXTRA_TITLE, "annotations.json"),
                            45,
                        )
                    10 -> vertical = !vertical
                    11 -> {
                        val p = getSharedPreferences("reader", 0)
                        p.edit().putInt("theme", (p.getInt("theme", 0) + 1) % 3).apply()
                        show(page) // re-render with new background
                    }
                }
            }
            .show()
    }

    private fun save(kind: String, text: String) {
        val loc = page.toString()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { store.mark(book.id, kind, loc, text) }
            canvas.marks =
                withContext(Dispatchers.IO) { store.marks(book.id).filter { it.location == loc } }
            canvas.invalidate()
        }
    }

    private fun annotations() {
        lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) { store.marks(book.id) }
            val kinds = arrayOf("All", "bookmark", "highlight", "ink", "note")
            AlertDialog.Builder(this@PdfActivity)
                .setTitle("Filter annotations")
                .setItems(kinds) { _, k ->
                    val marks = all.filter { k == 0 || it.kind == kinds[k] }
                    AlertDialog.Builder(this@PdfActivity)
                        .setItems(
                            marks
                                .map {
                                    "Page ${it.location.toInt()+1} · ${it.kind} ${if(it.kind=="note")it.text else ""}"
                                }
                                .toTypedArray()
                        ) { _, i ->
                            val m = marks[i]
                            AlertDialog.Builder(this@PdfActivity)
                                .setMessage(if (m.kind == "note") m.text else m.kind)
                                .setPositiveButton("Go") { _, _ -> show(m.location.toInt()) }
                                .setNegativeButton("Delete") { _, _ ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        store.removeMark(m.id)
                                        withContext(Dispatchers.Main) { show(page) }
                                    }
                                }
                                .show()
                        }
                        .show()
                }
                .show()
        }
    }

    private suspend fun text(page: Int): String =
        withContext(Dispatchers.IO) {
            PDDocument.load(File(book.path), MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                PDFTextStripper()
                    .apply {
                        startPage = page + 1
                        endPage = page + 1
                    }
                    .getText(doc)
            }
        }

    private fun pageText() {
        lifecycleScope.launch {
            try {
                val value = text(page)
                if (value.isBlank()) {
                    Toast.makeText(
                            this@PdfActivity,
                            "No text layer on this page",
                            Toast.LENGTH_LONG,
                        )
                        .show()
                    return@launch
                }
                val v = label(value)
                v.setTextIsSelectable(true)
                val scroll = ScrollView(this@PdfActivity)
                scroll.addView(v)
                AlertDialog.Builder(this@PdfActivity)
                    .setTitle("Page text • select to copy")
                    .setView(scroll)
                    .setPositiveButton("Close", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(
                        this@PdfActivity,
                        "Text unavailable: ${e.message}",
                        Toast.LENGTH_LONG,
                    )
                    .show()
            }
        }
    }

    private fun find(query: String) {
        lifecycleScope.launch {
            pageLabel.text = "Searching…"
            try {
                val found =
                    withContext(Dispatchers.IO) {
                        PDDocument.load(File(book.path), MemoryUsageSetting.setupTempFileOnly())
                            .use { doc ->
                                val stripper = PDFTextStripper()
                                buildList {
                                    for (i in 0 until doc.numberOfPages) {
                                        ensureActive()
                                        stripper.startPage = i + 1
                                        stripper.endPage = i + 1
                                        if (stripper.getText(doc).contains(query, true)) add(i)
                                        if (size >= 100) break
                                    }
                                }
                            }
                    }
                AlertDialog.Builder(this@PdfActivity)
                    .setTitle(if (found.isEmpty()) "No matches / no text layer" else "Matches")
                    .setItems(found.map { "Page ${it+1}" }.toTypedArray()) { _, i ->
                        show(found[i])
                    }
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@PdfActivity, "Search failed: ${e.message}", Toast.LENGTH_LONG)
                    .show()
            }
            pageLabel.text = "${page+1} / ${renderer?.pageCount}"
        }
    }

    @Deprecated("Android callback")
    override fun onActivityResult(request: Int, result: Int, data: android.content.Intent?) {
        super.onActivityResult(request, result, data)
        if (request == 45 && result == RESULT_OK)
            data?.data?.let { uri ->
                lifecycleScope.launch(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
                        out.write(
                            JSONObject()
                                .put("book", book.id)
                                .put("coordinateSystem", "normalized top-left page coordinates")
                                .put(
                                    "annotations",
                                    JSONArray(
                                        store.marks(book.id).map { m ->
                                            JSONObject()
                                                .put("page", m.location.toInt() + 1)
                                                .put("kind", m.kind)
                                                .put("data", m.text)
                                        }
                                    ),
                                )
                                .toString(2)
                        )
                    }
                }
            }
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putInt("page", page)
        super.onSaveInstanceState(out)
    }

    override fun onDestroy() {
        renderJob?.cancel()
        val r = renderer
        Thread { synchronized(lock) { r?.close() } }.start()
        canvas.bitmap?.recycle()
        super.onDestroy()
    }

    inner class PageView : View(this@PdfActivity) {
        var bitmap: Bitmap? = null
        var marks: List<Mark> = emptyList()
        var mode = "read"
        private var zoom = 1f
        private var dx = 0f
        private var dy = 0f
        private val rect = RectF()
        private var points = JSONArray()
        private var lastX = 0f
        private var lastY = 0f
        private var startX = 0f
        private var startY = 0f
        private val scale =
            ScaleGestureDetector(
                context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(d: ScaleGestureDetector): Boolean {
                        zoom = (zoom * d.scaleFactor).coerceIn(1f, 4f)
                        invalidate()
                        return true
                    }
                },
            )

        fun reset() {
            zoom = 1f
            dx = 0f
            dy = 0f
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val b = bitmap ?: return
            val factor = minOf(width.toFloat() / b.width, height.toFloat() / b.height) * zoom
            val w = b.width * factor
            val h = b.height * factor
            // Clamp pan so the page cannot be dragged completely off screen.
            // Allow panning up to (content - viewport) / 2 in each direction.
            val maxDx = ((w - width) / 2f).coerceAtLeast(0f)
            val maxDy = ((h - height) / 2f).coerceAtLeast(0f)
            dx = dx.coerceIn(-maxDx, maxDx)
            dy = dy.coerceIn(-maxDy, maxDy)
            rect.set(
                (width - w) / 2 + dx,
                (height - h) / 2 + dy,
                (width + w) / 2 + dx,
                (height + h) / 2 + dy,
            )
            c.drawBitmap(b, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
            marks
                .filter { it.kind == "ink" || it.kind == "highlight" }
                .forEach {
                    try {
                        draw(c, JSONArray(it.text), it.kind)
                    } catch (_: Exception) {}
                }
            draw(c, points, mode)
        }

        private fun draw(c: Canvas, pts: JSONArray, kind: String) {
            if (pts.length() < 2) return
            val p = Path()
            for (i in 0 until pts.length()) {
                val a = pts.getJSONArray(i)
                val x = rect.left + a.getDouble(0).toFloat() * rect.width()
                val y = rect.top + a.getDouble(1).toFloat() * rect.height()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            c.drawPath(
                p,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    color = if (kind == "highlight") 0x66D9B94E else Color.DKGRAY
                    strokeWidth = rect.width() * if (kind == "highlight") 0.022f else 0.0025f
                },
            )
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            scale.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.x
                    startY = e.y
                    lastX = e.x
                    lastY = e.y
                    points = JSONArray()
                    if (mode != "read") point(e)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode != "read" && e.pointerCount == 1) point(e)
                    else if (!scale.isInProgress && zoom > 1) {
                        dx += e.x - lastX
                        dy += e.y - lastY
                    }
                    lastX = e.x
                    lastY = e.y
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    if (mode != "read") {
                        if (points.length() > 1) save(mode, points.toString())
                        points = JSONArray()
                    } else if (zoom == 1f) {
                        val delta = if (vertical) e.y - startY else e.x - startX
                        if (kotlin.math.abs(delta) > 100) show(page + if (delta < 0) 1 else -1)
                        else if (kotlin.math.abs(e.x - startX) + kotlin.math.abs(e.y - startY) < 20)
                            bar.visibility =
                                if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                    performClick()
                }
                MotionEvent.ACTION_CANCEL -> points = JSONArray()
            }
            return true
        }

        private fun point(e: MotionEvent) {
            if (rect.width() > 0 && points.length() < 10000)
                points.put(
                    JSONArray()
                        .put(((e.x - rect.left) / rect.width()).coerceIn(0f, 1f).toDouble())
                        .put(((e.y - rect.top) / rect.height()).coerceIn(0f, 1f).toDouble())
                )
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}
