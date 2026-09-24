package com.example.appupdatesdk.appUpdate

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.appupdatesdk.R
import com.example.appupdatesdk.appUpdate.adapter.ChangeLogAdapter
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File

class DownloadActivity : AppCompatActivity(), DownloadService.DownloadListener {

    private lateinit var updateButton: Button
    private lateinit var closeButton: View
    private lateinit var progressDialog: AlertDialog

    private var progressTextView: TextView? = null
    private var progressBarView: ProgressBar? = null

    private var fileName = ""
    private var downloadUrl = ""
    private var version = ""
    private var changeLog = ""

    private var serverVersionCode = 0
    private var isForce = false
    private var isMaintenanceMode = false

    private var apkUri: Uri? = null
    private var downloadService: DownloadService? = null
    private var isBound = false
    private var permissionSheet: BottomSheetDialog? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionSheet?.dismiss()
            permissionSheet = null
            proceedWithSetup()
        } else {
            showNotificationPermissionDeniedDialog()
        }
    }

    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val uri = apkUri ?: return@registerForActivityResult

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
            launchPackageInstaller(uri)
        } else {
            Toast.makeText(
                this, "Allow installs from this app to continue.", Toast.LENGTH_LONG
            ).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {

        override fun onServiceConnected(
            name: ComponentName?, service: IBinder?
        ) {
            val binder = service as? DownloadService.DownloadBinder ?: return

            downloadService = binder.getService()
            downloadService?.listener = this@DownloadActivity
            isBound = true

            if (downloadService?.isDownloading == true) {
                showProgressDialog()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            downloadService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_download)

        setupSystemInsets()
        initializeViews()
        readIntentData()

        if (!isMaintenanceMode && downloadUrl.isBlank()) {
            Toast.makeText(
                this, "Invalid update download URL.", Toast.LENGTH_LONG
            ).show()

            finish()
            return
        }

        if (isMaintenanceMode) {
            proceedWithSetup()
        } else {
            enforceNotificationPermission()
        }
    }

    override fun onDestroy() {
        downloadService?.listener = null

        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }

        dismissProgressDialog()

        permissionSheet?.dismiss()
        permissionSheet = null

        super.onDestroy()
    }

    private fun setupSystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.main)
        ) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            view.setPadding(
                systemBars.left, systemBars.top, systemBars.right, systemBars.bottom
            )

            WindowInsetsControllerCompat(
                window, window.decorView
            ).isAppearanceLightStatusBars = false

            insets
        }
    }

    private fun initializeViews() {
        updateButton = findViewById(R.id.updateButton)
        closeButton = findViewById(R.id.close)
    }

    private fun readIntentData() {
        downloadUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        version = intent.getStringExtra(EXTRA_VERSION).orEmpty()
        changeLog = intent.getStringExtra(EXTRA_CHANGE_LOG).orEmpty()

        serverVersionCode = intent.getIntExtra(
            EXTRA_SERVER_VERSION_CODE, 0
        )

        isForce = intent.getBooleanExtra(
            EXTRA_IS_FORCE, false
        )

        isMaintenanceMode = intent.getBooleanExtra(
            EXTRA_IS_MAINTENANCE_MODE, false
        )
    }

    private fun enforceNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            proceedWithSetup()
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            proceedWithSetup()
        } else {
            showNotificationPermissionSheet()
        }
    }

    private fun showNotificationPermissionSheet() {
        if (isFinishing || permissionSheet?.isShowing == true) {
            return
        }

        val sheetView = layoutInflater.inflate(
            R.layout.bottom_sheet_notification_permission, null
        )

        permissionSheet = BottomSheetDialog(this).apply {
            setContentView(sheetView)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            behavior.isDraggable = false
            behavior.isHideable = false
        }

        sheetView.findViewById<Button>(
            R.id.btn_allow_notification
        ).setOnClickListener {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        permissionSheet?.show()
    }

    private fun showNotificationPermissionDeniedDialog() {
        AlertDialog.Builder(this).setTitle("Notification permission required").setMessage(
            "Notification permission is needed to show download progress. " + "Enable it in Settings to continue."
        ).setCancelable(false).setPositiveButton("Open Settings") { _, _ ->
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                ).apply {
                    data = Uri.fromParts(
                        "package", packageName, null
                    )
                })
        }.setNegativeButton("Exit") { _, _ ->
            finish()
        }.show()
    }

    private fun proceedWithSetup() {
        fileName = createApkFileName()

        setupCloseButton()
        setupChangeLog()
        setupVersionText()
        setupBackPressHandling()

        if (isMaintenanceMode) {
            setupMaintenanceMode()
            return
        }

        restoreDownloadState()
        bindDownloadServiceIfNeeded()
    }

    private fun createApkFileName(): String {
        val safeAppName =
            getApplicationName().replace(Regex("[^A-Za-z0-9._-]"), "_").replace(Regex("_+"), "_")
                .trim('_').ifBlank { "app" }

        val safeVersion =
            version.replace(Regex("[^A-Za-z0-9._-]"), "_").replace(Regex("_+"), "_").trim('_')
                .ifBlank { "latest" }

        return "${safeAppName}_${safeVersion}.apk"
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

    private fun setupCloseButton() {
        if (isMaintenanceMode || isForce) {
            closeButton.visibility = View.GONE
            return
        }

        closeButton.visibility = View.VISIBLE

        closeButton.setOnClickListener {
            finish()
        }
    }

    private fun setupChangeLog() {
        val items = changeLog.split(".").map(String::trim).filter(String::isNotEmpty).map { "$it." }

        findViewById<RecyclerView>(R.id.change_log).apply {
            layoutManager = LinearLayoutManager(this@DownloadActivity)
            adapter = ChangeLogAdapter(items)
        }
    }

    private fun setupVersionText() {
        findViewById<TextView>(R.id.version).text = when {
            isMaintenanceMode -> "Maintenance mode"
            version.isBlank() -> ""
            else -> "v$version"
        }
    }

    private fun setupMaintenanceMode() {
        updateButton.text = "Close"
        updateButton.isEnabled = true

        updateButton.setOnClickListener {
            finishAffinity()
        }
    }

    private fun restoreDownloadState() {
        val prefs = getSharedPreferences(
            DownloadService.SHARED_PREFERENCES_PREFIX, MODE_PRIVATE
        )

        val savedUrl = prefs.getString(
            DownloadService.PREF_DOWNLOAD_URL, null
        )

        val savedVersionCode = prefs.getInt(
            DownloadService.PREF_SERVER_VERSION_CODE, 0
        )

        /*
         * Clear only if this screen represents a different APK release.
         */
        if (savedUrl != null && (savedUrl != downloadUrl || (serverVersionCode > 0 && savedVersionCode > 0 && savedVersionCode != serverVersionCode))) {
            clearDownloadState()
        }

        val savedUri = prefs.getString(
            DownloadService.PREF_APK_URI, null
        )

        val savedTempPath = prefs.getString(
            DownloadService.PREF_TEMP_PATH, null
        )

        when {
            !savedUri.isNullOrBlank() && canReadUri(Uri.parse(savedUri)) -> {
                apkUri = Uri.parse(savedUri)
                setButtonInstall()
            }

            !savedUri.isNullOrBlank() -> {
                clearDownloadState()
                setButtonDownload()
            }

            !savedTempPath.isNullOrBlank() && File(savedTempPath).let {
                it.exists() && it.length() > 0L
            } -> {
                setButtonResume()
            }

            else -> {
                setButtonDownload()
            }
        }
    }

    private fun canReadUri(uri: Uri): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.use {
                true
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun bindDownloadServiceIfNeeded() {
        if (isBound) {
            return
        }

        bindService(
            Intent(this, DownloadService::class.java), serviceConnection, BIND_AUTO_CREATE
        )
    }

    private fun setupBackPressHandling() {
        onBackPressedDispatcher.addCallback(this) {
            when {
                isMaintenanceMode || isForce -> {
                    moveTaskToBack(true)
                }

                downloadService?.isDownloading == true -> {
                    Toast.makeText(
                        this@DownloadActivity,
                        "Download continues in the background.",
                        Toast.LENGTH_SHORT
                    ).show()

                    dismissProgressDialog()
                    moveTaskToBack(true)
                }

                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    override fun onProgress(percent: Int) {
        runOnUiThread {
            updateProgress(percent)
        }
    }

    override fun onComplete(uri: Uri) {
        apkUri = uri

        runOnUiThread {
            dismissProgressDialog()
            setButtonInstall()
            showInstallDialog(uri)
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            dismissProgressDialog()
            setButtonResume()

            Toast.makeText(
                this, "Download failed: $message", Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun setButtonDownload() {
        updateButton.text = "Download Update"
        updateButton.isEnabled = true

        updateButton.setOnClickListener {
            if (!isNetworkAvailable()) {
                showNoInternetDialog()
                return@setOnClickListener
            }

            showProgressDialog()
            startDownloadService()
        }
    }

    private fun setButtonResume() {
        updateButton.text = "Resume Download"
        updateButton.isEnabled = true

        updateButton.setOnClickListener {
            if (!isNetworkAvailable()) {
                showNoInternetDialog()
                return@setOnClickListener
            }

            showProgressDialog()
            startDownloadService()
        }
    }

    private fun setButtonInstall() {
        updateButton.text = "Install Now"
        updateButton.isEnabled = true

        updateButton.setOnClickListener {
            apkUri?.let(::showInstallDialog)
        }
    }

    private fun startDownloadService() {
        val intent = Intent(
            this, DownloadService::class.java
        ).apply {
            action = DownloadService.ACTION_START

            putExtra(
                DownloadService.EXTRA_URL, downloadUrl
            )

            putExtra(
                DownloadService.EXTRA_VERSION, version
            )

            putExtra(
                DownloadService.EXTRA_FILE_NAME, fileName
            )

            putExtra(
                DownloadService.EXTRA_CHANGE_LOG, changeLog
            )

            putExtra(
                DownloadService.EXTRA_IS_FORCE, isForce
            )

            putExtra(
                DownloadService.EXTRA_SERVER_VERSION_CODE, serverVersionCode
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        bindDownloadServiceIfNeeded()
    }

    private fun showInstallDialog(uri: Uri) {
        if (isFinishing) {
            return
        }

        AlertDialog.Builder(this).setTitle("Download complete")
            .setMessage("Install the update now?").setCancelable(false)
            .setPositiveButton("Install") { _, _ ->
                installApk(uri)
            }.setNegativeButton("Later") { _, _ ->
                finish()
            }.show()
    }

    private fun installApk(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            openUnknownSourcesSettings()
            return
        }

        launchPackageInstaller(uri)
    }

    private fun openUnknownSourcesSettings() {
        unknownSourcesLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")
            )
        )
    }

    private fun launchPackageInstaller(uri: Uri) {
        try {
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    uri, APK_MIME_TYPE
                )

                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(installIntent)
            finish()
        } catch (error: Exception) {
            Toast.makeText(
                this, "Unable to open the installer: ${error.message}", Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showProgressDialog() {
        if (::progressDialog.isInitialized && progressDialog.isShowing) {
            return
        }

        val view = layoutInflater.inflate(
            R.layout.dialog_download_progress, null
        )

        progressTextView = view.findViewById(
            R.id.progress_text
        )

        progressBarView = view.findViewById(
            R.id.progress_bar
        )

        view.findViewById<Button>(
            R.id.btn_cancel_download
        ).setOnClickListener {
            cancelCurrentDownload()
        }

        progressDialog = AlertDialog.Builder(
            this, R.style.ProgressDialogTheme
        ).setView(view).setCancelable(false).create()

        progressDialog.show()
    }

    private fun cancelCurrentDownload() {
        if (isBound && downloadService != null) {
            downloadService?.cancelDownload()
        } else {
            startService(
                Intent(
                    this, DownloadService::class.java
                ).apply {
                    action = DownloadService.ACTION_CANCEL
                })
        }

        dismissProgressDialog()
        setButtonResume()
    }

    private fun updateProgress(percent: Int) {
        progressTextView?.text = "$percent%"
        progressBarView?.progress = percent
    }

    private fun dismissProgressDialog() {
        if (::progressDialog.isInitialized && progressDialog.isShowing) {
            progressDialog.dismiss()
        }
    }

    private fun clearDownloadState() {
        val prefs = getSharedPreferences(
            DownloadService.SHARED_PREFERENCES_PREFIX, MODE_PRIVATE
        )

        prefs.getString(
            DownloadService.PREF_TEMP_PATH, null
        )?.takeIf(String::isNotBlank)?.let { path ->
            File(path).takeIf(File::exists)?.delete()
        }

        prefs.getString(
            DownloadService.PREF_APK_URI, null
        )?.takeIf(String::isNotBlank)?.let { uriString ->
            try {
                contentResolver.delete(
                    Uri.parse(uriString), null, null
                )
            } catch (_: Exception) {
            }
        }

        prefs.edit().remove(DownloadService.PREF_APK_URI).remove(DownloadService.PREF_TEMP_PATH)
            .remove(DownloadService.PREF_DOWNLOAD_URL)
            .remove(DownloadService.PREF_SERVER_VERSION_CODE).apply()

        apkUri = null
    }

    private fun isNetworkAvailable(): Boolean {
        val manager = getSystemService(
            CONNECTIVITY_SERVICE
        ) as ConnectivityManager

        val network = manager.activeNetwork ?: return false

        val capabilities = manager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        )
    }

    private fun showNoInternetDialog() {
        AlertDialog.Builder(this).setTitle("No internet connection").setMessage(
            "Please check your internet connection and try again."
        ).setCancelable(false).setPositiveButton("Retry") { _, _ ->
            updateButton.performClick()
        }.setNegativeButton("Exit") { _, _ ->
            finish()
        }.show()
    }

    companion object {
        const val EXTRA_URL = "URL"
        const val EXTRA_VERSION = "version"
        const val EXTRA_CHANGE_LOG = "changeLog"
        const val EXTRA_SERVER_VERSION_CODE = "serverVersionCode"
        const val EXTRA_IS_FORCE = "isForce"
        const val EXTRA_IS_MAINTENANCE_MODE = "isMaintenanceMode"

        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}