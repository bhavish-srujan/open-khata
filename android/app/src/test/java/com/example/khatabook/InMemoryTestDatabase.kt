package com.example.khatabook

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.khatabook.data.local.KhatabookDatabase

/**
 * A fresh, real, in-memory Room database for tests — same schema and DAO
 * behavior as production, minus persistence to disk. Requires
 * RobolectricTestRunner (Room's actual SQLite implementation is part of the
 * Android framework Robolectric shadows; it does not run on a bare JVM).
 *
 * Every test that builds a [com.example.khatabook.data.repository.KhatabookRepository]
 * directly should pass `databaseFactory = { buildInMemoryTestDatabase() }`,
 * matching the existing `prefsFactory = { FakeSharedPreferences() }`
 * convention — a real, deterministic implementation instead of a mock, so
 * these tests exercise actual query/persistence logic.
 */
fun buildInMemoryTestDatabase(): KhatabookDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KhatabookDatabase::class.java)
        .allowMainThreadQueries()
        .build()
