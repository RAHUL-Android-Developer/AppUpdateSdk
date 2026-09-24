package com.example.appupdatesdk.appUpdate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.example.appupdatesdk.R
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class DownloadService : Service() {

    inner class DownloadBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }

    interface DownloadListener {
        fun onProgress(percent: Int)
        fun onComplete(uri: Uri)
        fun onError(message: String)
    }

    private val binder = DownloadBinder()

    @Volatile
    private var isDownloadCancelled = false

    @Volatile
    var isDownloading = false
        private set

    private var downloadThread: Thread? = null

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    var listener: DownloadListener? = null

    private var downloadUrl = ""
    private var version = ""
    private var fileName = ""
    private var folderName = ""
    private var changeLog = ""
    private var isForce = false
    private var serverVersionCode = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(
        intent: Intent?, flags: Int, startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_CANCEL -> cancelDownload()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isDownloadCancelled = true
        activeConnection?.disconnect()
        downloadThread?.interrupt()
        listener = null

        super.onDestroy()
    }

    private fun handleStart(intent: Intent) {
        if (isDownloading) {
            return
        }

        downloadUrl = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()

        if (downloadUrl.isBlank()) {
            stopSelf()
            return
        }

        version = intent.getStringExtra(EXTRA_VERSION)?.trim().orEmpty()

        changeLog = intent.getStringExtra(EXTRA_CHANGE_LOG).orEmpty()

        isForce = intent.getBooleanExtra(
            EXTRA_IS_FORCE, false
        )

        serverVersionCode = intent.getIntExtra(
            EXTRA_SERVER_VERSION_CODE, 0
        )

        fileName = intent.getStringExtra(EXTRA_FILE_NAME)?.trim()?.takeIf(String::isNotBlank)
            ?: createFallbackFileName(version)

        if (!fileName.endsWith(".apk", ignoreCase = true)) {
            fileName = "$fileName.apk"
        }

        folderName = fileName.removeSuffix(".apk").removeSuffix(".APK")
            .replace(Regex("[^A-Za-z0-9._-]"), "_").replace(Regex("_+"), "_").trim('_')
            .ifBlank { "update" }

        startAsForegroundService()
        startDownload()
    }

    private fun createFallbackFileName(version: String): String {
        val appName =
            getApplicationName().replace(Regex("[^A-Za-z0-9._-]"), "_").replace(Regex("_+"), "_")
                .trim('_').ifBlank { "app" }

        val safeVersion =
            version.replace(Regex("[^A-Za-z0-9._-]"), "_").replace(Regex("_+"), "_").trim('_')
                .ifBlank { "latest" }

        return "${appName}_${safeVersion}.apk"
    }

    private fun getApplicationName(): String {
        return try {
            packageManager.getApplicationLabel(applicationInfo).toString().ifBlank {
                packageName.substringAfterLast(".")
            }
        } catch (_: Exception) {
            packageName.substringAfterLast(".")
        }
    }

    private fun getApplicationIconBitmap(): Bitmap? {
        return try {
            val drawable = packageManager.getApplicationIcon(
                applicationInfo
            )

            drawableToBitmap(drawable)
        } catch (_: Exception) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }

        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: DEFAULT_ICON_SIZE

        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: DEFAULT_ICON_SIZE

        val bitmap = Bitmap.createBitmap(
            width, height, Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(bitmap)

        drawable.setBounds(
            0, 0, canvas.width, canvas.height
        )

        drawable.draw(canvas)

        return bitmap
    }

    private fun startAsForegroundService() {
        val notification = buildProgressNotification(
            percent = 0, isIndeterminate = true
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(
                NOTIFICATION_ID, notification
            )
        }
    }

    private fun startDownload() {
        if (isDownloading) {
            return
        }

        isDownloading = true
        isDownloadCancelled = false

        downloadThread = Thread {
            downloadApk()
        }.apply {
            name = "AppUpdateDownloadThread"
            start()
        }
    }

    private fun downloadApk() {
        var connection: HttpURLConnection? = null
        var input: BufferedInputStream? = null
        var output: FileOutputStream? = null

        try {
            val preferences = getSharedPreferences(
                SHARED_PREFERENCES_PREFIX, MODE_PRIVATE
            )

            val savedUrl = preferences.getString(
                PREF_DOWNLOAD_URL, null
            )

            val savedVersionCode = preferences.getInt(
                PREF_SERVER_VERSION_CODE, 0
            )

            val savedTempPath = preferences.getString(
                PREF_TEMP_PATH, null
            )

            val canResume =
                savedUrl == downloadUrl && (serverVersionCode <= 0 || savedVersionCode <= 0 || savedVersionCode == serverVersionCode) && !savedTempPath.isNullOrBlank()

            val tempFile = if (canResume) {
                File(savedTempPath!!)
            } else {
                savedTempPath?.takeIf(String::isNotBlank)?.let { path ->
                    File(path).takeIf(File::exists)?.delete()
                }

                File(cacheDir, fileName)
            }

            if (!canResume) {
                clearSavedDownloadState(
                    deleteTempFile = false, deleteCompletedApk = false
                )
            }

            preferences.edit().putString(
                PREF_DOWNLOAD_URL, downloadUrl
            ).putString(
                PREF_TEMP_PATH, tempFile.absolutePath
            ).putInt(
                PREF_SERVER_VERSION_CODE, serverVersionCode
            ).apply()

            val alreadyDownloadedBytes = if (tempFile.exists()) {
                tempFile.length()
            } else {
                0L
            }

            connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                activeConnection = this

                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
                instanceFollowRedirects = true

                setRequestProperty(
                    "Accept-Encoding", "identity"
                )

                if (alreadyDownloadedBytes > 0L) {
                    setRequestProperty(
                        "Range", "bytes=$alreadyDownloadedBytes-"
                    )
                }
            }

            connection.connect()

            val responseCode = connection.responseCode

            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException(
                    "Server returned HTTP $responseCode"
                )
            }

            val appendToFile =
                responseCode == HttpURLConnection.HTTP_PARTIAL && alreadyDownloadedBytes > 0L

            val startBytes = if (appendToFile) {
                alreadyDownloadedBytes
            } else {
                if (tempFile.exists()) {
                    tempFile.delete()
                }

                0L
            }

            val responseContentLength = connection.contentLengthLong

            val totalSize = if (responseContentLength > 0L) {
                startBytes + responseContentLength
            } else {
                -1L
            }

            input = BufferedInputStream(
                connection.inputStream
            )

            output = FileOutputStream(
                tempFile, appendToFile
            )

            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalDownloaded = startBytes
            var bytesRead: Int
            var lastPercent = -1

            while (input.read(buffer).also {
                    bytesRead = it
                } != -1) {
                if (isDownloadCancelled || Thread.currentThread().isInterrupted) {
                    return
                }

                output.write(
                    buffer, 0, bytesRead
                )

                totalDownloaded += bytesRead

                if (totalSize > 0L) {
                    val percent = ((totalDownloaded * 100L) / totalSize).toInt().coerceIn(0, 100)

                    if (percent != lastPercent) {
                        lastPercent = percent
                        updateProgress(percent)
                    }
                }
            }

            output.flush()

            if (isDownloadCancelled || Thread.currentThread().isInterrupted) {
                return
            }

            val apkUri = publishApkToDownloads(tempFile) ?: throw IOException(
                "Could not save APK to Downloads"
            )

            preferences.edit().putString(
                PREF_APK_URI, apkUri.toString()
            ).remove(PREF_TEMP_PATH).apply()

            tempFile.delete()

            isDownloading = false

            showCompleteNotification(apkUri)
            listener?.onComplete(apkUri)

            stopSelf()

        } catch (error: Exception) {
            isDownloading = false

            if (!isDownloadCancelled) {
                val message = error.message ?: "Unknown download error"

                showErrorNotification(message)
                listener?.onError(message)
            }

            stopSelf()

        } finally {
            try {
                input?.close()
            } catch (_: Exception) {
            }

            try {
                output?.close()
            } catch (_: Exception) {
            }

            connection?.disconnect()
            activeConnection = null
        }
    }

    fun cancelDownload() {
        isDownloadCancelled = true
        isDownloading = false

        activeConnection?.disconnect()
        downloadThread?.interrupt()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateProgress(percent: Int) {
        updateNotification(percent)
        listener?.onProgress(percent)
    }

    private fun clearSavedDownloadState(
        deleteTempFile: Boolean, deleteCompletedApk: Boolean
    ) {
        val preferences = getSharedPreferences(
            SHARED_PREFERENCES_PREFIX, MODE_PRIVATE
        )

        if (deleteTempFile) {
            preferences.getString(
                PREF_TEMP_PATH, null
            )?.takeIf(String::isNotBlank)?.let { path ->
                File(path).takeIf(File::exists)?.delete()
            }
        }

        if (deleteCompletedApk) {
            preferences.getString(
                PREF_APK_URI, null
            )?.takeIf(String::isNotBlank)?.let { uriString ->
                try {
                    contentResolver.delete(
                        Uri.parse(uriString), null, null
                    )
                } catch (_: Exception) {
                }
            }
        }

        preferences.edit().remove(PREF_APK_URI).remove(PREF_TEMP_PATH).remove(PREF_DOWNLOAD_URL)
            .remove(PREF_SERVER_VERSION_CODE).apply()
    }

    private fun publishApkToDownloads(
        tempFile: File
    ): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishUsingMediaStore(tempFile)
            } else {
                publishUsingLegacyStorage(tempFile)
            }
        } catch (_: Exception) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishUsingMediaStore(
        tempFile: File
    ): Uri {
        val values = ContentValues().apply {
            put(
                MediaStore.MediaColumns.DISPLAY_NAME, fileName
            )

            put(
                MediaStore.MediaColumns.MIME_TYPE, DownloadActivity.APK_MIME_TYPE
            )

            put(
                MediaStore.MediaColumns.RELATIVE_PATH, "Download/AppUpdates/$folderName"
            )

            put(
                MediaStore.MediaColumns.IS_PENDING, 1
            )
        }

        val downloadsUri = MediaStore.Downloads.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        )

        val apkUri = contentResolver.insert(
            downloadsUri, values
        ) ?: throw IOException(
            "Unable to create Downloads entry"
        )

        try {
            contentResolver.openOutputStream(apkUri)?.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw IOException(
                "Unable to open Downloads output stream"
            )

            values.clear()

            values.put(
                MediaStore.MediaColumns.IS_PENDING, 0
            )

            contentResolver.update(
                apkUri, values, null, null
            )

            return apkUri
        } catch (error: Exception) {
            contentResolver.delete(
                apkUri, null, null
            )

            throw error
        }
    }

    private fun publishUsingLegacyStorage(
        tempFile: File
    ): Uri {
        @Suppress("DEPRECATION") val downloadsDirectory =
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )

        val destinationDirectory = File(
            downloadsDirectory, "AppUpdates/$folderName"
        )

        if (!destinationDirectory.exists() && !destinationDirectory.mkdirs()) {
            throw IOException(
                "Unable to create Downloads directory"
            )
        }

        val destinationFile = File(
            destinationDirectory, fileName
        )

        tempFile.copyTo(
            target = destinationFile, overwrite = true
        )

        return FileProvider.getUriForFile(
            this, "$packageName.appupdatesdk.fileprovider", destinationFile
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID, "App update downloads", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows application update download progress"
        }

        getSystemService(
            NotificationManager::class.java
        ).createNotificationChannel(channel)
    }

    private fun cancelPendingIntent(): PendingIntent {
        val cancelIntent = Intent(
            this, DownloadService::class.java
        ).apply {
            action = ACTION_CANCEL
        }

        return PendingIntent.getService(
            this,
            REQUEST_CODE_CANCEL,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openActivityPendingIntent(): PendingIntent {
        val openIntent = Intent(
            this, DownloadActivity::class.java
        ).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP

            putExtra(
                DownloadActivity.EXTRA_URL, downloadUrl
            )

            putExtra(
                DownloadActivity.EXTRA_VERSION, version
            )

            putExtra(
                DownloadActivity.EXTRA_CHANGE_LOG, changeLog
            )

            putExtra(
                DownloadActivity.EXTRA_SERVER_VERSION_CODE, serverVersionCode
            )

            putExtra(
                DownloadActivity.EXTRA_IS_FORCE, isForce
            )

            putExtra(
                DownloadActivity.EXTRA_IS_MAINTENANCE_MODE, false
            )
        }

        return PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildProgressNotification(
        percent: Int, isIndeterminate: Boolean
    ): Notification {
        val text = if (isIndeterminate) {
            "Preparing download..."
        } else {
            "$percent%"
        }

        return NotificationCompat.Builder(
            this, CHANNEL_ID
        )/*
             * Required Android notification status-bar icon.
             * This must be a monochrome white vector drawable.
             */.setSmallIcon(
            R.drawable.ic_app_update_notification
        )

            /*
             * Uses the actual host application's launcher icon.
             */.setLargeIcon(
                getApplicationIconBitmap()
            )

            .setContentTitle(
                "Downloading ${getApplicationName()} update"
            )

            .setContentText(text)

            .setProgress(
                100, percent, isIndeterminate
            )

            .setOngoing(true)

            .setOnlyAlertOnce(true)

            .setContentIntent(
                openActivityPendingIntent()
            )

            .addAction(
                android.R.drawable.ic_delete, "Cancel", cancelPendingIntent()
            )

            .build()
    }

    private fun updateNotification(percent: Int) {
        val notificationManager = getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        notificationManager.notify(
            NOTIFICATION_ID, buildProgressNotification(
                percent = percent, isIndeterminate = false
            )
        )
    }

    private fun showCompleteNotification(apkUri: Uri) {
        val notificationManager = getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        val installIntent = Intent(
            Intent.ACTION_VIEW
        ).apply {
            setDataAndType(
                apkUri, DownloadActivity.APK_MIME_TYPE
            )

            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }

        val installPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_INSTALL,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            this, CHANNEL_ID
        ).setSmallIcon(
            R.drawable.ic_app_update_notification
        ).setLargeIcon(
            getApplicationIconBitmap()
        ).setContentTitle("Update download complete").setContentText(
            "Tap to install ${getApplicationName()} update"
        ).setAutoCancel(true).setContentIntent(installPendingIntent).build()

        stopForeground(STOP_FOREGROUND_REMOVE)

        notificationManager.notify(
            NOTIFICATION_ID, notification
        )
    }

    private fun showErrorNotification(message: String) {
        val notificationManager = getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        val notification = NotificationCompat.Builder(
            this, CHANNEL_ID
        ).setSmallIcon(
            R.drawable.ic_app_update_notification
        ).setLargeIcon(
            getApplicationIconBitmap()
        ).setContentTitle("Update download failed").setContentText(message).setAutoCancel(true)
            .setContentIntent(
                openActivityPendingIntent()
            ).build()

        stopForeground(STOP_FOREGROUND_REMOVE)

        notificationManager.notify(
            NOTIFICATION_ID, notification
        )
    }

    companion object {
        const val ACTION_START = "ACTION_START_DOWNLOAD"
        const val ACTION_CANCEL = "ACTION_CANCEL_DOWNLOAD"

        const val EXTRA_URL = "URL"
        const val EXTRA_VERSION = "VERSION"
        const val EXTRA_FILE_NAME = "FILE_NAME"
        const val EXTRA_CHANGE_LOG = "CHANGE_LOG"
        const val EXTRA_IS_FORCE = "IS_FORCE"
        const val EXTRA_SERVER_VERSION_CODE = "SERVER_VERSION_CODE"

        const val SHARED_PREFERENCES_PREFIX = "APP_UPDATE_PREFS"

        const val PREF_APK_URI = "APK_URI"
        const val PREF_TEMP_PATH = "TEMP_APK_PATH"
        const val PREF_DOWNLOAD_URL = "DOWNLOAD_URL"
        const val PREF_SERVER_VERSION_CODE = "SERVER_VERSION_CODE"

        private const val CHANNEL_ID = "app_update_download_channel"

        private const val NOTIFICATION_ID = 1001

        private const val REQUEST_CODE_CANCEL = 100
        private const val REQUEST_CODE_OPEN = 101
        private const val REQUEST_CODE_INSTALL = 102

        private const val DEFAULT_ICON_SIZE = 96
    }
}