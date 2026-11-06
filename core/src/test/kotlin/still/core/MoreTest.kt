package still.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.mockwebserver.*

class MoreTest {
    @Test
    fun metadataCache() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            var calls = 0
            val p =
                CachedProvider(
                    object : Provider {
                        override val name = "test"

                        override suspend fun search(q: Query): List<Book> {
                            calls++
                            return listOf(
                                Book(
                                    q.raw,
                                    files =
                                        listOf(
                                            Edition("https://example.org/a.pdf", "pdf", "fixture")
                                        ),
                                )
                            )
                        }
                    },
                    dir,
                )
            assertEquals(p.search(Query("book")), p.search(Query("book")))
            assertEquals(1, calls)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun concurrentSearchIsolation() = runBlocking {
        MockWebServer().use { s ->
            s.start()
            repeat(4) { s.enqueue(MockResponse().setBody("%PDF-1.4\n%%EOF")) }
            val net = Net(true)
            val p =
                object : Provider {
                    override val name = "test"

                    override suspend fun search(q: Query) =
                        listOf(
                            Book(
                                q.raw,
                                files =
                                    listOf(
                                        Edition(s.url("/${q.raw}.pdf").toString(), "pdf", "test")
                                    ),
                            )
                        )
                }
            val d = Discovery(net, listOf(p))
            val a = async { d.search("Alpha").toList().last() }
            val b = async { d.search("Beta").toList().last() }
            assertEquals("Alpha", a.await().books.single().title)
            assertEquals("Beta", b.await().books.single().title)
        }
    }

    @Test
    fun malformedAndEmptyResponse() = runBlocking {
        MockWebServer().use { s ->
            s.start()
            for (body in listOf("", "not JSON")) {
                s.enqueue(MockResponse().setBody(body))
                assertFailsWith<org.json.JSONException> {
                    org.json.JSONObject(Net(true).text(s.url("/").toString()))
                }
            }
        }
    }

    @Test
    fun providerCancellation() = runBlocking {
        var cancelled = false
        val p =
            object : Provider {
                override val name = "slow"

                override suspend fun search(q: Query): List<Book> {
                    try {
                        delay(10000)
                    } finally {
                        cancelled = true
                    }
                    return emptyList()
                }
            }
        val job = launch { Discovery(Net(true), listOf(p)).search("book").collect() }
        delay(100)
        job.cancelAndJoin()
        assertTrue(cancelled)
    }

    @Test
    fun timeoutAndDisconnection() = runBlocking {
        MockWebServer().use { s ->
            s.start()
            s.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(200) { Net(true).text(s.url("/").toString()) }
            }
        }
        Unit
    }

    @Test
    fun redirectLoop() = runBlocking {
        MockWebServer().use { s ->
            s.start()
            repeat(6) {
                s.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/loop"))
            }
            assertFailsWith<java.io.IOException> { Net(true).text(s.url("/loop").toString()) }
        }
        Unit
    }

    @Test
    fun emptyProvider() = runBlocking {
        val p =
            object : Provider {
                override val name = "empty"

                override suspend fun search(q: Query) = emptyList<Book>()
            }
        val result = Discovery(Net(), listOf(p)).search("missing").toList().last()
        assertTrue(result.books.isEmpty())
        assertTrue(result.message.startsWith("No verified"))
    }
}
