package still.core

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl

class HttpFailure(val status: Int) : IOException("HTTP $status")

class Net(private val allowLocal: Boolean = false, cacheDir: java.io.File? = null) {
    private val gate = Mutex()
    private val times = mutableMapOf<String, Long>()
    private val client =
        OkHttpClient.Builder()
            .cache(cacheDir?.let { Cache(it, 16L * 1024 * 1024) })
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .dns(
                object : Dns {
                    override fun lookup(host: String): List<InetAddress> =
                        publicAddresses(host, InetAddress.getAllByName(host).toList(), allowLocal)

                }
            )
            .build()

    suspend fun open(raw: String, headers: Map<String, String> = emptyMap()): Response {
        var url = raw.toHttpUrl()
        val origin = url.host
        require(url.username.isEmpty() && url.password.isEmpty())
        repeat(6) {
            currentCoroutineContext().ensureActive()
            gate.withLock {
                val now = System.currentTimeMillis()
                val wait = 600 - (now - (times[url.host] ?: 0))
                if (wait > 0) delay(wait)
                times[url.host] = System.currentTimeMillis()
            }
            var response: Response? = null
            for (attempt in 0..2) {
                val b =
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", "StillReader/0.1 (+public ebook discovery)")
                headers
                    .filter {
                        url.host == origin ||
                            it.key.lowercase() !in
                                setOf("authorization", "x-subscription-token", "cookie")
                    }
                    .forEach { (k, v) -> b.header(k, v) }
                val call = client.newCall(b.build())
                response = suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { call.cancel() }
                    call.enqueue(
                        object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                if (cont.isActive) cont.resumeWithException(e)
                            }

                            override fun onResponse(call: Call, r: Response) {
                                if (cont.isActive) cont.resume(r) else r.close()
                            }
                        }
                    )
                }
                if (response!!.code !in listOf(429, 500, 502, 503, 504) || attempt == 2) break
                val wait =
                    response!!
                        .header("Retry-After")
                        ?.toLongOrNull()
                        ?.times(1000)
                        ?.coerceIn(1000, 60000) ?: (1000L shl attempt)
                response!!.close()
                delay(wait)
            }
            val r = response!!
            if (r.code in 300..399) {
                val next = r.header("Location")?.let { url.resolve(it) }
                r.close()
                require(next != null) { "Invalid redirect" }
                require(!(url.isHttps && !next.isHttps))
                url = next
            } else {
                if (!r.isSuccessful) {
                    val code = r.code
                    r.close()
                    throw HttpFailure(code)
                }
                return r
            }
        }
        throw IOException("Redirect limit")
    }

    suspend fun data(
        url: String,
        limit: Int = 2 * 1024 * 1024,
        headers: Map<String, String> = emptyMap(),
    ): ByteArray =
        withContext(Dispatchers.IO) {
            open(url, headers).use { r ->
                val source = r.body!!.source()
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    ensureActive()
                    val n = source.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= limit) { "Response too large" }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        }

    suspend fun text(url: String, headers: Map<String, String> = emptyMap()) =
        data(url, headers = headers).toString(Charsets.UTF_8)

    suspend fun probe(e: Edition): Edition =
        withContext(Dispatchers.IO) {
            open(e.url, mapOf("Range" to "bytes=0-65535", "Accept-Encoding" to "identity")).use { r
                ->
                val src = r.body!!.source()
                val prefix = src.readByteArrayAtMost(65536)
                require(validPrefix(prefix, e.format)) { "Not a ${e.format} document" }
                val size =
                    if (r.code == 206)
                        r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    else r.body!!.contentLength().takeIf { it >= 0 }
                require(size == null || size <= 512L * 1024 * 1024) { "Exceeds size limit" }
                e.copy(url = r.request.url.toString(), size = size, verified = true)
            }
        }
}

fun okio.BufferedSource.readByteArrayAtMost(n: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val b = ByteArray(4096)
    while (out.size() < n) {
        val read = read(b, 0, minOf(b.size, n - out.size()))
        if (read < 0) break
        out.write(b, 0, read)
    }
    return out.toByteArray()
}

fun validPrefix(b: ByteArray, format: String): Boolean =
    when (format) {
        "pdf" -> b.take(5).toByteArray().toString(Charsets.US_ASCII) == "%PDF-"
        "epub" ->
            b.size > 30 &&
                b[0] == 80.toByte() &&
                b[1] == 75.toByte() &&
                b.toString(Charsets.ISO_8859_1).contains("application/epub+zip")
        else -> false
    }

/** Filter DNS answers individually; a private answer must never reach the socket. */
internal fun publicAddresses(host: String, answers: List<InetAddress>, allowLocal: Boolean = false): List<InetAddress> {
    val safe = if (allowLocal) answers else answers.filterNot { address ->
        val bytes = address.address
        val first = bytes[0].toInt() and 255
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress ||
            (bytes.size == 4 && (first == 0 || first >= 224 ||
                (first == 100 && (bytes[1].toInt() and 255) in 64..127))) ||
            (bytes.size == 16 && (first and 254) == 252)
    }
    if (safe.isEmpty()) throw java.net.UnknownHostException(
        "No public address for $host. DNS returned only local/private addresses; check VPN or DNS settings."
    )
    return safe
}
