package still.core

import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.*
import org.jsoup.Jsoup

fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }

interface Provider {
    val name: String

    suspend fun search(q: Query): List<Book>
}

class Gutenberg(private val net: Net) : Provider {
    override val name = "Gutenberg / Gutendex"

    override suspend fun search(q: Query): List<Book> {
        if (q.isbn != null) return emptyList()
        return JSONObject(
                net.text("https://gutendex.com/books/?search=${enc(q.raw)}&copyright=false")
            )
            .getJSONArray("results")
            .objects()
            .take(10)
            .map { b ->
                val f = b.getJSONObject("formats")
                Book(
                    b.getString("title"),
                    b.getJSONArray("authors").objects().joinToString { it.optString("name") },
                    b.optJSONArray("languages")?.optString(0) ?: "",
                    edition = "Gutenberg ${b.getInt("id")}",
                    description = b.optJSONArray("summaries")?.optString(0) ?: "",
                    cover = f.optString("image/jpeg"),
                    files =
                        f.keys()
                            .asSequence()
                            .filter { it == "application/epub+zip" || it == "application/pdf" }
                            .map {
                                Edition(
                                    f.getString(it),
                                    if (it.contains("epub")) "epub" else "pdf",
                                    name,
                                )
                            }
                            .toList(),
                )
            }
    }
}

class Archive(private val net: Net) : Provider {
    override val name = "Internet Archive"

    override suspend fun search(q: Query): List<Book> = coroutineScope {
        val terms = q.raw.replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        val query =
            "(title:\"$terms\" OR creator:\"$terms\" OR isbn:\"$terms\") AND mediatype:texts AND NOT collection:inlibrary AND NOT collection:printdisabled"
        val docs =
            JSONObject(
                    net.text(
                        "https://archive.org/advancedsearch.php?q=${enc(query)}&output=json&rows=5&fl[]=identifier"
                    )
                )
                .getJSONObject("response")
                .getJSONArray("docs")
                .objects()
        docs
            .map { d ->
                async {
                    val id = d.getString("identifier")
                    val data = JSONObject(net.text("https://archive.org/metadata/${enc(id)}"))
                    val m = data.getJSONObject("metadata")
                    if (m.optString("access-restricted-item") == "true") return@async null
                    val files =
                        data
                            .getJSONArray("files")
                            .objects()
                            .filter {
                                it.optString("private") != "true" &&
                                    it.optString("name").let { n ->
                                        n.endsWith(".epub") || n.endsWith(".pdf")
                                    }
                            }
                            .take(3)
                            .map { f ->
                                val n = f.getString("name")
                                Edition(
                                    "https://archive.org/download/${enc(id)}/${enc(n)}",
                                    n.substringAfterLast('.'),
                                    name,
                                )
                            }
                    Book(
                        m.optString("title", id),
                        m.optString("creator"),
                        m.optString("language"),
                        m.optString("date"),
                        m.optString("description"),
                        files = files,
                    )
                }
            }
            .awaitAll()
            .filterNotNull()
    }
}

class WebResolver(private val net: Net) {
    suspend fun resolve(url: String, title: String = ""): List<Book> {
        val format = url.substringBefore('?').substringAfterLast('.').lowercase()
        if (format in listOf("pdf", "epub"))
            return listOf(
                Book(
                    title.ifBlank { url.substringBefore('?').substringAfterLast('/') },
                    files = listOf(Edition(url, format, "Public URL")),
                )
            )
        val u = java.net.URI(url)
        val robots =
            try {
                net.text("${u.scheme}://${u.rawAuthority}/robots.txt")
            } catch (e: HttpFailure) {
                if (e.status == 404) "" else throw e
            }
        // Conservative: any matching Disallow is honored, including directives for other agents.
        require(
            robots
                .lineSequence()
                .filter { it.trim().startsWith("Disallow:", true) }
                .map { it.substringAfter(':').trim().substringBefore('#').trim() }
                .none { it.isNotEmpty() && u.path.orEmpty().startsWith(it.substringBefore('*')) }
        ) {
            "Robots disallows page"
        }
        val doc = Jsoup.parse(net.text(url), url)
        if (doc.select("meta[name=robots]").attr("content").contains("nofollow", true))
            return emptyList()
        val links =
            doc.select("a[href],link[href],meta[name=citation_pdf_url]")
                .mapNotNull { a ->
                    val href = if (a.tagName() == "meta") a.attr("content") else a.absUrl("href")
                    val type = a.attr("type")
                    val f =
                        when {
                            type.contains("pdf") ||
                                href.substringBefore('?').endsWith(".pdf", true) -> "pdf"
                            type.contains("epub") ||
                                href.substringBefore('?').endsWith(".epub", true) -> "epub"
                            else -> null
                        }
                    f?.let { Edition(href, it, u.host) }
                }
                .distinctBy { it.url }
                .take(8)
        return listOf(
            Book(
                doc.select("meta[name=citation_title]").attr("content").ifBlank {
                    title.ifBlank { doc.title() }
                },
                doc.select("meta[name=citation_author]").joinToString { it.attr("content") },
                files = links,
            )
        )
    }
}

class Brave(private val net: Net, private val key: String) : Provider {
    override val name = "Brave web search"

    override suspend fun search(q: Query): List<Book> {
        val result =
            JSONObject(
                net.text(
                    "https://api.search.brave.com/res/v1/web/search?q=${enc(q.raw+" (filetype:pdf OR filetype:epub) free ebook")}&count=8",
                    mapOf("X-Subscription-Token" to key),
                )
            )
        return result.optJSONObject("web")?.optJSONArray("results")?.objects()?.flatMap { r ->
            try {
                WebResolver(net).resolve(r.getString("url"), r.getString("title"))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        } ?: emptyList()
    }
}

class Opds(private val net: Net, private val url: String) : Provider {
    override val name = "OPDS"

    override suspend fun search(q: Query): List<Book> {
        val doc =
            Jsoup.parse(
                net.text(url.replace("{searchTerms}", enc(q.raw))),
                url,
                org.jsoup.parser.Parser.xmlParser(),
            )
        return doc.select("entry")
            .map { e ->
                Book(
                    e.select("title").text(),
                    e.select("author > name").text(),
                    files =
                        e.select("link")
                            .filter {
                                it.attr("rel") == "http://opds-spec.org/acquisition/open-access"
                            }
                            .mapNotNull { l ->
                                val f =
                                    when (l.attr("type")) {
                                        "application/pdf" -> "pdf"
                                        "application/epub+zip" -> "epub"
                                        else -> null
                                    }
                                f?.let { Edition(l.absUrl("href"), it, url) }
                            },
                )
            }
            .filter { rank(q, it) > 0 }
    }
}

data class SearchState(val books: List<Book>, val message: String)

class Discovery(private val net: Net, private val providers: List<Provider>) {
    fun search(raw: String): Flow<SearchState> =
        channelFlow {
                val q = Query(raw)
                val mutex = kotlinx.coroutines.sync.Mutex()
                val all = mutableListOf<Book>()
                val sem = Semaphore(3)
                val sources =
                    if (raw.startsWith("https://"))
                        listOf(
                            object : Provider {
                                override val name = "Public URL"

                                override suspend fun search(q: Query) =
                                    WebResolver(net).resolve(raw)
                            }
                        )
                    else providers
                sources
                    .map { p ->
                        launch {
                            try {
                                withTimeout(75000) {
                                    p.search(q)
                                        .filter { raw.startsWith("https://") || rank(q, it) > 0 }
                                        .forEach { b ->
                                            val editions =
                                                b.files
                                                    .map { e ->
                                                        async {
                                                            sem.withPermit {
                                                                try {
                                                                    net.probe(e)
                                                                } catch (c: CancellationException) {
                                                                    throw c
                                                                } catch (_: Exception) {
                                                                    null
                                                                }
                                                            }
                                                        }
                                                    }
                                                    .awaitAll()
                                                    .filterNotNull()
                                            if (editions.isNotEmpty()) {
                                                mutex.lock()
                                                try {
                                                    all.add(b.copy(files = editions))
                                                    send(
                                                        SearchState(
                                                            merge(all, q),
                                                            "Verified accessible file headers • checking sources…",
                                                        )
                                                    )
                                                } finally {
                                                    mutex.unlock()
                                                }
                                            }
                                        }
                                }
                            } catch (c: CancellationException) {
                                if (c !is TimeoutCancellationException) throw c
                                send(
                                    SearchState(
                                        mutex.run {
                                            lock()
                                            try {
                                                merge(all, q)
                                            } finally {
                                                unlock()
                                            }
                                        },
                                        "${p.name}: timeout",
                                    )
                                )
                            } catch (e: Exception) {
                                mutex.lock()
                                try {
                                    send(SearchState(merge(all, q), "${p.name}: ${e.message}"))
                                } finally {
                                    mutex.unlock()
                                }
                            }
                        }
                    }
                    .joinAll()
                send(
                    SearchState(
                        merge(all, q),
                        if (all.isEmpty())
                            "No verified free downloads. Try another title or a public document URL."
                        else
                            "${merge(all,q).size} works • availability checked; full integrity checked on download",
                    )
                )
            }
            .flowOn(Dispatchers.IO)
}

/** Persist candidate metadata only. Accessibility is always rechecked before displaying it. */
class CachedProvider(private val delegate: Provider, private val directory: File) : Provider {
    override val name = delegate.name

    override suspend fun search(q: Query): List<Book> {
        directory.mkdirs()
        val file = File(directory, hash(name + q.raw) + ".json")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < 3600000) {
            try {
                return JSONArray(file.readText()).objects().map { b ->
                    Book(
                        b.getString("title"),
                        b.optString("author"),
                        b.optString("language"),
                        b.optString("edition"),
                        b.optString("description"),
                        b.optString("isbn"),
                        cover = b.optString("cover"),
                        files =
                            b.getJSONArray("files").objects().map {
                                Edition(
                                    it.getString("url"),
                                    it.getString("format"),
                                    it.getString("source"),
                                )
                            },
                    )
                }
            } catch (_: Exception) {
                file.delete()
            }
        }
        val books = delegate.search(q)
        val json =
            JSONArray(
                books.map { b ->
                    JSONObject()
                        .put("title", b.title)
                        .put("author", b.author)
                        .put("language", b.language)
                        .put("edition", b.edition)
                        .put("description", b.description)
                        .put("isbn", b.isbn)
                        .put("cover", b.cover)
                        .put(
                            "files",
                            JSONArray(
                                b.files.map {
                                    JSONObject()
                                        .put("url", it.url)
                                        .put("format", it.format)
                                        .put("source", it.source)
                                }
                            ),
                        )
                }
            )
        val temp = File(directory, file.name + ".${java.util.UUID.randomUUID()}.tmp")
        temp.writeText(json.toString())
        temp.renameTo(file)
        directory
            .listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(100)
            ?.forEach { it.delete() }
        return books
    }
}

/** Cross-publisher, no-key discovery of openly licensed full book deposits. */
class Crossref(private val net: Net) : Provider {
    override val name = "Crossref open books"

    override suspend fun search(q: Query): List<Book> {
        val url =
            "https://api.crossref.org/works?filter=type:book,has-full-text:true,has-license:true&rows=12&query.bibliographic=${enc(q.isbn?:q.raw)}"
        return JSONObject(net.text(url))
            .getJSONObject("message")
            .getJSONArray("items")
            .objects()
            .filter { b ->
                b.optJSONArray("license")?.objects()?.any {
                    it.optString("URL").startsWith("https://creativecommons.org/") ||
                        it.optString("URL").startsWith("http://creativecommons.org/")
                } == true
            }
            .map { b ->
                Book(
                    b.optJSONArray("title")?.optString(0) ?: "Untitled",
                    b.optJSONArray("author")?.objects()?.joinToString {
                        it.optString("given") + " " + it.optString("family")
                    } ?: "",
                    b.optString("language"),
                    b.optString("DOI"),
                    b.optString("abstract"),
                    b.optJSONArray("ISBN")?.optString(0) ?: "",
                    files =
                        b.optJSONArray("link")
                            ?.objects()
                            ?.filter {
                                it.optString("content-type") == "application/pdf" &&
                                    it.optString("content-version") == "vor"
                            }
                            ?.map { Edition(it.getString("URL"), "pdf", name) } ?: emptyList(),
                )
            }
    }
}
