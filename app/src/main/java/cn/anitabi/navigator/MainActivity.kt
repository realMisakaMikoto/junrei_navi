package cn.anitabi.navigator

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import cn.anitabi.navigator.ui.search.SearchViewModel
import cn.anitabi.navigator.ui.planner.PlannerViewModel
import cn.anitabi.navigator.navigation.NavigationViewModel
import cn.anitabi.navigator.ui.theme.AnitabiTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.anitabi.navigator.ui.discovery.DiscoveryViewModel
import cn.anitabi.navigator.ui.AppShell

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
    private val discoveryViewModel by viewModels<DiscoveryViewModel> {
        viewModelFactory {
            initializer {
                DiscoveryViewModel(
                    container.discoveryRepository, container.discoveryPreferences,
                    container.locationProvider, container.territoryClassifier::classify,
                    createSavedStateHandle(),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var appearance by remember { mutableStateOf(container.appSettingsStore.appearance()) }
            var imagesEnabled by remember { mutableStateOf(container.appSettingsStore.imageMarkersEnabled()) }
            AnitabiTheme(appearance = appearance) {
                val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                androidx.compose.runtime.SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                var onboardingComplete by remember {
                    mutableStateOf(container.appSettingsStore.hasCompletedOnboarding())
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    if (onboardingComplete) {
                        AppShell(
                            container = container,
                            searchViewModel = searchViewModel,
                            discoveryViewModel = discoveryViewModel,
                            plannerViewModel = plannerViewModel,
                            navigationViewModel = navigationViewModel,
                            appearance = appearance,
                            onAppearanceChange = { appearance = it; container.appSettingsStore.setAppearance(it) },
                            imagesEnabled = imagesEnabled,
                            onImagesEnabledChange = { imagesEnabled = it; container.appSettingsStore.setImageMarkersEnabled(it) },
                            darkTheme = dark,
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
