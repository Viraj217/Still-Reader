package still.core

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class QuickLiveTest {
    @Test
    fun live() = runBlocking {
        org.junit.Assume.assumeTrue("Opt-in live network test", System.getenv("STILL_LIVE") == "1")
        val out = File("../docs/live-extended.txt")
        out.writeText("${java.time.Instant.now()}\n")
        val net = Net()
        val start = System.nanoTime()
        var first = true
        withTimeout(90000) {
            Discovery(net, listOf(Archive(net))).search("Pride and Prejudice").collect { s ->
                if (first && s.books.isNotEmpty()) {
                    out.appendText(
                        "First verified result ${(System.nanoTime()-start)/1e6} ms: ${s.books.first().title}\n"
                    )
                    first = false
                }
                out.appendText("${s.message}\n")
            }
        }
        assertFalse(first, "Archive yielded no verified matching result")
        val begin = System.nanoTime()
        val books = Crossref(net).search(Query("open science"))
        out.appendText("Crossref ${books.size} open records ${(System.nanoTime()-begin)/1e6} ms\n")
        val dir = File("../.tools/samples")
        val epub = dir.listFiles()!!.first { it.extension == "epub" }
        val pdf = dir.listFiles()!!.first { it.extension == "pdf" }
        assertEquals(64, Integrity.check(epub, "epub").length)
        assertEquals(64, Integrity.check(pdf, "pdf").length)
        ZipFile(epub).use { zip ->
            val opf = zip.entries().asSequence().first { it.name.endsWith(".opf") }
            val doc =
                org.jsoup.Jsoup.parse(
                    zip.getInputStream(opf).bufferedReader().use { it.readText() },
                    "",
                    org.jsoup.parser.Parser.xmlParser(),
                )
            out.appendText("EPUB title ${doc.select("dc|title").text()}; ${zip.size()} entries\n")
        }
        println(out.readText())
    }
}
