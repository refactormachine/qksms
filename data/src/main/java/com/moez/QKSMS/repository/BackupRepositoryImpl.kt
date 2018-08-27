/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.moez.QKSMS.repository

import android.content.Context
import android.os.Environment
import android.provider.Telephony
import androidx.core.content.contentValuesOf
import com.moez.QKSMS.model.BackupFile
import com.moez.QKSMS.model.Message
import com.moez.QKSMS.util.QkFileObserver
import com.squareup.moshi.Moshi
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import io.realm.Realm
import okio.Okio
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class BackupRepositoryImpl @Inject constructor(
        private val context: Context,
        private val moshi: Moshi
) : BackupRepository {

    companion object {
        private val BACKUP_DIRECTORY = Environment.getExternalStorageDirectory().toString() + "/QKSMS/Backups"
    }

    data class Backup(
            val messages: List<BackupMessage>
    )

    data class BackupMessage(
            val type: Int,
            val address: String,
            val date: Long,
            val dateSent: Long,
            val read: Boolean,
            val status: Int,
            val body: String,
            val protocol: Int,
            val serviceCenter: String?,
            val locked: Boolean,
            val subId: Int)

    // Subjects to emit our progress events to
    private val backupProgress: Subject<BackupRepository.Progress> = BehaviorSubject.createDefault(BackupRepository.Progress.Idle())
    private val restoreProgress: Subject<BackupRepository.Progress> = BehaviorSubject.createDefault(BackupRepository.Progress.Idle())

    override fun performBackup() {
        // If a backup or restore is already running, don't do anything
        if (isBackupOrRestoreRunning()) return

        // Map all the messages into our object we'll use for the Json mapping
        val backupMessages = Realm.getDefaultInstance().use { realm ->
            // Get the messages from realm
            val messages = realm.where(Message::class.java).sort("date").findAll().createSnapshot()
            val messageCount = messages.size

            // Map the messages to the new format
            val startTime = System.currentTimeMillis()
            messages.mapIndexed { index, message ->
                val progress = index.toDouble() / messageCount * 100
                val elapsed = System.currentTimeMillis() - startTime
                val remaining = when {
                    index > 100 -> TimeUnit.MILLISECONDS.toSeconds((elapsed.toDouble() / index * (messageCount - index)).toLong())
                    else -> 0
                }

                // Update the progress
                backupProgress.onNext(BackupRepository.Progress.Running(progress.toInt(), "$remaining seconds remaining"))
                messageToBackupMessage(message)
            }
        }

        // Update the status, and set the progress to be indeterminate since we can no longer calculate progress
        backupProgress.onNext(BackupRepository.Progress.Running(0, "Saving..."))

        // Convert the data to json
        val adapter = moshi.adapter(Backup::class.java).indent("\t")
        val json = adapter.toJson(Backup(backupMessages)).toByteArray()

        try {
            // Create the directory and file
            val dir = File(BACKUP_DIRECTORY).apply { mkdirs() }
            val file = File(dir, "backup-${SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(System.currentTimeMillis())}.json")

            // Write the log to the file
            FileOutputStream(file, true).use { fileOutputStream -> fileOutputStream.write(json) }
        } catch (e: Exception) {
        }

        backupProgress.onNext(BackupRepository.Progress.Idle())
    }

    private fun messageToBackupMessage(message: Message): BackupMessage = BackupMessage(
            type = message.boxId,
            address = message.address,
            date = message.date,
            dateSent = message.dateSent,
            read = message.read,
            status = message.deliveryStatus,
            body = message.body,
            protocol = 0,
            serviceCenter = null,
            locked = message.locked,
            subId =  message.subId)

    override fun getBackupProgress(): Observable<BackupRepository.Progress> = backupProgress

    override fun getBackups(): Observable<List<BackupFile>> = QkFileObserver(BACKUP_DIRECTORY).observable
            .map { File(BACKUP_DIRECTORY).listFiles() ?: arrayOf() }
            .subscribeOn(Schedulers.io())
            .observeOn(Schedulers.computation())
            .map { files ->
                files.map { file ->
                    val path = file.path
                    val date = file.lastModified()
                    val messages = 0
                    val size = file.length()
                    BackupFile(path, date, messages, size)
                }
            }
            .map { files -> files.sortedByDescending { file -> file.date } }

    override fun performRestore(backupFile: BackupFile) {
        // If a backupFile or restore is already running, don't do anything
        if (isBackupOrRestoreRunning()) return

        restoreProgress.onNext(BackupRepository.Progress.Running(0, "Parsing backup"))

        val file = File(backupFile.path)
        val source = Okio.buffer(Okio.source(file))

        val backup = moshi.adapter(Backup::class.java).fromJson(source)
        val messageCount = backup?.messages?.size ?: 0

        backup?.messages?.forEachIndexed { index, message ->
            // Update the progress
            val progress = Math.ceil(index.toDouble() / messageCount * 100).toInt()
            restoreProgress.onNext(BackupRepository.Progress.Running(progress, "$index/$messageCount messages"))

            context.contentResolver.insert(Telephony.Sms.CONTENT_URI, contentValuesOf(
                    Telephony.Sms.TYPE to message.type,
                    Telephony.Sms.ADDRESS to message.address,
                    Telephony.Sms.DATE to message.date,
                    Telephony.Sms.DATE_SENT to message.dateSent,
                    Telephony.Sms.READ to message.read,
                    Telephony.Sms.SEEN to 1,
                    Telephony.Sms.STATUS to message.status,
                    Telephony.Sms.BODY to message.body,
                    Telephony.Sms.PROTOCOL to message.protocol,
                    Telephony.Sms.SERVICE_CENTER to message.serviceCenter,
                    Telephony.Sms.LOCKED to message.locked,
                    Telephony.Sms.SUBSCRIPTION_ID to message.subId))
        }

        restoreProgress.onNext(BackupRepository.Progress.Idle())
    }

    override fun getRestoreProgress(): Observable<BackupRepository.Progress> = restoreProgress

    private fun isBackupOrRestoreRunning(): Boolean {
        return backupProgress.blockingFirst() is BackupRepository.Progress.Running
                || restoreProgress.blockingFirst() is BackupRepository.Progress.Running
    }

}