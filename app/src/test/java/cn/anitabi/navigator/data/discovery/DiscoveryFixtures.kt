package cn.anitabi.navigator.data.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal fun indexFixture(modified: Long = 100, count: Int = 3, pageSize: Int = 2): JsonElement = Json.parseToJsonElement(
    "[[" + (1..count).joinToString(",") { id ->
        """[$id,"Synthetic CN $id","Synthetic EN $id","Synthetic $id","Test City","#123456","/covers/test.jpg",0,"TV",0,0,1,["shared",10.123456789,20.987654321,0],0,[],0,0,0]"""
    } + "],$pageSize,$modified]",
)

internal fun pageFixture(ids: List<Int>, name: String = "Synthetic Point"): JsonElement = Json.parseToJsonElement(
    ids.joinToString(",", "[", "]") { id ->
        """[$id,[],[["shared","$name","Local name",0,0,0,"/points/test.jpg","group-id",2.5,72,"Note","Source","https://example.com/source","Group name",0]],100]"""
    },
)

internal class MemoryDiscoveryCache(var snapshot: DiscoverySnapshot? = null) : DiscoveryCache {
    override fun read(): DiscoverySnapshot? = snapshot
    override fun write(snapshot: DiscoverySnapshot) { this.snapshot = snapshot }
}
