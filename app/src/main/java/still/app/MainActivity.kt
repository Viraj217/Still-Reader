package still.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import kotlinx.coroutines.*
import still.core.*

val paper = Color.rgb(247, 244, 237)

fun android.content.Context.label(s: String, size: Float = 18f) =
    TextView(this).apply {
        text = s
        textSize = size
        setTextColor(Color.rgb(43, 43, 40))
        setPadding(12, 10, 12, 10)
    }

fun android.content.Context.button(s: String, action: () -> Unit) =
    Button(this).apply {
        text = s
        isAllCaps = false
        setOnClickListener { action() }
    }

fun android.content.Context.column() =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 12, 24, 12)
        setBackgroundColor(paper)
    }

fun FragmentActivity.prompt(title: String, initial: String = "", done: (String) -> Unit) {
    val input = EditText(this).apply { setText(initial) }
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(input)
        .setPositiveButton("Save") { _, _ -> done(input.text.toString()) }
        .setNegativeButton("Cancel", null)
        .show()
}

class MainActivity : FragmentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private val store by lazy { Store(this) }
    private var search: Job? = null
    private var grid = false
    private var libraryVisible = false
    private var libraryJob: Job? = null
    private var workSnapshot: List<WorkInfo> = emptyList()
    private var downloadRows: LinearLayout? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        library()
        WorkManager.getInstance(this).getWorkInfosByTagLiveData("download").observe(this) { works ->
            val completedChanged =
                works.filter { it.state.isFinished }.map { it.id to it.state } !=
                    workSnapshot.filter { it.state.isFinished }.map { it.id to it.state }
            workSnapshot = works
            if (libraryVisible) {
                if (completedChanged) library() else renderDownloads()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (libraryVisible) library()
    }

    private fun base(title: String) {
        libraryVisible = false
        libraryJob?.cancel()
        downloadRows = null
        root = column()
        root.addView(label(title, 32f))
        setContentView(root)
        val sc = ScrollView(this)
        results = column()
        sc.addView(results)
        root.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun library() {
        search?.cancel()
        base("Still")
        libraryVisible = true
        val rows = column()
        downloadRows = rows
        results.addView(rows)
        val booksContainer = column()
        results.addView(booksContainer)
        renderDownloads()
        val bar = LinearLayout(this)
        bar.addView(button("Discover") { discover() })
        bar.addView(button("Settings") { settings() })
        bar.addView(
            button(if (grid) "List" else "Grid") {
                grid = !grid
                library()
            }
        )
        root.addView(bar, 1)
        libraryJob =
            lifecycleScope.launch {
                val books =
                    withContext(Dispatchers.IO) {
                        LibraryRecovery.recover(this@MainActivity, store)
                        store.books()
                    }
                if (books.isEmpty())
                    booksContainer.addView(
                        label(
                            "A quiet place for your books.\nSearch for a free EPUB or PDF to begin."
                        )
                    )
                var row: LinearLayout? = null
                books.forEachIndexed { i, b ->
                    val card = column()
                    card.addView(label(b.title, 23f))
                    card.addView(label(b.author))
                    card.addView(
                        label(
                            "${b.format.uppercase()} · ${if(b.pages>0)"${b.pages} pages" else "Pages: Unknown"}"
                        )
                    )
                    card.addView(button("Continue") { open(b) })
                    card.setOnLongClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Delete ${b.title} and its annotations?")
                            .setPositiveButton("Delete") { _, _ ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    store.delete(b)
                                    withContext(Dispatchers.Main) { library() }
                                }
                            }
                            .setNegativeButton("Keep", null)
                            .show()
                        true
                    }
                    if (grid) {
                        if (i % 2 == 0) {
                            row = LinearLayout(this@MainActivity)
                            booksContainer.addView(row)
                        }
                        row!!.addView(card, LinearLayout.LayoutParams(0, -2, 1f))
                    } else booksContainer.addView(card)
                }
            }
    }

    private fun renderDownloads() {
        val rows = downloadRows ?: return
        rows.removeAllViews()
        val wm = WorkManager.getInstance(this)
        workSnapshot
            .filter { !it.state.isFinished || it.state == WorkInfo.State.FAILED }
            .forEach { w ->
                val message =
                    when (w.state) {
                        WorkInfo.State.RUNNING ->
                            "Downloading · ${bytes(w.progress.getLong("bytes", 0))}"
                        WorkInfo.State.ENQUEUED ->
                            if (w.runAttemptCount > 0)
                                "Waiting to retry · network or server interruption"
                            else "Waiting for network / download slot"
                        WorkInfo.State.BLOCKED -> "Waiting for another download"
                        WorkInfo.State.FAILED ->
                            "Download failed: ${w.outputData.getString("error") ?: "Unknown error; retry from search"}"
                        else -> w.state.toString()
                    }
                rows.addView(label(message))
                if (!w.state.isFinished)
                    rows.addView(button("Cancel download") { wm.cancelWorkById(w.id) })
            }
    }

    private fun open(b: ShelfBook) {
        startActivity(
            Intent(
                    this,
                    if (b.format == "epub") EpubActivity::class.java else PdfActivity::class.java,
                )
                .putExtra("id", b.id)
        )
    }

    private fun discover() {
        base("Discover")
        val field =
            EditText(this).apply {
                hint = "Title, author, ISBN or https:// URL"
                setSingleLine(true)
            }
        root.addView(field, 1)
        status = label("Only accessible EPUB and PDF files appear.")
        root.addView(status, 2)
        root.addView(
            button("Search") {
                search?.cancel()
                results.removeAllViews()
                status.text = "Searching…"
                val raw = field.text.toString().trim()
                if (raw.isNotEmpty())
                    search =
                        lifecycleScope.launch {
                            val net = Net(cacheDir = java.io.File(cacheDir, "http"))
                            val prefs = getSharedPreferences("settings", 0)
                            val providers =
                                mutableListOf<Provider>(Gutenberg(net), Archive(net), Crossref(net))
                            prefs
                                .getString("brave", "")!!
                                .takeIf { it.isNotBlank() }
                                ?.let { providers.add(Brave(net, it)) }
                            prefs
                                .getString("opds", "")!!
                                .takeIf { it.isNotBlank() }
                                ?.let { providers.add(Opds(net, it)) }
                            Discovery(
                                    net,
                                    providers.map {
                                        CachedProvider(it, java.io.File(cacheDir, "metadata"))
                                    },
                                )
                                .search(raw)
                                .collect { state ->
                                    status.text = state.message
                                    results.removeAllViews()
                                    state.books.forEach { b ->
                                        val card = column()
                                        if (b.cover.isNotBlank()) {
                                            val cover =
                                                ImageView(this@MainActivity).apply {
                                                    contentDescription = "Cover of ${b.title}"
                                                    adjustViewBounds = true
                                                }
                                            card.addView(cover, LinearLayout.LayoutParams(100, 140))
                                            launch(Dispatchers.IO) {
                                                try {
                                                    val data = net.data(b.cover, 1024 * 1024)
                                                    val options =
                                                        android.graphics.BitmapFactory.Options()
                                                            .apply { inJustDecodeBounds = true }
                                                    android.graphics.BitmapFactory.decodeByteArray(
                                                        data,
                                                        0,
                                                        data.size,
                                                        options,
                                                    )
                                                    options.inSampleSize =
                                                        (maxOf(
                                                                options.outWidth,
                                                                options.outHeight,
                                                            ) / 240)
                                                            .coerceAtLeast(1)
                                                    options.inJustDecodeBounds = false
                                                    val bitmap =
                                                        android.graphics.BitmapFactory
                                                            .decodeByteArray(
                                                                data,
                                                                0,
                                                                data.size,
                                                                options,
                                                            )
                                                    withContext(Dispatchers.Main) {
                                                        cover.setImageBitmap(bitmap)
                                                    }
                                                } catch (c: CancellationException) {
                                                    throw c
                                                } catch (_: Exception) {}
                                            }
                                        }
                                        card.addView(label(b.title, 24f))
                                        card.addView(label(b.author))
                                        card.addView(
                                            label(
                                                "${b.language.ifBlank {"Language unknown"}} · Pages: ${b.pages?:"Unknown"}\n${b.edition}"
                                            )
                                        )
                                        if (b.description.isNotBlank())
                                            card.addView(
                                                label(
                                                    org.jsoup.Jsoup.parse(b.description)
                                                        .text()
                                                        .take(500),
                                                    15f,
                                                )
                                            )
                                        b.files.forEach { e ->
                                            card.addView(
                                                button(
                                                    "${e.format.uppercase()} · ${bytes(e.size)} · ${e.source}"
                                                ) {
                                                    download(b, e)
                                                }
                                            )
                                        }
                                        results.addView(card)
                                    }
                                }
                        }
            },
            3,
        )
        root.addView(button("Library") { library() }, 4)
    }

    private fun download(b: Book, e: Edition) {
        val request =
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(
                    workDataOf(
                        "url" to e.url,
                        "format" to e.format,
                        "source" to e.source,
                        "title" to b.title,
                        "author" to b.author,
                    )
                )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .addTag("download")
                .build()
        val wm = WorkManager.getInstance(this)
        wm.enqueueUniqueWork(hash(e.url), ExistingWorkPolicy.KEEP, request)
        val text = label("Queued")
        val dialog =
            AlertDialog.Builder(this)
                .setTitle(b.title)
                .setView(text)
                .setNegativeButton("Pause / cancel") { _, _ -> wm.cancelUniqueWork(hash(e.url)) }
                .setNeutralButton("Background", null)
                .show()
        var opened = false
        wm.getWorkInfosForUniqueWorkLiveData(hash(e.url)).observe(this) { works ->
            val w = works.firstOrNull { it.id == request.id }
                ?: works.firstOrNull { !it.state.isFinished }

            if (w != null) {
                text.text =
                    "${w.state}\n${bytes(w.progress.getLong("bytes",0))} / ${bytes(w.progress.getLong("total",-1).takeIf{it>=0})}"
                if (w.state == WorkInfo.State.SUCCEEDED && !opened) {
                    opened = true
                    dialog.dismiss()
                    lifecycleScope.launch {
                        val book =
                            withContext(Dispatchers.IO) {
                                store.books().find { it.id == w.outputData.getString("book") }
                            }
                        book?.let { open(it) }
                    }
                }
                if (w.state == WorkInfo.State.FAILED) text.text = w.outputData.getString("error")
            }
        }
    }

    private fun settings() {
        base("Settings")
        val p = getSharedPreferences("settings", 0)
        results.addView(
            button("Optional Brave API key") {
                prompt("Your Brave key", p.getString("brave", "")!!) {
                    p.edit().putString("brave", it.trim()).apply()
                }
            }
        )
        results.addView(
            button("Public OPDS feed / search template") {
                prompt("HTTPS OPDS URL; optional {searchTerms}", p.getString("opds", "")!!) {
                    p.edit().putString("opds", it.trim()).apply()
                }
            }
        )
        results.addView(
            button("SD card / folder backup destination") {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        .addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        ),
                    70,
                )
            }
        )
        results.addView(
            button("Download limit: ${p.getLong("limit",256)} MB") {
                prompt("Maximum MB (16–512)", p.getLong("limit", 256).toString()) {
                    it.toLongOrNull()?.let { n ->
                        p.edit().putLong("limit", n.coerceIn(16, 512)).apply()
                        settings()
                    }
                }
            }
        )
        results.addView(
            label(
                "Privacy\nNo analytics, account or reading-history uploads. Search queries go to enabled providers. Files and annotations remain in private app storage.\n\nWithout a Brave key, discovery uses catalogs and public URLs you paste. OPDS expands the catalog set. Gutendex is a community service; availability is not guaranteed.\n\nCancelled downloads retain partial files; repeat the download to resume where the server supports it."
            )
        )
        results.addView(button("Library") { library() })
    }

    @Deprecated("Android callback")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == 70 && result == RESULT_OK)
            data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    getSharedPreferences("settings", 0)
                        .edit()
                        .putString("backup", uri.toString())
                        .apply()
                    Toast.makeText(
                            this,
                            "New downloads will also be copied to this folder",
                            Toast.LENGTH_LONG,
                        )
                        .show()
                } catch (e: Exception) {
                    Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                }
            }
    }

    override fun onDestroy() {
        search?.cancel()
        store.close()
        super.onDestroy()
    }
}
