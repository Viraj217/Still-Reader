#!/usr/bin/env python3
"""Exercise the exact CREATE statements from Store.kt using desktop SQLite."""
import re, sqlite3, tempfile, pathlib
source = pathlib.Path('app/src/main/java/still/app/Store.kt').read_text()
with tempfile.TemporaryDirectory() as tmp:
    path = tmp + '/test.db'
    db = sqlite3.connect(path)
    db.execute('PRAGMA foreign_keys=ON')
    for sql in re.findall(r'"(CREATE (?:TABLE|INDEX)[^"\n]+)"', source):
        db.execute(sql)
    db.execute("INSERT INTO books(id,title,author,path,format) VALUES('a','Title','Author','/private/a.pdf','pdf')")
    db.execute("INSERT INTO marks(book,kind,location,text) VALUES('a','ink','2','[[0.2,0.4],[0.8,0.7]]')")
    db.execute("UPDATE books SET position='2' WHERE id='a'")
    db.commit(); db.close()
    db = sqlite3.connect(path)
    db.execute('PRAGMA foreign_keys=ON')
    assert db.execute('SELECT position FROM books').fetchone()[0] == '2'
    assert db.execute('SELECT text FROM marks').fetchone()[0] == '[[0.2,0.4],[0.8,0.7]]'
    db.execute("DELETE FROM books WHERE id='a'")
    assert db.execute('SELECT count(*) FROM marks').fetchone()[0] == 0
    print('PASS: actual schema creation, progress/ink persistence after reopen, foreign-key cascade')
