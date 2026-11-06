package still.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue

class SampleTest {
    @Test fun downloadedSamplesValidate() {
        val files = File("../.tools/samples").listFiles().orEmpty()
        assumeTrue("Run opt-in live download test first", files.any { it.extension == "epub" } && files.any { it.extension == "pdf" })
        for (format in listOf("epub", "pdf")) {
            val file = files.first { it.extension == format }
            assertEquals(file.nameWithoutExtension, Integrity.check(file, format))
        }
    }
}
