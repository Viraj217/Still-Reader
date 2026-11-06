package still.core

import java.security.MessageDigest
import java.text.Normalizer

data class Book(
    val title: String,
    val author: String = "",
    val language: String = "",
    val edition: String = "",
    val description: String = "",
    val isbn: String = "",
    val pages: Int? = null,
    val cover: String = "",
    val files: List<Edition> = emptyList(),
)

data class Edition(
    val url: String,
    val format: String,
    val source: String,
    val size: Long? = null,
    val verified: Boolean = false,
)

data class Query(val raw: String) {
    val normalized = normalize(raw)
    val isbn =
        raw.replace(Regex("[\\s-]"), "").takeIf { it.matches(Regex("(?:\\d{9}[\\dXx]|\\d{13})")) }
}

fun normalize(s: String) =
    Normalizer.normalize(s, Normalizer.Form.NFKC)
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

fun hash(s: String) =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") {
        "%02x".format(it)
    }

fun rank(q: Query, b: Book): Int {
    val t = normalize(b.title)
    return (if (q.isbn != null && q.isbn == b.isbn) 100000 else 0) +
        (if (t == q.normalized) 10000 else 0) +
        q.normalized
            .split(' ')
            .map { if (t.contains(it)) 100 else if (normalize(b.author).contains(it)) 50 else 0 }
            .sum()
}

fun merge(books: List<Book>, q: Query): List<Book> =
    books
        .groupBy {
            listOf(normalize(it.title), normalize(it.author), it.language, it.edition, it.isbn)
                .joinToString("|")
        }
        .values
        .map { group ->
            group.first().copy(files = group.flatMap { it.files }.distinctBy { it.url })
        }
        .sortedByDescending { rank(q, it) }

fun bytes(n: Long?): String =
    when {
        n == null -> "Unknown size"
        n < 1024 -> "$n B"
        n < 1048576 -> "%.0f KB".format(n / 1024.0)
        else -> "%.1f MB".format(n / 1048576.0)
    }
