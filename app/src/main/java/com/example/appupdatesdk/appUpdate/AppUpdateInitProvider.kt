package com.example.appupdatesdk.appUpdate

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

class AppUpdateInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val application = context?.applicationContext as? Application ?: return false

        AppUpdateAutoStarter.initialize(application)

        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri, values: ContentValues?
    ): Uri? = null

    override fun delete(
        uri: Uri, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0
}

object AppUpdateAutoStarter : Application.ActivityLifecycleCallbacks {

    @Volatile
    private var initialized = false

    @Volatile
    private var checkedInCurrentProcess = false

    fun initialize(application: Application) {
        if (initialized) {
            return
        }

        initialized = true

        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {/*
         * Never call Firestore again from DownloadActivity itself.
         */
        if (activity is DownloadActivity) {
            return
        }

        /*
         * Perform only one automatic check per application process.
         */
        if (checkedInCurrentProcess) {
            return
        }

        checkedInCurrentProcess = true

        AppUpdateManager.check(activity)
    }

    override fun onActivityCreated(
        activity: Activity, savedInstanceState: Bundle?
    ) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity, outState: Bundle
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}