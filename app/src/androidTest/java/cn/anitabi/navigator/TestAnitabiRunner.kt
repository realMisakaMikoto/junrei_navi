package cn.anitabi.navigator

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import cn.anitabi.navigator.core.model.TerritoryRegion

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
    override fun createContainer(): AppContainer = AppContainer(
        context = this,
        classifyTerritoryOverride = { TerritoryRegion.OTHER },
        regionDataVersionOverride = { TEST_REGION_DATA_VERSION },
    )
}
