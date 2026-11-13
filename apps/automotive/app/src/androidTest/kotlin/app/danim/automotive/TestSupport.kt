package app.danim.automotive

import app.danim.automotive.data.AppDatabase

/** RoomDatabase is not Closeable in Room 2.6; inline so suspend calls work inside runBlocking. */
inline fun <R> AppDatabase.useDb(block: (AppDatabase) -> R): R = try { block(this) } finally { close() }
