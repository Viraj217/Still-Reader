package still.app

import android.content.*
import android.database.sqlite.*
import java.io.File
import still.core.*

data class ShelfBook(
    val id: String,
    val title: String,
    val author: String,
    val path: String,
    val format: String,
    val position: String,
    val pages: Int,
)

data class Mark(
    val id: Long,
    val book: String,
    val kind: String,
    val location: String,
    val text: String,
)

class Store(context: Context) : SQLiteOpenHelper(context, "still.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT NOT NULL,author TEXT NOT NULL,path TEXT NOT NULL,format TEXT NOT NULL,position TEXT NOT NULL DEFAULT '',pages INTEGER NOT NULL DEFAULT 0,opened INTEGER NOT NULL DEFAULT 0,source TEXT NOT NULL DEFAULT '',url TEXT NOT NULL DEFAULT '',bytes INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE marks(id INTEGER PRIMARY KEY AUTOINCREMENT,book TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,kind TEXT NOT NULL,location TEXT NOT NULL,text TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX marks_book ON marks(book)")
        db.execSQL(
            "CREATE TABLE cache(query TEXT PRIMARY KEY,json TEXT NOT NULL,time INTEGER NOT NULL)"
        )
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        error("Unsupported schema migration $old → $new")
    }

    fun add(
        title: String,
        author: String,
        file: File,
        format: String,
        pages: Int = 0,
        source: String = "",
        url: String = "",
    ): String {
        val id = file.nameWithoutExtension
        writableDatabase.insertWithOnConflict(
            "books",
            null,
            ContentValues().apply {
                put("id", id)
                put("title", title)
                put("author", author)
                put("path", file.path)
                put("format", format)
                put("pages", pages)
                put("source", source)
                put("url", url)
                put("bytes", file.length())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        return id
    }

    fun books(): List<ShelfBook> =
        readableDatabase
            .rawQuery(
                "SELECT id,title,author,path,format,position,pages FROM books ORDER BY opened DESC,rowid DESC",
                null,
            )
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        ShelfBook(
                            c.getString(0),
                            c.getString(1),
                            c.getString(2),
                            c.getString(3),
                            c.getString(4),
                            c.getString(5),
                            c.getInt(6),
                        )
                    )
                }
            }

    fun progress(id: String, pos: String) {
        writableDatabase.update(
            "books",
            ContentValues().apply {
                put("position", pos)
                put("opened", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(id),
        )
    }

    fun mark(id: String, kind: String, loc: String, text: String) {
        writableDatabase.insertOrThrow(
            "marks",
            null,
            ContentValues().apply {
                put("book", id)
                put("kind", kind)
                put("location", loc)
                put("text", text)
            },
        )
    }

    fun marks(id: String): List<Mark> =
        readableDatabase
            .rawQuery(
                "SELECT id,book,kind,location,text FROM marks WHERE book=? ORDER BY id",
                arrayOf(id),
            )
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        Mark(
                            c.getLong(0),
                            c.getString(1),
                            c.getString(2),
                            c.getString(3),
                            c.getString(4),
                        )
                    )
                }
            }

    fun removeMark(id: Long) {
        writableDatabase.delete("marks", "id=?", arrayOf(id.toString()))
    }

    fun delete(book: ShelfBook) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("books", "id=?", arrayOf(book.id))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        File(book.path).delete()
    }
}
