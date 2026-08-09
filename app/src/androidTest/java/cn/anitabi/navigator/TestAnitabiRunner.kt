package cn.anitabi.navigator

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.core.region.FailClosedTerritoryClassifier

internal const val TEST_REGION_DATA_VERSION = "TEST_ONLY_android_instrumentation_v1"

class TestAnitabiRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader,
        className: String,
        context: Context,
    ): Application = super.newApplication(
        classLoader,
        TestAnitabiApplication::class.java.name,
        context,
    )
}

class TestAnitabiApplication : AnitabiApplication() {
    private var productionRegionPolicyEnabled = false
    private val productionTerritoryClassifier by lazy {
        FailClosedTerritoryClassifier.load { assetPath -> assets.open(assetPath) }
    }

    fun useProductionRegionPolicy() {
        productionRegionPolicyEnabled = true
    }

    fun useSyntheticRegionPolicy() {
        productionRegionPolicyEnabled = false
    }

    override fun createContainer(): AppContainer = AppContainer(
        context = this,
        classifyTerritoryOverride = { point ->
            if (productionRegionPolicyEnabled) {
                productionTerritoryClassifier.classify(point)
            } else {
                TerritoryRegion.OTHER
            }
        },
        regionDataVersionOverride = {
            if (productionRegionPolicyEnabled) {
                productionTerritoryClassifier.metadata?.version
            } else {
                TEST_REGION_DATA_VERSION
            }
        },
    )
}
