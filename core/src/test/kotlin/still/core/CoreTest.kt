package still.core

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import okhttp3.mockwebserver.*

class CoreTest {
    private fun server(block: suspend (MockWebServer, Net) -> Unit) = runBlocking {
        MockWebServer().use { s ->
            s.start()
            block(s, Net(true))
        }
    }

    private fun pdf() = "%PDF-1.4\nfixture\n%%EOF".toByteArray()

    @Test
    fun normalization() {
        assertEquals("les misérables", Query(" Les  Misérables! ").normalized)
        assertEquals("9781234567890", Query("978-1-234567-89-0").isbn)
        assertNull(Query("Doyle").isbn)
    }

    @Test
    fun rankingAndEditions() {
        val q = Query("Book")
        val b = Book("Book", "Author", "en", "1900")
        val other = b.copy(edition = "1901")
        assertEquals(2, merge(listOf(b, b, other), q).size)
        assertEquals(b, merge(listOf(Book("Unrelated"), b), q).first())
    }

    @Test
    fun signatures() {
        assertTrue(validPrefix(pdf(), "pdf"))
        assertFalse(validPrefix("<html>PDF</html>".toByteArray(), "pdf"))
        assertFalse(validPrefix(byteArrayOf(80, 75), "epub"))
    }

    @Test
    fun redirectsAndUnknownSize() = server { s, n ->
        s.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/book"))
        s.enqueue(MockResponse().setChunkedBody(String(pdf()), 5))
        val e = n.probe(Edition(s.url("/a").toString(), "pdf", "fixture"))
        assertTrue(e.verified)
        assertNull(e.size)
        assertTrue(e.url.endsWith("/book"))
    }

    @Test
    fun htmlRejected() = server { s, n ->
        s.enqueue(
            MockResponse()
                .setBody("<html>pay to download</html>")
                .setHeader("Content-Type", "application/pdf")
        )
        assertFailsWith<IllegalArgumentException> {
            n.probe(Edition(s.url("/bad.pdf").toString(), "pdf", "x"))
        }
    }

    @Test
    fun errorsAndRetry() = server { s, n ->
        for (code in listOf(429, 500)) {
            s.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After", "0"))
            s.enqueue(MockResponse().setBody("ok"))
            assertEquals("ok", n.text(s.url("/retry").toString()))
        }
        s.enqueue(MockResponse().setResponseCode(404))
        assertEquals(404, assertFailsWith<HttpFailure> { n.text(s.url("/404").toString()) }.status)
    }

    @Test
    fun boundedResponse() = server { s, n ->
        s.enqueue(MockResponse().setBody("123456789"))
        assertFailsWith<IllegalArgumentException> { n.data(s.url("/").toString(), 4) }
    }

    @Test
    fun cancellation() = server { s, n ->
        s.enqueue(MockResponse().setBody("late").setHeadersDelay(5, TimeUnit.SECONDS))
        val job = CoroutineScope(currentCoroutineContext()).launch { n.text(s.url("/").toString()) }
        delay(100)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test
    fun downloadAtomicAndDuplicate() = server { s, n ->
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            repeat(2) { s.enqueue(MockResponse().setBody(String(pdf()))) }
            val e = Edition(s.url("/book").toString(), "pdf", "test")
            val a = Downloader(n).download(e, dir)
            val b = Downloader(n).download(e, dir)
            assertEquals(a, b)
            assertContentEquals(pdf(), a.readBytes())
            assertEquals(1, dir.listFiles()!!.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun interruptedResume() = server { s, n ->
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            val url = s.url("/book").toString()
            val data = pdf()
            File(dir, hash(url) + ".part").writeBytes(data.take(8).toByteArray())
            File(dir, hash(url) + ".etag").writeText("\"v1\"")
            s.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 8-${data.size-1}/${data.size}")
                    .setBody(String(data.drop(8).toByteArray()))
            )
            val result = Downloader(n).download(Edition(url, "pdf", "test"), dir)
            assertContentEquals(data, result.readBytes())
            assertEquals("bytes=8-", s.takeRequest().getHeader("Range"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rangeIgnoredRestarts() = server { s, n ->
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            val url = s.url("/").toString()
            File(dir, hash(url) + ".part").writeText("junk")
            File(dir, hash(url) + ".etag").writeText("tag")
            s.enqueue(MockResponse().setBody(String(pdf())))
            assertContentEquals(
                pdf(),
                Downloader(n).download(Edition(url, "pdf", "test"), dir).readBytes(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun incompleteRejected() {
        val f = File.createTempFile("bad", ".pdf")
        try {
            f.writeText("%PDF-1.4 truncated")
            assertFailsWith<IllegalArgumentException> { Integrity.check(f, "pdf") }
        } finally {
            f.delete()
        }
    }

    @Test
    fun epubStructureAndTraversal() {
        val f = File.createTempFile("book", ".epub")
        fun write(bad: Boolean) {
            ZipOutputStream(f.outputStream()).use { z ->
                mapOf(
                        "mimetype" to "application/epub+zip",
                        "book.opf" to
                            "<package><manifest><item id=\"c\" href=\"text.xhtml\"/></manifest><spine><itemref idref=\"c\"/></spine></package>",
                        "META-INF/container.xml" to
                            "<container><rootfiles><rootfile full-path=\"book.opf\"/></rootfiles></container>",
                        (if (bad) "../escape" else "text.xhtml") to "hello",
                    )
                    .forEach { (k, v) ->
                        z.putNextEntry(ZipEntry(k))
                        z.write(v.toByteArray())
                        z.closeEntry()
                    }
            }
        }
        try {
            write(false)
            assertEquals(64, Integrity.check(f, "epub").length)
            write(true)
            assertFailsWith<IllegalArgumentException> { Integrity.check(f, "epub") }
        } finally {
            f.delete()
        }
    }

    @Test
    fun providerFailurePartialResults() = server { s, n ->
        s.enqueue(MockResponse().setBody(String(pdf())))
        val good =
            object : Provider {
                override val name = "good"

                override suspend fun search(q: Query) =
                    listOf(
                        Book("Exact", files = listOf(Edition(s.url("/").toString(), "pdf", "test")))
                    )
            }
        val bad =
            object : Provider {
                override val name = "bad"

                override suspend fun search(q: Query): List<Book> {
                    error("failed")
                }
            }
        val states = Discovery(n, listOf(good, bad)).search("Exact").toList()
        assertEquals(1, states.last().books.size)
        assertTrue(states.any { it.message.contains("failed") })
    }

    @Test
    fun privateNetworkBlocked() = runBlocking {
        assertFails { Net().text("http://127.0.0.1/test") }
        assertFails { Net().text("file:///etc/passwd") }
        Unit
    }

    @Test
    fun benchmark() {
        val books = (1..10000).map { Book("Book ${it%500}", "Author", "en") }
        val start = System.nanoTime()
        val result = merge(books, Query("Book 12"))
        println("Dedup 10000 records: ${(System.nanoTime()-start)/1e6} ms; ${result.size} works")
        assertEquals(500, result.size)
    }
}
