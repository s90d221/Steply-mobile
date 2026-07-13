package com.steply.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.steply.app.AppContainer
import com.steply.app.ui.screens.profile.AddEditProfileScreen
import com.steply.app.ui.screens.profile.AddEditProfileViewModel
import com.steply.app.ui.screens.profile.ProfileListScreen
import com.steply.app.ui.screens.profile.ProfileListViewModel
import com.steply.app.ui.screens.remote.RemoteCameraScreen
import com.steply.app.ui.screens.history.HistoryScreen
import com.steply.app.ui.screens.history.HistoryViewModel
import com.steply.app.ui.screens.remote.RemoteConnectScreen
import com.steply.app.ui.screens.remote.RemoteConnectViewModel
import kotlinx.coroutines.flow.first

@Composable
fun SteplyApp(
    appContainer: AppContainer,
    navController: NavHostController = rememberNavController(),
) {
    var startRoute by remember(appContainer) { mutableStateOf<String?>(null) }

    LaunchedEffect(appContainer) {
        val selectedUserId = appContainer.settingsRepository.selectedUserId.first()
        val activeAssessment = appContainer.assessmentSessionRepository.getActive()
        startRoute = when {
            selectedUserId == null -> Routes.ProfileList
            activeAssessment?.envelope?.session?.profileId == selectedUserId -> Routes.RemoteCamera
            else -> Routes.RemoteConnect
        }
    }

    val resolvedStartRoute = startRoute
    if (resolvedStartRoute == null) {
        SteplyLoadingScreen()
        return
    }

    NavHost(
        navController = navController,
        startDestination = resolvedStartRoute,
    ) {
        composable(Routes.ProfileList) {
            val viewModel: ProfileListViewModel = viewModel(
                factory = ProfileListViewModel.factory(appContainer),
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            ProfileListScreen(
                uiState = uiState,
                onSelectProfile = { profileId ->
                    viewModel.selectProfile(profileId) {
                        navController.navigate(Routes.RemoteConnect) {
                            popUpTo(Routes.ProfileList) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                onAddProfile = { navController.navigate(Routes.addProfile()) },
                onEditProfile = { profileId -> navController.navigate(Routes.editProfile(profileId)) },
                onArchiveProfile = viewModel::requestArchive,
                onConfirmArchive = viewModel::confirmArchive,
                onDismissArchive = viewModel::dismissArchiveDialog,
            )
        }

        composable(
            route = Routes.AddEditProfile,
            arguments = listOf(
                navArgument("profileId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val profileId = entry.arguments?.getString("profileId")
            val viewModel: AddEditProfileViewModel = viewModel(
                factory = AddEditProfileViewModel.factory(appContainer, profileId),
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            AddEditProfileScreen(
                uiState = uiState,
                onDisplayNameChanged = viewModel::onDisplayNameChanged,
                onBirthYearChanged = viewModel::onBirthYearChanged,
                onGenderChanged = viewModel::onGenderChanged,
                onHeightCmChanged = viewModel::onHeightCmChanged,
                onMovementNotesChanged = viewModel::onMovementNotesChanged,
                onSafetyNoteChanged = viewModel::onSafetyNoteChanged,
                onSaveProfile = viewModel::save,
                onCancel = { navController.popBackStack() },
                onSaved = {
                    navController.popBackStack()
                },
                onSavedHandled = viewModel::onSavedNavigationHandled,
            )
        }

        composable(Routes.RemoteConnect) {
            val viewModel: RemoteConnectViewModel = viewModel(
                factory = RemoteConnectViewModel.factory(appContainer),
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(uiState.openPersistedAssessment) {
                if (!uiState.openPersistedAssessment) return@LaunchedEffect
                navController.navigate(Routes.remoteCamera()) {
                    launchSingleTop = true
                }
                viewModel.onLinkedSessionNavigationHandled()
            }

            RemoteConnectScreen(
                uiState = uiState,
                onQrScanned = viewModel::connectFromQr,
                onManualQrChanged = viewModel::onManualQrChanged,
                onConnectManual = viewModel::connectManual,
                onChangeProfile = { navController.navigate(Routes.ProfileList) },
                onAddProfile = { navController.navigate(Routes.addProfile()) },
                onViewHistory = { navController.navigate(Routes.History) },
                onToggleMission = viewModel::toggleMission,
            )
        }


        composable(Routes.History) {
            val viewModel: HistoryViewModel = viewModel(
                factory = HistoryViewModel.factory(appContainer),
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            HistoryScreen(
                uiState = uiState,
                onBack = { navController.popBackStack() },
                onPrepareWeeklyReport = viewModel::prepareWeeklyReport,
            )
        }

        composable(Routes.RemoteCamera) {
            RemoteCameraScreen(
                appContainer = appContainer,
                onBack = { navController.popBackStack() },
                onChangeProfile = { navController.navigate(Routes.ProfileList) },
                onViewHistory = { navController.navigate(Routes.History) },
            )
        }
    }
}

@Composable
private fun SteplyLoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Getting Steply ready...",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
