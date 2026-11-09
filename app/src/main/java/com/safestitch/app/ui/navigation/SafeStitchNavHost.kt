package com.safestitch.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.safestitch.app.AppContainer
import com.safestitch.app.ui.camera.PhotoCaptureScreen
import com.safestitch.app.ui.checkpoint.CheckpointFormScreen
import com.safestitch.app.ui.checkpoint.CheckpointsScreen
import com.safestitch.app.ui.home.HomeScreen
import com.safestitch.app.ui.inspection.InspectionScreen
import com.safestitch.app.ui.report.ReportScreen
import com.safestitch.app.ui.scan.ScanScreen
import com.safestitch.app.ui.settings.SettingsScreen

@Composable
fun SafeStitchNavHost(container: AppContainer, modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = HomeRoute, modifier = modifier) {
        composable<HomeRoute> {
            HomeScreen(
                container = container,
                onScan = { nav.navigate(ScanRoute) },
                onOpenInspection = { id, completed ->
                    nav.navigate(if (completed) ReportRoute(id) else InspectionRoute(id))
                },
                onCheckpoints = { nav.navigate(CheckpointsRoute) },
                onSettings = { nav.navigate(SettingsRoute) },
            )
        }
        composable<ScanRoute> {
            ScanScreen(
                container = container,
                onBack = { nav.popBackStack() },
                onInspectionReady = { id ->
                    nav.navigate(InspectionRoute(id)) { popUpTo<ScanRoute> { inclusive = true } }
                },
                onNewCheckpoint = { code ->
                    nav.navigate(CheckpointFormRoute(code = code)) { popUpTo<ScanRoute> { inclusive = true } }
                },
            )
        }
        composable<CheckpointFormRoute> { entry ->
            val route = entry.toRoute<CheckpointFormRoute>()
            CheckpointFormScreen(
                container = container,
                scannedCode = route.code,
                checkpointId = route.checkpointId.takeIf { it > 0 },
                onBack = { nav.popBackStack() },
                onSaved = { nav.popBackStack() },
                onInspectionReady = { id ->
                    nav.navigate(InspectionRoute(id)) { popUpTo<CheckpointFormRoute> { inclusive = true } }
                },
            )
        }
        composable<CheckpointsRoute> {
            CheckpointsScreen(
                container = container,
                onBack = { nav.popBackStack() },
                onInspectionReady = { id -> nav.navigate(InspectionRoute(id)) },
                onAddCheckpoint = { nav.navigate(CheckpointFormRoute()) },
                onEditCheckpoint = { id -> nav.navigate(CheckpointFormRoute(checkpointId = id)) },
            )
        }
        composable<InspectionRoute> { entry ->
            val route = entry.toRoute<InspectionRoute>()
            InspectionScreen(
                container = container,
                inspectionId = route.inspectionId,
                onBack = { nav.popBackStack() },
                onTakePhoto = { itemId -> nav.navigate(PhotoCaptureRoute(route.inspectionId, itemId ?: -1L)) },
                onCompleted = {
                    nav.navigate(ReportRoute(route.inspectionId)) { popUpTo<InspectionRoute> { inclusive = true } }
                },
                onDeleted = { nav.popBackStack() },
            )
        }
        composable<PhotoCaptureRoute> { entry ->
            val route = entry.toRoute<PhotoCaptureRoute>()
            PhotoCaptureScreen(
                container = container,
                inspectionId = route.inspectionId,
                itemId = route.itemId.takeIf { it > 0 },
                onDone = { nav.popBackStack() },
            )
        }
        composable<ReportRoute> { entry ->
            val route = entry.toRoute<ReportRoute>()
            ReportScreen(
                container = container,
                inspectionId = route.inspectionId,
                onBack = { if (!nav.popBackStack<HomeRoute>(inclusive = false)) nav.popBackStack() },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(container = container, onBack = { nav.popBackStack() })
        }
    }
}
