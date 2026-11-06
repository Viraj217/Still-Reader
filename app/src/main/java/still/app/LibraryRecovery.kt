package still.app

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import still.core.Integrity

/** Recover finalized books left on disk if an earlier worker failed before registration. */
object LibraryRecovery {
    @Synchronized
    fun recover(context: Context, store: Store) {
        val known = store.books().map { it.id }.toSet()
        File(context.filesDir, "books")
            .listFiles()
            .orEmpty()
            .filter { it.extension in listOf("epub", "pdf") && it.nameWithoutExtension !in known }
            .forEach { file ->
                try {
                    if (Integrity.check(file, file.extension) != file.nameWithoutExtension)
                        return@forEach
                    var title = "Recovered PDF — title unavailable"
                    var author = ""
                    var pages = 0
                    if (file.extension == "epub") {
                        ZipFile(file).use { zip ->
                            val container =
                                zip.getInputStream(zip.getEntry("META-INF/container.xml"))
                                    .bufferedReader()
                                    .use { Jsoup.parse(it.readText(), "", Parser.xmlParser()) }
                            val opf = zip.getEntry(container.select("rootfile").attr("full-path"))
                            val metadata =
                                zip.getInputStream(opf).bufferedReader().use {
                                    Jsoup.parse(it.readText(), "", Parser.xmlParser())
                                }
                            title =
                                metadata.select("dc|title").text().ifBlank {
                                    "Recovered EPUB — title unavailable"
                                }
                            author = metadata.select("dc|creator").text()
                        }
                    } else {
                        PdfRenderer(
                                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                            )
                            .use { pages = it.pageCount }
                    }
                    store.add(
                        title,
                        author,
                        file,
                        file.extension,
                        pages,
                        "Recovered local download",
                    )
                } catch (e: Exception) {
                    // Keep the original file; a malformed document must not prevent library
                    // loading.
                    android.util.Log.w("StillRecovery", "Cannot register ${file.name}", e)
                }
            }
    }
}
