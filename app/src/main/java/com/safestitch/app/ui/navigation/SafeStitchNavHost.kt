package com.safestitch.app.ui.navigation

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.safestitch.app.AppContainer
import com.safestitch.app.R
import com.safestitch.app.data.LocationLevel
import com.safestitch.app.data.db.UserEntity
import com.safestitch.app.ui.actions.ActionDetailScreen
import com.safestitch.app.ui.actions.ActionsScreen
import com.safestitch.app.ui.admin.AdminScreen
import com.safestitch.app.ui.admin.ChecklistEditorScreen
import com.safestitch.app.ui.admin.LocationsScreen
import com.safestitch.app.ui.admin.SqlDatabaseScreen
import com.safestitch.app.ui.admin.SyncScreen
import com.safestitch.app.ui.admin.UsersScreen
import com.safestitch.app.ui.camera.PhotoCaptureScreen
import com.safestitch.app.ui.chat.GeminiChatScreen
import com.safestitch.app.ui.checkpoint.CheckpointFormScreen
import com.safestitch.app.ui.dashboard.DashboardScreen
import com.safestitch.app.ui.history.CapturedInspectionsScreen
import com.safestitch.app.ui.history.HistoryScreen
import com.safestitch.app.ui.history.InspectionDetailsScreen
import com.safestitch.app.ui.inspect.FindEquipmentScreen
import com.safestitch.app.ui.inspection.InspectionScreen
import com.safestitch.app.ui.report.PdfViewerScreen
import com.safestitch.app.ui.report.ReportScreen
import com.safestitch.app.ui.scan.ScanScreen
import com.safestitch.app.ui.settings.SettingsScreen

private data class Tab(val route: Any, val label: String, @param:DrawableRes val icon: Int, val tag: String)

private val tabs = listOf(
    Tab(DashboardRoute, "Home", R.drawable.ic_home, "tab-home"),
    Tab(InspectRoute, "Inspect", R.drawable.ic_qr_scan, "tab-inspect"),
    Tab(ActionsRoute, "Actions", R.drawable.ic_assignment, "tab-actions"),
    Tab(HistoryRoute(), "History", R.drawable.ic_history, "tab-history"),
)

@Composable
fun SafeStitchNavHost(container: AppContainer, user: UserEntity, modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showBar = tabs.any { destination?.hasRoute(it.route::class) == true }

    fun openInspection(id: Long, completed: Boolean) = nav.navigate(if (completed) ReportRoute(id) else InspectionRoute(id))

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = destination?.hasRoute(tab.route::class) == true,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                            label = { Text(tab.label) },
                            modifier = Modifier.testTag(tab.tag),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController = nav, startDestination = DashboardRoute, modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            composable<DashboardRoute> {
                DashboardScreen(
                    container = container,
                    user = user,
                    onScan = { nav.navigate(ScanRoute) },
                    onFindEquipment = { nav.navigate(InspectRoute) { launchSingleTop = true } },
                    onOpenActions = { nav.navigate(ActionsRoute) { launchSingleTop = true } },
                    onOpenCapturedInspections = { nav.navigate(CapturedInspectionsRoute) },
                    onOpenChecklistInspection = ::openInspection,
                    onOpenCapturedInspectionDetail = { id -> nav.navigate(CapturedInspectionDetailRoute(id)) },
                    onNavigateToEquipmentHistory = { code ->
                        nav.navigate(HistoryRoute(equipmentCode = code))
                    },
                    onOpenChat = { nav.navigate(GeminiChatRoute) },
                    onAdmin = { nav.navigate(AdminRoute) },
                    onSettings = { nav.navigate(SettingsRoute) },
                )
            }
            composable<InspectRoute> {
                FindEquipmentScreen(
                    container = container,
                    user = user,
                    onScan = { nav.navigate(ScanRoute) },
                    onInspectionReady = { nav.navigate(InspectionRoute(it)) },
                    onAddEquipment = { nav.navigate(CheckpointFormRoute()) },
                    onEditEquipment = { nav.navigate(CheckpointFormRoute(checkpointId = it)) },
                )
            }
            composable<ActionsRoute> {
                ActionsScreen(container = container, onOpen = { nav.navigate(ActionDetailRoute(it)) })
            }
            composable<HistoryRoute> { entry ->
                val route = entry.toRoute<HistoryRoute>()
                HistoryScreen(
                    container = container,
                    onOpen = ::openInspection,
                    onOpenCapturedInspections = { nav.navigate(CapturedInspectionsRoute) },
                    onOpenCapturedInspectionDetail = { id -> nav.navigate(CapturedInspectionDetailRoute(id)) },
                    initialEquipmentCode = route.equipmentCode,
                )
            }
            composable<CapturedInspectionsRoute> {
                CapturedInspectionsScreen(
                    container = container,
                    onBack = { nav.popBackStack() },
                    onOpenInspection = { id -> nav.navigate(CapturedInspectionDetailRoute(id)) },
                )
            }
            composable<CapturedInspectionDetailRoute> { entry ->
                val route = entry.toRoute<CapturedInspectionDetailRoute>()
                InspectionDetailsScreen(
                    container = container,
                    inspectionId = route.inspectionId,
                    onBack = { nav.popBackStack() },
                )
            }
            composable<ScanRoute> {
                ScanScreen(
                    container = container,
                    user = user,
                    onBack = { nav.popBackStack() },
                    onInspectionReady = { id -> nav.navigate(InspectionRoute(id)) { popUpTo<ScanRoute> { inclusive = true } } },
                    onNewCheckpoint = { code -> nav.navigate(CheckpointFormRoute(code = code)) { popUpTo<ScanRoute> { inclusive = true } } },
                )
            }
            composable<CheckpointFormRoute> { entry ->
                val route = entry.toRoute<CheckpointFormRoute>()
                CheckpointFormScreen(
                    container = container,
                    user = user,
                    scannedCode = route.code,
                    checkpointId = route.checkpointId.takeIf { it > 0 },
                    onBack = { nav.popBackStack() },
                    onSaved = { nav.popBackStack() },
                    onInspectionReady = { id -> nav.navigate(InspectionRoute(id)) { popUpTo<CheckpointFormRoute> { inclusive = true } } },
                )
            }
            composable<InspectionRoute> { entry ->
                val route = entry.toRoute<InspectionRoute>()
                InspectionScreen(
                    container = container,
                    inspectionId = route.inspectionId,
                    onBack = { nav.popBackStack() },
                    onTakePhoto = { itemId -> nav.navigate(PhotoCaptureRoute(route.inspectionId, itemId ?: -1L)) },
                    onCompleted = { nav.navigate(ReportRoute(route.inspectionId)) { popUpTo<InspectionRoute> { inclusive = true } } },
                    onDeleted = { nav.popBackStack() },
                )
            }
            composable<PhotoCaptureRoute> { entry ->
                val route = entry.toRoute<PhotoCaptureRoute>()
                PhotoCaptureScreen(
                    container = container,
                    inspectionId = route.inspectionId,
                    itemId = route.itemId.takeIf { it > 0 },
                    actionId = route.actionId.takeIf { it > 0 },
                    onDone = { nav.popBackStack() },
                )
            }
            composable<ReportRoute> { entry ->
                val route = entry.toRoute<ReportRoute>()
                ReportScreen(
                    container = container,
                    inspectionId = route.inspectionId,
                    onBack = { nav.popBackStack() },
                    onViewPdf = { nav.navigate(PdfViewerRoute(route.inspectionId)) },
                    onOpenAction = { nav.navigate(ActionDetailRoute(it)) },
                )
            }
            composable<PdfViewerRoute> { entry ->
                PdfViewerScreen(container = container, inspectionId = entry.toRoute<PdfViewerRoute>().inspectionId, onBack = { nav.popBackStack() })
            }
            composable<ActionDetailRoute> { entry ->
                ActionDetailScreen(
                    container = container,
                    user = user,
                    actionId = entry.toRoute<ActionDetailRoute>().actionId,
                    onBack = { nav.popBackStack() },
                    onOpenInspection = { nav.navigate(ReportRoute(it)) },
                    onAddAfterFixPhoto = { inspectionId, actionId -> nav.navigate(PhotoCaptureRoute(inspectionId, actionId = actionId)) },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(container = container, user = user, onBack = { nav.popBackStack() })
            }
            composable<AdminRoute> {
                AdminScreen(
                    onBack = { nav.popBackStack() },
                    onUsers = { nav.navigate(UsersRoute) },
                    onLocations = { nav.navigate(LocationsRoute()) },
                    onChecklists = { nav.navigate(ChecklistEditorRoute) },
                    onSync = { nav.navigate(SyncRoute) },
                    onEquipment = { nav.navigate(InspectRoute) { launchSingleTop = true } },
                    onSqlDatabase = { nav.navigate(SqlDatabaseRoute) },
                )
            }
            composable<UsersRoute> { UsersScreen(container = container, admin = user, onBack = { nav.popBackStack() }) }
            composable<LocationsRoute> { entry ->
                val route = entry.toRoute<LocationsRoute>()
                val level = LocationLevel.entries[route.level.coerceIn(0, LocationLevel.entries.lastIndex)]
                LocationsScreen(
                    container = container,
                    level = level,
                    parentId = route.parentId,
                    title = route.title,
                    onBack = { nav.popBackStack() },
                    onOpenChild = { childLevel, nodeId, nodeName -> nav.navigate(LocationsRoute(childLevel.ordinal, nodeId, nodeName)) },
                )
            }
            composable<ChecklistEditorRoute> { ChecklistEditorScreen(container = container, onBack = { nav.popBackStack() }) }
            composable<SyncRoute> { SyncScreen(container = container, onBack = { nav.popBackStack() }) }
            composable<SqlDatabaseRoute> { SqlDatabaseScreen(container = container, onBack = { nav.popBackStack() }) }
            composable<GeminiChatRoute> { GeminiChatScreen(container = container, onBack = { nav.popBackStack() }) }
        }
    }
}
