/*
 * Copyright 2021 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.dash.wallet.features.exploredash.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import net.lingala.zip4j.ZipFile
import org.dash.wallet.common.Constants
import org.slf4j.LoggerFactory
import java.io.*
import java.lang.System.currentTimeMillis
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface ExploreRepository {
    val localDatabaseTimestamp: Long
    var lastSyncAttemptTimestamp: Long
    var preloadedOnTimestamp: Long
    var failedSyncAttempts: Int

    suspend fun getRemoteTimestamp(): Long
    fun getDatabaseInputStream(file: File): InputStream?
    fun getTimestamp(file: File): Long
    fun getUpdateFile(): File
    suspend fun download()
    fun markDbForDeletion(dbFile: File)
    fun preloadFromAssetsInto(dbUpdateFile: File)
    fun finalizeUpdate()
}

@Suppress("BlockingMethodInNonBlockingContext")
class GCExploreDatabase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: SharedPreferences,
    private val auth: FirebaseAuth,
    private val storage: FirebaseStorage
) : ExploreRepository {

    companion object {
        const val DATA_FILE_NAME = "explore.db"
        const val DATA_TMP_FILE_NAME = "explore.tmp"
        private const val DB_ASSET_FILE_NAME = "explore/$DATA_FILE_NAME"
        private const val PREFS_LOCAL_DB_TIMESTAMP_KEY = "local_db_timestamp"
        private const val LAST_SYNC_TIMESTAMP_KEY = "last_sync_timestamp"
        private const val PRELOADED_ON_TIMESTAMP_KEY = "preloaded_on"
        private const val FAILED_SYNC_ATTEMPTS_KEY = "failed_sync_attempts"

        private val log = LoggerFactory.getLogger(GCExploreDatabase::class.java)
    }

    private var remoteDataRef: StorageReference? = null

    private var updateTimestampCache = -1L

    override var localDatabaseTimestamp: Long
        get() = preferences.getLong(PREFS_LOCAL_DB_TIMESTAMP_KEY, 0)
        private set(value) {
            preferences.edit().apply {
                putLong(PREFS_LOCAL_DB_TIMESTAMP_KEY, value)
            }.apply()
        }

    override var lastSyncAttemptTimestamp: Long
        get() = preferences.getLong(LAST_SYNC_TIMESTAMP_KEY, 0)
        set(value) {
            preferences.edit().apply {
                putLong(LAST_SYNC_TIMESTAMP_KEY, value)
            }.apply()
        }

    override var preloadedOnTimestamp: Long
        get() = preferences.getLong(PRELOADED_ON_TIMESTAMP_KEY, 0)
        set(value) {
            preferences.edit().apply {
                putLong(PRELOADED_ON_TIMESTAMP_KEY, value)
            }.apply()
        }

    override var failedSyncAttempts: Int
        get() = preferences.getInt(FAILED_SYNC_ATTEMPTS_KEY, 0)
        set(value) {
            preferences.edit().apply {
                putInt(FAILED_SYNC_ATTEMPTS_KEY, value)
            }.apply()
        }

    override suspend fun getRemoteTimestamp(): Long {
        val remoteDataInfo = try {
            ensureAuthenticated()
            remoteDataRef = storage.reference.child(Constants.EXPLORE_GC_FILE_PATH)
            remoteDataRef!!.metadata.await()
        } catch (ex: Exception) {
            log.warn("error getting remote data timestamp", ex)
            null
        }
        val dataTimestamp = remoteDataInfo?.getCustomMetadata("Data-Timestamp")?.toLong()
        return dataTimestamp ?: -1L
    }

    override suspend fun download() {
        ensureAuthenticated()
        lastSyncAttemptTimestamp = currentTimeMillis()

        val cacheDir = context.cacheDir

        val tmpFile = File(cacheDir, DATA_TMP_FILE_NAME)
        val tmpFileDelete = tmpFile.delete()

        log.info("downloading explore db from server ($tmpFileDelete)")
        val startTime = currentTimeMillis()
        val result = remoteDataRef!!.getFile(tmpFile).await()
        val totalTime = (currentTimeMillis() - startTime).toFloat() / 1000
        log.info("downloaded $remoteDataRef (${result.bytesTransferred} as $tmpFile [$totalTime s]")

        val updateFile = File(cacheDir, DATA_FILE_NAME)
        val updateFileDelete = updateFile.delete()
        val tmpFileRename = tmpFile.renameTo(updateFile)

        log.info("update file $updateFile created ($updateFileDelete, $tmpFileRename)")
    }

    private suspend fun ensureAuthenticated() {
        if (auth.currentUser == null) {
            signingAnonymously()
        }
    }

    private suspend fun signingAnonymously(): FirebaseUser {
        return suspendCancellableCoroutine { coroutine ->
            auth.signInAnonymously().addOnSuccessListener { result ->
                if (coroutine.isActive) {
                    val user = result.user
                    if (user != null) {
                        coroutine.resume(user)
                    } else {
                        coroutine.resumeWithException(
                            FirebaseAuthException("-1", "User is null after anon sign in")
                        )
                    }
                }
            }.addOnFailureListener {
                if (coroutine.isActive) {
                    coroutine.resumeWithException(it)
                }
            }
        }
    }

    private fun extractComment(zipFile: ZipFile) : Array<String> {
        return zipFile.comment.split("#".toRegex()).toTypedArray()
    }

    @Throws(IOException::class)
    override fun getTimestamp(file: File): Long {
        val zipFile = ZipFile(file)
        val comment = extractComment(zipFile)
        return comment[0].toLong()
    }

    @Throws(IOException::class)
    override fun getDatabaseInputStream(file: File): InputStream? {
        val zipFile = ZipFile(file)
        val comment = extractComment(zipFile)
        updateTimestampCache = comment[0].toLong()
        val checksum = comment[1]
        log.info("package timestamp {}, checksum {}", updateTimestampCache, checksum)
        zipFile.setPassword(checksum.toCharArray())
        val zipHeader = zipFile.getFileHeader("explore.db")
        return zipFile.getInputStream(zipHeader)
    }

    override fun getUpdateFile(): File {
        return File(context.cacheDir, DATA_FILE_NAME)
    }

    override fun markDbForDeletion(dbFile: File) {
        try {
            if (dbFile.exists()) {
                dbFile.deleteOnExit()
            }
            val dbShmFile = File(dbFile.absolutePath + "-shm")
            if (dbShmFile.exists()) {
                dbShmFile.deleteOnExit()
            }
            val dbWalFile = File(dbFile.absolutePath + "-wal")
            if (dbWalFile.exists()) {
                dbWalFile.deleteOnExit()
            }
            log.info("existing explore db files to be deleted on exit")
        } catch (ex: SecurityException) {
            log.warn("unable to delete explore db", ex)
        }
    }

    @Throws(IOException::class)
    override fun preloadFromAssetsInto(dbUpdateFile: File) {
        log.info("preloading explore db from assets ${dbUpdateFile.absolutePath}")
        try {
            context.assets.open(DB_ASSET_FILE_NAME).use { inputStream ->
                FileOutputStream(dbUpdateFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        } catch (ex: FileNotFoundException) {
            log.warn("missing {}, explore db will be empty", DB_ASSET_FILE_NAME)
        }
    }

    override fun finalizeUpdate() {
        log.info("finalizing update: $updateTimestampCache")
        localDatabaseTimestamp = updateTimestampCache
    }
}
