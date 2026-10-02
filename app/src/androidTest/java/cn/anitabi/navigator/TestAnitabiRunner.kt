package cn.anitabi.navigator

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import cn.anitabi.navigator.core.model.TerritoryRegion
import cn.anitabi.navigator.core.region.FailClosedTerritoryClassifier
import cn.anitabi.navigator.data.discovery.DiscoveryCache
import cn.anitabi.navigator.data.discovery.DiscoveryParser
import cn.anitabi.navigator.data.discovery.DiscoverySnapshot
import cn.anitabi.navigator.data.discovery.DiscoverySource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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
        discoverySourceOverride = SyntheticDiscoveryFixture,
        discoveryCacheOverride = MemoryDiscoveryCache(SyntheticDiscoveryFixture.snapshot()),
    )
}

/** All instrumentation discovery data is synthetic, including lifecycle refreshes. */
internal object SyntheticDiscoveryFixture : DiscoverySource {
    const val SUBJECT_NAME = "Synthetic Subject Alpha"
    const val FIRST_POINT_NAME = "Synthetic Stop Alpha"
    const val SECOND_POINT_NAME = "Synthetic Stop Beta"

    private val indexDocument = Json.parseToJsonElement(
        """[[[901,"$SUBJECT_NAME","Synthetic English Alpha","$SUBJECT_NAME","Synthetic City","#426b62",null,0,"TV",0,0,2,["alpha",0.0,0.0,0,"beta",0.01,0.01,1],0,[],0,0,0]],1,100]""",
    )
    private val pageDocument = Json.parseToJsonElement(
        """[[901,[],[["alpha","$FIRST_POINT_NAME",null,0,0,0,null,null,1,10,"Synthetic detail alpha",null,null,null,0],["beta","$SECOND_POINT_NAME",null,0,0,0,null,null,2,20,"Synthetic detail beta",null,null,null,0]],100]]""",
    )

    override suspend fun index(cacheToken: String): JsonElement = indexDocument

    override suspend fun page(page: Int, cacheToken: String): JsonElement {
        require(page == 0)
        return pageDocument
    }

    override suspend fun subject(subjectId: Long): JsonElement {
        require(subjectId == 901L)
        return Json.parseToJsonElement(
            """[{"id":"alpha","name":"$FIRST_POINT_NAME","mark":"Synthetic detail alpha"},{"id":"beta","name":"$SECOND_POINT_NAME","mark":"Synthetic detail beta"}]""",
        )
    }

    fun snapshot(): DiscoverySnapshot = DiscoveryParser.merge(
        DiscoveryParser.index(indexDocument), DiscoveryParser.page(pageDocument),
    ).copy(loadedPages = setOf(0), endVersionVerified = true, checkedAtMillis = System.currentTimeMillis())
}

private class MemoryDiscoveryCache(private var snapshot: DiscoverySnapshot) : DiscoveryCache {
    @Synchronized
    override fun read(): DiscoverySnapshot = snapshot

    @Synchronized
    override fun write(snapshot: DiscoverySnapshot) { this.snapshot = snapshot }
}
