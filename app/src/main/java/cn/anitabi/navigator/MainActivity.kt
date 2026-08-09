package cn.anitabi.navigator

import android.os.Bundle
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cn.anitabi.navigator.ui.onboarding.OnboardingRoute
import cn.anitabi.navigator.ui.search.SearchRoute
import cn.anitabi.navigator.ui.search.SearchViewModel
import cn.anitabi.navigator.ui.planner.PlannerViewModel
import cn.anitabi.navigator.navigation.NavigationViewModel
import cn.anitabi.navigator.ui.theme.AnitabiTheme
import cn.anitabi.navigator.ui.theme.Paper

class MainActivity : ComponentActivity() {
    private val container by lazy { (application as AnitabiApplication).container }
    private val searchViewModel by viewModels<SearchViewModel> {
        SearchViewModel.Factory(
            container.bangumiApi,
            container.pilgrimageRepository,
            container.tourRepository,
        )
    }
    private val plannerViewModel by viewModels<PlannerViewModel> {
        PlannerViewModel.Factory(
            planner = container.tourPlanner,
            repository = container.tourRepository,
            locationProvider = container.locationProvider,
        )
    }
    private val navigationViewModel by viewModels<NavigationViewModel> {
        NavigationViewModel.Factory(application, container.tourRepository, container.tourPlanner)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                AndroidColor.TRANSPARENT,
                AndroidColor.argb(230, 247, 246, 242),
            ),
        )
        setContent {
            AnitabiTheme {
                var onboardingComplete by remember {
                    mutableStateOf(container.appSettingsStore.hasCompletedOnboarding())
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Paper),
                ) {
                    if (onboardingComplete) {
                        SearchRoute(
                            viewModel = searchViewModel,
                            plannerViewModel = plannerViewModel,
                            navigationViewModel = navigationViewModel,
                            telemetryConsentController = container.telemetryConsentController,
                            appSettingsStore = container.appSettingsStore,
                            amapPrivacyGate = container.amapPrivacyGate,
                            classifyTerritory = container.territoryClassifier::classify,
                        )
                    } else {
                        OnboardingRoute(
                            settingsStore = container.appSettingsStore,
                            onAmapPrivacyConsentChanged = { accepted ->
                                if (accepted) {
                                    container.amapPrivacyGate.prepareIfAllowed(true)
                                } else {
                                    container.amapPrivacyGate.revoke()
                                }
                            },
                            onComplete = { onboardingComplete = true },
                        )
                    }
                }
            }
        }
    }
}
