package com.example.appupdatesdk.appUpdate

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val COLLECTION_NAME = "app_updates"

    @Volatile
    private var isCheckRunning = false

    @Volatile
    private var isUpdateScreenOpening = false

    fun check(activity: Activity) {
        if (activity.isFinishing || isCheckRunning || isUpdateScreenOpening) {
            return
        }

        isCheckRunning = true

        FirebaseFirestore.getInstance().collection(COLLECTION_NAME).document(activity.packageName)
            .get().addOnSuccessListener { snapshot ->
                isCheckRunning = false

                if (!snapshot.exists()) {
                    Log.d(
                        TAG, "No Firestore update document for ${activity.packageName}"
                    )
                    return@addOnSuccessListener
                }

                val payload = snapshot.toObject(
                    AppUpdatePayload::class.java
                ) ?: return@addOnSuccessListener

                val decision = AppUpdateDecision.create(
                    context = activity, payload = payload
                ) ?: return@addOnSuccessListener

                openUpdateActivity(
                    activity = activity, decision = decision
                )
            }.addOnFailureListener { error ->
                isCheckRunning = false

                /*
                 * Never crash/block the host app because Firebase
                 * or the network is unavailable.
                 */
                Log.e(
                    TAG, "Failed to read update configuration", error
                )
            }
    }

    private fun openUpdateActivity(
        activity: Activity, decision: AppUpdateDecision
    ) {
        if (activity.isFinishing || isUpdateScreenOpening) {
            return
        }

        isUpdateScreenOpening = true

        try {
            val intent = Intent(
                activity, DownloadActivity::class.java
            ).apply {
                putExtra(
                    DownloadActivity.EXTRA_URL, decision.updateUrl
                )

                putExtra(
                    DownloadActivity.EXTRA_VERSION, decision.version
                )

                putExtra(
                    DownloadActivity.EXTRA_CHANGE_LOG, decision.changeLog
                )

                putExtra(
                    DownloadActivity.EXTRA_IS_FORCE, decision.isForce
                )

                putExtra(
                    DownloadActivity.EXTRA_IS_MAINTENANCE_MODE, decision.isMaintenanceMode
                )

                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

            activity.startActivity(intent)
        } catch (error: Exception) {
            isUpdateScreenOpening = false

            Log.e(
                TAG, "Unable to open DownloadActivity", error
            )
        }
    }

    fun resetLaunchState() {
        isUpdateScreenOpening = false
    }
}