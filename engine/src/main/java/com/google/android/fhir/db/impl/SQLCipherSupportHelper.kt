/*
 * Copyright 2023-2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.db.impl

import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.google.android.fhir.DatabaseErrorStrategy
import com.google.android.fhir.db.DatabaseEncryptionException
import com.google.android.fhir.db.DatabaseEncryptionException.DatabaseEncryptionErrorCode.TIMEOUT
import com.google.android.fhir.db.DatabaseEncryptionException.DatabaseEncryptionErrorCode.UNKNOWN
import com.google.android.fhir.db.impl.DatabaseImpl.Companion.UNENCRYPTED_DATABASE_NAME
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import timber.log.Timber

/** A [SupportSQLiteOpenHelper] which initializes a [SQLiteDatabase] with a passphrase. */
internal class SQLCipherSupportHelper(
  private val configuration: SupportSQLiteOpenHelper.Configuration,
  private val hook: SQLiteDatabaseHook? = null,
  private val databaseErrorStrategy: DatabaseErrorStrategy,
  private val passphraseFetcher: () -> ByteArray,
) : SupportSQLiteOpenHelper {

  init {
    // net.zetetic:sqlcipher-android has no SQLiteDatabase.loadLibs(context); loading the
    // native library is the caller's job. Repeat calls in a classloader are a no-op.
    System.loadLibrary("sqlcipher")
  }

  /**
   * Built on first open rather than in the constructor: sqlcipher-android takes the passphrase
   * in SQLiteOpenHelper's constructor, where android-database-sqlcipher took it in
   * getWritableDatabase(passphrase). The passphrase is fetched lazily (and with retry), so the
   * helper cannot exist before the first open.
   */
  private var standardHelper: SQLiteOpenHelper? = null

  /** Remembered so it can be passed whenever [standardHelper] is (re)built. */
  private var writeAheadLoggingEnabled = false

  private fun buildStandardHelper(passphrase: ByteArray): SQLiteOpenHelper =
    object :
      SQLiteOpenHelper(
        configuration.context,
        configuration.name,
        passphrase,
        /* factory= */ null,
        configuration.callback.version,
        /* minimumSupportedVersion= */ 0,
        /* errorHandler= */ null,
        hook,
        writeAheadLoggingEnabled,
      ) {
      override fun onCreate(db: SQLiteDatabase) {
        configuration.callback.onCreate(db)
      }

      override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        configuration.callback.onUpgrade(db, oldVersion, newVersion)
      }

      override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        configuration.callback.onDowngrade(db, oldVersion, newVersion)
      }

      override fun onOpen(db: SQLiteDatabase) {
        configuration.callback.onOpen(db)
      }

      override fun onConfigure(db: SQLiteDatabase) {
        configuration.callback.onConfigure(db)
      }
    }

  override val databaseName
    get() = configuration.name

  override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
    writeAheadLoggingEnabled = enabled
    standardHelper?.setWriteAheadLoggingEnabled(enabled)
  }

  private fun openWritableDatabase(passphrase: ByteArray): SupportSQLiteDatabase {
    val helper = standardHelper ?: buildStandardHelper(passphrase).also { standardHelper = it }
    return helper.writableDatabase
  }

  override val writableDatabase: SupportSQLiteDatabase
    get() {
      check(!configuration.context.getDatabasePath(UNENCRYPTED_DATABASE_NAME).exists()) {
        "Unexpected unencrypted database, $UNENCRYPTED_DATABASE_NAME, already exists. " +
          "Check if you have accidentally disabled database encryption across releases."
      }
      val key = runBlocking { getPassphraseWithRetry() }
      return try {
        openWritableDatabase(key)
      } catch (ex: SQLiteException) {
        if (databaseErrorStrategy == DatabaseErrorStrategy.RECREATE_AT_OPEN) {
          Timber.w("Fail to open database. Recreating database.")
          // Drop the helper too: it holds the handle to the file being deleted.
          standardHelper?.close()
          standardHelper = null
          configuration.context.getDatabasePath(databaseName).delete()
          openWritableDatabase(key)
        } else {
          throw ex
        }
      }
    }

  private suspend fun getPassphraseWithRetry(): ByteArray {
    var lastException: DatabaseEncryptionException? = null
    for (retryAttempt in 1..MAX_RETRY_ATTEMPTS) {
      try {
        return passphraseFetcher()
      } catch (exception: DatabaseEncryptionException) {
        lastException = exception
        if (exception.errorCode == TIMEOUT) {
          Timber.i("Fail to get the encryption key on attempt: $retryAttempt")
          delay(retryDelay.toMillis() * retryAttempt)
        } else {
          throw exception
        }
      }
    }
    Timber.w("Can't access the database encryption key after $MAX_RETRY_ATTEMPTS attempts.")
    throw lastException ?: DatabaseEncryptionException(Exception(), UNKNOWN)
  }

  override val readableDatabase
    get() = writableDatabase

  override fun close() {
    standardHelper?.close()
    standardHelper = null
  }

  private companion object {
    const val MAX_RETRY_ATTEMPTS = 3

    /** The time delay before retrying a database operation. */
    val retryDelay: Duration = Duration.ofSeconds(1)
  }
}
