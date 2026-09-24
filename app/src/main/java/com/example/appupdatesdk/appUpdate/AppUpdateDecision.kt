package com.example.appupdatesdk.appUpdate

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppUpdateDecision(
    val updateUrl: String,
    val version: String,
    val changeLog: String,
    val isForce: Boolean,
    val isMaintenanceMode: Boolean
) {
    companion object {

        private const val TAG = "AppUpdateDecision"

        fun create(
            context: Context, payload: AppUpdatePayload
        ): AppUpdateDecision? {
            val metadata = payload.metadata
            val conditions = payload.conditions

            if (conditions.isMaintenanceMode) {
                return AppUpdateDecision(
                    updateUrl = "",
                    version = metadata.versionName.orEmpty(),
                    changeLog = conditions.maintenanceMessage
                        ?: "The app is currently under maintenance. Please try again later.",
                    isForce = true,
                    isMaintenanceMode = true
                )
            }

            val installedVersionCode = getInstalledVersionCode(context)

            val hasValidUpdateUrl = metadata.updateUrl.isNotBlank()

            val hasNewVersion = metadata.versionCode > installedVersionCode

            val isBelowMinimumVersion =
                metadata.minSupportedVersionCode > 0 && installedVersionCode < metadata.minSupportedVersionCode

            val isForcedByType = metadata.updateType.equals(
                "force", ignoreCase = true
            )

            val deadlineExpired = isPastDeadline(
                metadata.deadline
            )

            val shouldShowUpdate =
                hasValidUpdateUrl && hasNewVersion && (conditions.isUpdateAvailable || isBelowMinimumVersion)

            if (!shouldShowUpdate) {
                return null
            }

            return AppUpdateDecision(
                updateUrl = metadata.updateUrl,
                version = metadata.versionName ?: metadata.versionCode.toString(),
                changeLog = metadata.changeLog.orEmpty(),
                isForce = isBelowMinimumVersion || isForcedByType || deadlineExpired,
                isMaintenanceMode = false
            )
        }

        @Suppress("DEPRECATION")
        private fun getInstalledVersionCode(
            context: Context
        ): Int {
            return try {
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.GET_META_DATA
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode.toInt()
                } else {
                    packageInfo.versionCode
                }
            } catch (error: Exception) {
                Log.e(
                    TAG, "Unable to get installed version code", error
                )
                0
            }
        }

        private fun isPastDeadline(deadline: String?): Boolean {
            if (deadline.isNullOrBlank()) {
                return false
            }

            return try {
                val formatter = SimpleDateFormat(
                    "yyyy-MM-dd", Locale.US
                ).apply {
                    isLenient = false
                }

                val deadlineDate = formatter.parse(deadline) ?: return false

                deadlineDate.before(Date())
            } catch (_: Exception) {
                false
            }
        }
    }
}