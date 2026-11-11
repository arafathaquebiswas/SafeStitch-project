package com.safestitch.app.ui.navigation

import kotlinx.serialization.Serializable

// Bottom-bar destinations
@Serializable data object DashboardRoute
@Serializable data object InspectRoute
@Serializable data object ActionsRoute
@Serializable data object HistoryRoute
@Serializable data object CapturedInspectionsRoute
@Serializable data class CapturedInspectionDetailRoute(val inspectionId: Long)

@Serializable data object ScanRoute

/** [code] is set when an unknown QR code was scanned; [checkpointId] > 0 edits existing equipment. */
@Serializable data class CheckpointFormRoute(val code: String? = null, val checkpointId: Long = -1L)

@Serializable data class InspectionRoute(val inspectionId: Long)

/** [itemId] <= 0 means a general photo; [actionId] > 0 means an after-fix photo for that corrective action. */
@Serializable data class PhotoCaptureRoute(val inspectionId: Long, val itemId: Long = -1L, val actionId: Long = -1L)

@Serializable data class ReportRoute(val inspectionId: Long)
@Serializable data class PdfViewerRoute(val inspectionId: Long)
@Serializable data class ActionDetailRoute(val actionId: Long)
@Serializable data object SettingsRoute

// Admin
@Serializable data object AdminRoute
@Serializable data object UsersRoute

/** One level of the location hierarchy: 0 factories, 1 buildings, 2 floors, 3 areas. */
@Serializable data class LocationsRoute(val level: Int = 0, val parentId: Long = -1L, val title: String = "Factories")

@Serializable data object ChecklistEditorRoute
@Serializable data object SyncRoute
