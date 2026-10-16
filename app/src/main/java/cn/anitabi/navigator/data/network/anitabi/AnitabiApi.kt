package cn.anitabi.navigator.data.network.anitabi

import cn.anitabi.navigator.core.model.Anime
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.PilgrimagePoint
import cn.anitabi.navigator.data.network.ApiHttpClient
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request

class AnitabiApi(private val httpClient: ApiHttpClient) {
    suspend fun getLite(subjectId: Long): AnitabiLiteDto = httpClient.execute(
        Request.Builder().url("$BASE_URL/bangumi/$subjectId/lite").get().build(),
        AnitabiLiteDto.serializer(),
    )

    suspend fun getPointDetails(subjectId: Long): List<AnitabiPointDto> = httpClient.execute(
        Request.Builder().url("$BASE_URL/bangumi/$subjectId/points/detail").get().build(),
        AnitabiPointListSerializer,
    )

    companion object {
        private const val BASE_URL = "https://api.anitabi.cn"
    }
}

private object AnitabiPointListSerializer : kotlinx.serialization.KSerializer<List<AnitabiPointDto>> by
    kotlinx.serialization.builtins.ListSerializer(AnitabiPointDto.serializer())

@Serializable
data class AnitabiLiteDto(
    val id: Long,
    @Serializable(with = LenientNullableStringSerializer::class)
    val cn: String? = null,
    @Serializable(with = LenientNullableStringSerializer::class)
    val title: String? = null,
    @Serializable(with = LenientNullableStringSerializer::class)
    val city: String? = null,
    @Serializable(with = LenientNullableStringSerializer::class)
    val cover: String? = null,
    val geo: List<Double> = emptyList(),
    val zoom: Double? = null,
    val modified: Long? = null,
    val pointsLength: Int = 0,
) {
    fun toAnime(): Anime = Anime(
        subjectId = id,
        name = title?.takeIf(String::isNotBlank) ?: cn?.takeIf(String::isNotBlank) ?: "Bangumi $id",
        nameCn = cn?.takeIf(String::isNotBlank),
        imageUrl = cover.toAllowedAnitabiImageUrlOrNull(),
    )
}

@Serializable
data class AnitabiPointDto(
    val id: String,
    @Serializable(with = LenientNullableStringSerializer::class)
    val name: String? = null,
    @Serializable(with = LenientNullableStringSerializer::class)
    val cn: String? = null,
    @Serializable(with = LenientNullableStringSerializer::class)
    val image: String? = null,
    val geo: List<Double> = emptyList(),
    @Serializable(with = LenientNullableStringSerializer::class)
    val origin: String? = null,
    @SerialName("originURL")
    @Serializable(with = LenientNullableStringSerializer::class)
    val originUrl: String? = null,
) {
    fun toPilgrimagePointOrNull(): PilgrimagePoint? {
        val coordinate = runCatching { GeoPoint.fromAnitabiGeo(geo) }.getOrNull() ?: return null
        return PilgrimagePoint(
            id = id,
            name = cn?.takeIf(String::isNotBlank)
                ?: name?.takeIf(String::isNotBlank)
                ?: "未命名地点",
            coordinate = coordinate,
            imageUrl = image.toAllowedAnitabiImageUrlOrNull(),
            origin = origin?.takeIf(String::isNotBlank),
            originUrl = originUrl?.takeIf(String::isSafeWebUrl),
        )
    }
}

private fun String.isSafeWebUrl(): Boolean =
    startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

private fun String?.toAllowedAnitabiImageUrlOrNull(): String? =
    cn.anitabi.navigator.data.images.AnitabiImageReference.normalize(this)

@OptIn(ExperimentalSerializationApi::class)
private object LenientNullableStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = String.serializer().nullable.descriptor

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return decoder.decodeNullableSerializableValue(String.serializer())
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive ?: return null
        return primitive.takeIf(JsonPrimitive::isString)?.contentOrNull
    }

    override fun serialize(encoder: Encoder, value: String?) {
        val jsonEncoder = encoder as? JsonEncoder
        if (jsonEncoder == null) {
            encoder.encodeNullableSerializableValue(String.serializer(), value)
        } else {
            jsonEncoder.encodeJsonElement(value?.let(::JsonPrimitive) ?: JsonNull)
        }
    }
}
