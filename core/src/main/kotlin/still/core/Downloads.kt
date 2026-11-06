package still.core

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.*

object Integrity {
    fun check(file: File, format: String): String {
        require(file.length() > 0)
        if (format == "epub")
            ZipFile(file).use { zip ->
                require(
                    zip.getEntry("mimetype")?.let {
                        require(it.size == 20L)
                        zip.getInputStream(it).bufferedReader().use { r -> r.readText() }
                    } == "application/epub+zip"
                )
                fun xml(path: String): org.jsoup.nodes.Document {
                    val entry =
                        requireNotNull(zip.getEntry(path)) { "Missing EPUB resource: $path" }
                    require(entry.size in 1..2L * 1024 * 1024) { "Oversized EPUB metadata" }
                    return org.jsoup.Jsoup.parse(
                        zip.getInputStream(entry).bufferedReader().use { it.readText() },
                        "",
                        org.jsoup.parser.Parser.xmlParser(),
                    )
                }
                val root = xml("META-INF/container.xml").select("rootfile").attr("full-path")
                require(
                    root.isNotBlank() &&
                        !root.startsWith('/') &&
                        root.split('/').none { it == ".." }
                )
                val packageDoc = xml(root)
                val manifest =
                    packageDoc.select("manifest > item").associate {
                        it.attr("id") to it.attr("href")
                    }
                val spine = packageDoc.select("spine > itemref")
                require(spine.isNotEmpty()) { "EPUB has no reading order" }
                spine.forEach { item ->
                    val href = requireNotNull(manifest[item.attr("idref")]) { "Broken EPUB spine" }
                    val path = java.net.URI(root).resolve(href).normalize().path
                    require(
                        path != null &&
                            !path.startsWith("../") &&
                            zip.getEntry(java.net.URLDecoder.decode(path, "UTF-8")) != null
                    ) {
                        "Missing EPUB chapter"
                    }
                }
                var total = 0L
                var count = 0
                zip.entries().asSequence().forEach { e ->
                    require(
                        ++count <= 20000 &&
                            !e.name.startsWith('/') &&
                            e.name.split('/').none { it == ".." }
                    )
                    require(e.size in 0..64L * 1024 * 1024)
                    total += e.size
                    require(total <= 512L * 1024 * 1024)
                    require(e.compressedSize <= 0 || e.size / e.compressedSize < 1000)
                    if (!e.isDirectory)
                        zip.getInputStream(e).use { input ->
                            val crc = java.util.zip.CRC32()
                            val b = ByteArray(16384)
                            var actual = 0L
                            while (true) {
                                val n = input.read(b)
                                if (n < 0) break
                                actual += n
                                require(actual <= e.size)
                                crc.update(b, 0, n)
                            }
                            require(actual == e.size && crc.value == e.crc)
                        }
                }
            }
        else {
            RandomAccessFile(file, "r").use { f ->
                val b = ByteArray(5)
                f.readFully(b)
                require(validPrefix(b, "pdf"))
                f.seek((f.length() - 2048).coerceAtLeast(0))
                val tail = ByteArray((f.length() - f.filePointer).toInt())
                f.readFully(tail)
                require(tail.toString(Charsets.ISO_8859_1).contains("%%EOF")) { "Incomplete PDF" }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val b = ByteArray(32768)
            while (true) {
                val n = input.read(b)
                if (n < 0) break
                digest.update(b, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

class Downloader(private val net: Net) {
    suspend fun download(
        e: Edition,
        dir: File,
        maxBytes: Long = 256L * 1024 * 1024,
        progress: (Long, Long?) -> Unit = { _, _ -> },
    ): File =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val part = File(dir, hash(e.url) + ".part")
            val tag = File(dir, hash(e.url) + ".etag")
            val offset = if (tag.exists()) part.length() else 0L
            val headers = mutableMapOf("Accept-Encoding" to "identity")
            if (offset > 0) {
                headers["Range"] = "bytes=$offset-"
                headers["If-Range"] = tag.readText()
            }
            val response =
                try {
                    net.open(e.url, headers)
                } catch (error: HttpFailure) {
                    if (error.status == 416 && offset > 0) {
                        part.delete()
                        tag.delete()
                        return@withContext download(e, dir, maxBytes, progress)
                    } else throw error
                }
            response.use { r ->
                val resume =
                    offset > 0 &&
                        r.code == 206 &&
                        r.header("Content-Range")?.startsWith("bytes $offset-") == true
                require(
                    r.code != 206 ||
                        resume ||
                        r.header("Content-Range")?.startsWith("bytes 0-") == true
                )
                val start = if (resume) offset else 0L
                val total = r.body!!.contentLength().takeIf { it >= 0 }?.plus(start)
                require(total == null || total <= maxBytes) { "File exceeds download limit" }
                require(
                    dir.usableSpace > minOf(total ?: 16L * 1024 * 1024, maxBytes) + 8 * 1024 * 1024
                ) {
                    "Insufficient storage"
                }
                val validator =
                    r.header("ETag")?.takeUnless { it.startsWith("W/") }
                        ?: r.header("Last-Modified")
                if (validator != null) tag.writeText(validator) else tag.delete()
                var done = start
                java.io.FileOutputStream(part, resume).use { out ->
                    r.body!!.byteStream().use { input ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            done += n
                            require(done <= maxBytes)
                            out.write(buffer, 0, n)
                            progress(done, total)
                        }
                    }
                    out.fd.sync()
                }
                require(total == null || done == total) { "Interrupted download" }
            }
            ensureActive()
            val digest = Integrity.check(part, e.format)
            val final = File(dir, "$digest.${e.format}")
            if (final.exists()) part.delete()
            else require(part.renameTo(final)) { "Atomic finalization failed" }
            tag.delete()
            final
        }
}
