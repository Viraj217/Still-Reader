package still.app

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.work.*
import java.io.File
import kotlinx.coroutines.*
import still.core.*

class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result =
        try {
            val format = inputData.getString("format")!!
            var last = 0L
            val file =
                Downloader(Net()).download(
                    Edition(
                        inputData.getString("url")!!,
                        format,
                        inputData.getString("source") ?: "",
                    ),
                    File(applicationContext.filesDir, "books"),
                    applicationContext.getSharedPreferences("settings", 0).getLong("limit", 256) *
                        1024 *
                        1024,
                ) { done, total ->
                    if (System.currentTimeMillis() - last > 500) {
                        last = System.currentTimeMillis()
                        setProgressAsync(workDataOf("bytes" to done, "total" to (total ?: -1)))
                    }
                }
            val pages =
                if (format == "pdf")
                    PdfRenderer(
                            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                        )
                        .use { it.pageCount }
                else 0
            // SQLiteOpenHelper does not implement AutoCloseable on Android 9.
            val store = Store(applicationContext)
            val id =
                try {
                    store.add(
                        inputData.getString("title")!!,
                        inputData.getString("author") ?: "",
                        file,
                        format,
                        pages,
                        inputData.getString("source") ?: "",
                        inputData.getString("url") ?: "",
                    )
                } finally {
                    store.close()
                }
            val backup =
                applicationContext.getSharedPreferences("settings", 0).getString("backup", null)
            var warning = ""
            if (backup != null)
                try {
                    val tree = android.net.Uri.parse(backup)
                    val parent =
                        android.provider.DocumentsContract.buildDocumentUriUsingTree(
                            tree,
                            android.provider.DocumentsContract.getTreeDocumentId(tree),
                        )
                    val target =
                        android.provider.DocumentsContract.createDocument(
                            applicationContext.contentResolver,
                            parent,
                            if (format == "epub") "application/epub+zip" else "application/pdf",
                            file.name,
                        ) ?: error("Folder unavailable")
                    applicationContext.contentResolver.openOutputStream(target)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    } ?: error("Cannot write backup")
                } catch (e: Exception) {
                    warning = "Book saved internally; folder backup failed: ${e.message}"
                }
            Result.success(workDataOf("book" to id, "warning" to warning))
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            if (e is java.io.IOException && runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf("error" to (e.message ?: "Download failed")))
        }
}
