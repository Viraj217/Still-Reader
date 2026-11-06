package still.core

import java.io.File
import kotlin.test.*
import kotlinx.coroutines.*

class LiveTest {
    @Test
    fun liveProvidersAndDownloads() = runBlocking {
        org.junit.Assume.assumeTrue("Opt-in live network test", System.getenv("STILL_LIVE") == "1")
        val net = Net()
        val output = File("../docs/live-results.txt")
        output.parentFile.mkdirs()
        output.writeText("Live checks ${java.time.Instant.now()}\n")
        suspend fun check(name: String, action: suspend () -> String) {
            val start = System.nanoTime()
            try {
                val detail = withTimeout(90000) { action() }
                output.appendText("PASS $name ${(System.nanoTime()-start)/1000000} ms $detail\n")
            } catch (e: Exception) {
                output.appendText(
                    "FAIL $name ${(System.nanoTime()-start)/1000000} ms ${e.javaClass.simpleName}: ${e.message}\n"
                )
            }
        }
        for (query in
            listOf(
                "Sherlock Holmes",
                "Jane Austen",
                "9780141439518",
                "Home",
                "Les Misérables",
                "zzzxqv987654321",
            )) check("Gutendex $query") {
            val books = Gutenberg(net).search(Query(query))
            "${books.size} records; ${books.firstOrNull()?.title}"
        }
        check("Archive exact title") {
            val b = Archive(net).search(Query("Pride and Prejudice"))
            "${b.size} records; ${b.firstOrNull()?.title}"
        }
        check("Crossref open books") {
            val b = Crossref(net).search(Query("mathematics"))
            "${b.size} openly licensed records; ${b.sumOf {it.files.size}} PDF candidates"
        }
        val dir = File("../.tools/samples").apply { mkdirs() }
        check("Gutenberg EPUB") {
            val e =
                net.probe(
                    Edition(
                        "https://www.gutenberg.org/ebooks/11.epub.noimages",
                        "epub",
                        "Gutenberg",
                    )
                )
            val f = Downloader(net).download(e, dir)
            "${f.length()} bytes SHA256 ${Integrity.check(f,"epub")}"
        }
        check("W3C PDF") {
            val e =
                net.probe(
                    Edition(
                        "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf",
                        "pdf",
                        "W3C",
                    )
                )
            val f = Downloader(net).download(e, dir)
            "${f.length()} bytes SHA256 ${Integrity.check(f,"pdf")}"
        }
        println(output.readText())
    }
}
