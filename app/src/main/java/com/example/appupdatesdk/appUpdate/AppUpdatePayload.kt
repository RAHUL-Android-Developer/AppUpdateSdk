package com.example.appupdatesdk.appUpdate

import com.google.firebase.firestore.PropertyName

data class AppUpdatePayload(

    @get:PropertyName("metadata")
    @set:PropertyName("metadata")
    var metadata: AppMetadata = AppMetadata(),

    @get:PropertyName("conditions")
    @set:PropertyName("conditions")
    var conditions: AppConditions = AppConditions()
) {
    constructor() : this(
        metadata = AppMetadata(),
        conditions = AppConditions()
    )
}

data class AppMetadata(

    @get:PropertyName("versionCode")
    @set:PropertyName("versionCode")
    var versionCode: Int = 0,

    @get:PropertyName("versionName")
    @set:PropertyName("versionName")
    var versionName: String? = null,

    @get:PropertyName("updateUrl")
    @set:PropertyName("updateUrl")
    var updateUrl: String = "",

    @get:PropertyName("updateType")
    @set:PropertyName("updateType")
    var updateType: String = "optional",

    @get:PropertyName("deadline")
    @set:PropertyName("deadline")
    var deadline: String? = null,

    @get:PropertyName("minSupportedVersionCode")
    @set:PropertyName("minSupportedVersionCode")
    var minSupportedVersionCode: Int = 0,

    @get:PropertyName("changeLog")
    @set:PropertyName("changeLog")
    var changeLog: String? = null,

    @get:PropertyName("apkSizeMB")
    @set:PropertyName("apkSizeMB")
    var apkSizeMB: Double? = null,

    @get:PropertyName("updatePriority")
    @set:PropertyName("updatePriority")
    var updatePriority: Int = 0,

    @get:PropertyName("releaseDate")
    @set:PropertyName("releaseDate")
    var releaseDate: String? = null,

    @get:PropertyName("message")
    @set:PropertyName("message")
    var message: String? = null,

    @get:PropertyName("platform")
    @set:PropertyName("platform")
    var platform: String? = null
) {
    constructor() : this(
        versionCode = 0,
        versionName = null,
        updateUrl = "",
        updateType = "optional",
        deadline = null,
        minSupportedVersionCode = 0,
        changeLog = null,
        apkSizeMB = null,
        updatePriority = 0,
        releaseDate = null,
        message = null,
        platform = null
    )
}

data class AppConditions(

    @get:PropertyName("isUpdateAvailable")
    @set:PropertyName("isUpdateAvailable")
    var isUpdateAvailable: Boolean = false,

    @get:PropertyName("isMaintenanceMode")
    @set:PropertyName("isMaintenanceMode")
    var isMaintenanceMode: Boolean = false,

    @get:PropertyName("maintenanceMessage")
    @set:PropertyName("maintenanceMessage")
    var maintenanceMessage: String? = null,

    @get:PropertyName("isTestVersion")
    @set:PropertyName("isTestVersion")
    var isTestVersion: Boolean = false
) {
    constructor() : this(
        isUpdateAvailable = false,
        isMaintenanceMode = false,
        maintenanceMessage = null,
        isTestVersion = false
    )
}