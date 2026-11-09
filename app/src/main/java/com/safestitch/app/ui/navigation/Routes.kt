package com.safestitch.app.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object ScanRoute

@Serializable
data object CheckpointsRoute

@Serializable
data object SettingsRoute

/** [code] is set when an unknown QR code was scanned; [checkpointId] > 0 edits an existing checkpoint. */
@Serializable
data class CheckpointFormRoute(val code: String? = null, val checkpointId: Long = -1L)

@Serializable
data class InspectionRoute(val inspectionId: Long)

/** [itemId] <= 0 means a general photo that is not tied to one checklist item. */
@Serializable
data class PhotoCaptureRoute(val inspectionId: Long, val itemId: Long = -1L)

@Serializable
data class ReportRoute(val inspectionId: Long)
