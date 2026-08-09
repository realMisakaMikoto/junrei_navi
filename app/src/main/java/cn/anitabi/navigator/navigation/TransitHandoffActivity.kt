package cn.anitabi.navigator.navigation

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import cn.anitabi.navigator.MainActivity
import cn.anitabi.navigator.core.model.GeoPoint
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TravelMode

class TransitHandoffActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var retryButton: Button
    private val requestState by viewModels<TransitHandoffRequestViewModel>()
    private var renderedResultSequence: Long? = null
    private val resultListener: (TransitHandoffRequestResult) -> Unit = { result ->
        renderedResultSequence = result.sequence
        handleServiceResult(result, allowAutomaticMapLaunch = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildContent()
        if (requestState.status == TransitHandoffRequestStatus.Idle) {
            when (intent.getStringExtra(EXTRA_MODE) ?: MODE_OPEN) {
                MODE_CONFIRM_ARRIVAL -> requestArrivalConfirmation(confirmEarly = false)
                MODE_END -> showEndConfirmation()
                MODE_NEXT -> requestHandoff(advance = true)
                else -> requestHandoff(advance = false)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requestState.attach(resultListener)
        renderRequestStatus()
    }

    override fun onPause() {
        requestState.detach(resultListener)
        super.onPause()
    }

    private fun buildContent() {
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(247, 246, 242))
        }
        statusText = TextView(this).apply {
            text = "正在准备外部分段导航…"
            textSize = 18f
            setTextColor(Color.rgb(35, 34, 31))
            gravity = Gravity.CENTER
        }
        progressBar = ProgressBar(this)
        retryButton = Button(this).apply {
            text = "重试本段"
            visibility = View.GONE
        }
        val returnButton = Button(this).apply {
            text = "返回巡礼手帳"
            setOnClickListener {
                startActivity(
                    Intent(this@TransitHandoffActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
                finish()
            }
        }
        root.addView(
            statusText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (20 * density).toInt() },
        )
        root.addView(progressBar)
        root.addView(
            retryButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (20 * density).toInt() },
        )
        root.addView(
            returnButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (12 * density).toInt() },
        )
        setContentView(root)
    }

    private fun requestHandoff(advance: Boolean, explicitRetry: Boolean = false) {
        val request = requestKey(
            if (advance) NavigationService.ACTION_PREPARE_NEXT_HANDOFF
            else NavigationService.ACTION_PREPARE_HANDOFF,
        )
        if (!requestState.beginRequest(request, explicitRetry)) return
        showLoading(if (advance) "正在确认并保存下一段…" else "正在确认并保存当前段…")
        sendServiceRequest(request)
    }

    private fun requestArrivalConfirmation(confirmEarly: Boolean, explicitRetry: Boolean = false) {
        val request = requestKey(
            action = NavigationService.ACTION_CONFIRM_EXTERNAL_ARRIVAL,
            confirmEarly = confirmEarly,
        )
        if (!requestState.beginRequest(request, explicitRetry)) return
        showLoading("正在确认到达状态…")
        sendServiceRequest(request)
    }

    private fun showEarlyArrivalConfirmation() {
        progressBar.visibility = View.GONE
        AlertDialog.Builder(this)
            .setTitle("尚未检测到接近目标")
            .setMessage("定位尚未连续 15 秒处于目标 80 米内。仍要手动确认已经到达吗？")
            .setNegativeButton("取消") { _, _ -> finish() }
            .setPositiveButton("仍然确认") { _, _ -> requestArrivalConfirmation(confirmEarly = true) }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showEndConfirmation(explicitRetry: Boolean = false) {
        progressBar.visibility = View.GONE
        statusText.text = "结束后会停止定位、通知和悬浮窗，但保留点位顺序及已完成进度。"
        AlertDialog.Builder(this)
            .setTitle("结束本次巡礼？")
            .setMessage("此操作不会删除行程记录。")
            .setNegativeButton("继续巡礼") { _, _ -> finish() }
            .setPositiveButton("确认结束") { _, _ ->
                requestEnd(explicitRetry)
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun requestEnd(explicitRetry: Boolean) {
        val request = requestKey(NavigationService.ACTION_END_EXTERNAL)
        if (!requestState.beginRequest(request, explicitRetry)) return
        showLoading("正在保存并结束行程…")
        sendServiceRequest(request)
    }

    private fun sendServiceRequest(request: TransitHandoffRequestKey) {
        if (
            externalLocationForegroundActionRequiresFinePermission(request.action) &&
            !AndroidLocationProvider.hasFineLocationPermission(this)
        ) {
            completeServiceStartFailure(
                request,
                externalLocationPermissionMessage(
                    strategy = NavigationRuntime.state.value.plan?.executionStrategy,
                    resume = request.action == NavigationService.ACTION_RESUME_EXTERNAL,
                ),
            )
            return
        }
        val receiver = TransitHandoffResultReceiver(
            Handler(Looper.getMainLooper()),
            requestState,
            request,
        )
        val serviceIntent = Intent(this, NavigationService::class.java)
            .setAction(request.action)
            .putExtra(NavigationService.EXTRA_TOUR_ID, request.tourId)
            .putExtra(NavigationService.EXTRA_EXPECTED_LEG_INDEX, request.legIndex)
            .putExtra(NavigationService.EXTRA_CONFIRM_EARLY, request.confirmEarly)
            .putExtra(NavigationService.EXTRA_RESULT_RECEIVER, receiver)
        try {
            if (request.action == NavigationService.ACTION_END_EXTERNAL) {
                startService(serviceIntent)
            } else {
                ContextCompat.startForegroundService(this, serviceIntent)
            }
        } catch (_: RuntimeException) {
            completeServiceStartFailure(
                request,
                "无法启动外部分段导航控制服务，请返回应用确认权限后重试",
            )
        }
    }

    private fun completeServiceStartFailure(request: TransitHandoffRequestKey, message: String) {
        requestState.completeRequest(
            request = request,
            resultCode = NavigationService.RESULT_ERROR,
            response = TransitHandoffServiceResponse(
                message = message,
                expectedLegIndex = null,
                originLatitude = null,
                originLongitude = null,
                destinationLatitude = null,
                destinationLongitude = null,
                executionStrategy = null,
                travelMode = null,
                originName = null,
                destinationName = null,
            ),
        )
    }

    private fun renderRequestStatus() {
        when (val status = requestState.status) {
            TransitHandoffRequestStatus.Idle -> Unit
            is TransitHandoffRequestStatus.Loading -> showLoading(status.request.loadingMessage())
            is TransitHandoffRequestStatus.Result -> {
                if (renderedResultSequence != status.value.sequence) {
                    renderedResultSequence = status.value.sequence
                    handleServiceResult(status.value, allowAutomaticMapLaunch = false)
                }
            }
        }
    }

    private fun handleServiceResult(
        result: TransitHandoffRequestResult,
        allowAutomaticMapLaunch: Boolean,
    ) {
        when (result.resultCode) {
            NavigationService.RESULT_HANDOFF_READY -> handleHandoffReady(result, allowAutomaticMapLaunch)
            NavigationService.RESULT_ARRIVAL_CONFIRMED -> {
                Toast.makeText(this, "已确认到达，开始停留", Toast.LENGTH_SHORT).show()
                finish()
            }
            NavigationService.RESULT_EARLY_CONFIRMATION_REQUIRED -> showEarlyArrivalConfirmation()
            NavigationService.RESULT_ENDED -> finish()
            NavigationService.RESULT_COMPLETED -> {
                Toast.makeText(this, "巡礼行程已完成", Toast.LENGTH_SHORT).show()
                finish()
            }
            else -> showRetry(
                result.response.message ?: result.request.failureMessage(),
            ) {
                retryRequest(result.request)
            }
        }
    }

    private fun handleHandoffReady(
        result: TransitHandoffRequestResult,
        allowAutomaticMapLaunch: Boolean,
    ) {
        intent.putExtra(
            EXTRA_LEG_INDEX,
            result.response.expectedLegIndex ?: intent.getIntExtra(EXTRA_LEG_INDEX, 0),
        )
        intent.putExtra(EXTRA_MODE, MODE_OPEN)
        val handoff = result.response.readyHandoff()
        if (handoff == null) {
            showRetry("暂时无法验证本段提供方、坐标或出行方式，进度已保留，请重试。") {
                requestHandoff(advance = false, explicitRetry = true)
            }
            return
        }
        if (
            allowAutomaticMapLaunch &&
            !isFinishing &&
            !isDestroyed &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            launchReadyHandoff(handoff)
        } else {
            showReadyForUser(handoff)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showReadyForUser(handoff: ReadyExternalHandoff) {
        statusText.text = when (handoff.executionStrategy) {
            TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN ->
                "本段路线已准备完成。请点击下方按钮打开 Google 地图。"
            TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND ->
                "本段路线已准备完成。请点击下方按钮打开高德地图。"
            TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES -> return
        }
        progressBar.visibility = View.GONE
        retryButton.text = "打开本段"
        retryButton.visibility = View.VISIBLE
        retryButton.setOnClickListener { launchReadyHandoff(handoff) }
    }

    private fun launchReadyHandoff(handoff: ReadyExternalHandoff) {
        if (isFinishing || isDestroyed) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            showReadyForUser(handoff)
            return
        }
        val launched = when (handoff.executionStrategy) {
            TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN ->
                GoogleMapsTransitLauncher(this).launch(handoff.originWgs84, handoff.destinationWgs84)
            TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND ->
                runCatching {
                    AmapExternalNavigationLauncher(this).launch(
                        originWgs84 = handoff.originWgs84,
                        destinationWgs84 = handoff.destinationWgs84,
                        originName = handoff.originName,
                        destinationName = handoff.destinationName,
                        mode = handoff.travelMode,
                    )
                }.getOrDefault(false)
            TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES -> false
        }
        if (launched) {
            finish()
        } else {
            val message = when (handoff.executionStrategy) {
                TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN ->
                    "无法打开 Google 地图或浏览器。当前段和进度已保留，请重试。"
                TransitExecutionStrategy.EXTERNAL_AMAP_MAINLAND ->
                    "无法打开高德地图。不会改用其他地图或浏览器；当前段和进度已保留，请重试。"
                TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES ->
                    "当前分段提供方无效。当前段和进度已保留，请重试。"
            }
            showRetry(message) {
                launchReadyHandoff(handoff)
            }
        }
    }

    private fun retryRequest(request: TransitHandoffRequestKey) {
        when (request.action) {
            NavigationService.ACTION_PREPARE_NEXT_HANDOFF -> requestHandoff(
                advance = true,
                explicitRetry = true,
            )
            NavigationService.ACTION_PREPARE_HANDOFF -> requestHandoff(
                advance = false,
                explicitRetry = true,
            )
            NavigationService.ACTION_CONFIRM_EXTERNAL_ARRIVAL -> requestArrivalConfirmation(
                confirmEarly = request.confirmEarly,
                explicitRetry = true,
            )
            NavigationService.ACTION_END_EXTERNAL -> showEndConfirmation(explicitRetry = true)
        }
    }

    private fun requestKey(
        action: String,
        confirmEarly: Boolean = false,
    ): TransitHandoffRequestKey = TransitHandoffRequestKey(
        action = action,
        tourId = intent.getStringExtra(EXTRA_TOUR_ID),
        legIndex = intent.getIntExtra(EXTRA_LEG_INDEX, 0),
        confirmEarly = confirmEarly,
    )

    private fun TransitHandoffRequestKey.loadingMessage(): String = when (action) {
        NavigationService.ACTION_PREPARE_NEXT_HANDOFF -> "正在确认并保存下一段…"
        NavigationService.ACTION_PREPARE_HANDOFF -> "正在确认并保存当前段…"
        NavigationService.ACTION_CONFIRM_EXTERNAL_ARRIVAL -> "正在确认到达状态…"
        NavigationService.ACTION_END_EXTERNAL -> "正在保存并结束行程…"
        else -> "正在处理请求…"
    }

    private fun TransitHandoffRequestKey.failureMessage(): String = when (action) {
        NavigationService.ACTION_CONFIRM_EXTERNAL_ARRIVAL -> "暂时无法确认到达，请重试。"
        NavigationService.ACTION_END_EXTERNAL -> "暂时无法结束行程，请重试。"
        else -> "暂时无法准备本段，进度已保留，请重试。"
    }

    private fun showLoading(message: String) {
        statusText.text = message
        progressBar.visibility = View.VISIBLE
        retryButton.visibility = View.GONE
    }

    private fun showRetry(message: String, retry: (() -> Unit)? = null) {
        statusText.text = message
        progressBar.visibility = View.GONE
        retryButton.text = "重试本段"
        retryButton.visibility = View.VISIBLE
        retryButton.setOnClickListener {
            (retry ?: { requestHandoff(intent.getStringExtra(EXTRA_MODE) == MODE_NEXT, explicitRetry = true) }).invoke()
        }
    }

    companion object {
        const val MODE_OPEN = "open"
        const val MODE_NEXT = "next"
        const val MODE_CONFIRM_ARRIVAL = "confirm_arrival"
        const val MODE_END = "end"

        const val EXTRA_MODE = "handoff_mode"
        const val EXTRA_TOUR_ID = "handoff_tour_id"
        const val EXTRA_LEG_INDEX = "handoff_leg_index"
        const val EXTRA_ORIGIN_LATITUDE = "handoff_origin_latitude"
        const val EXTRA_ORIGIN_LONGITUDE = "handoff_origin_longitude"
        const val EXTRA_DESTINATION_LATITUDE = "handoff_destination_latitude"
        const val EXTRA_DESTINATION_LONGITUDE = "handoff_destination_longitude"
        const val EXTRA_EXECUTION_STRATEGY = "handoff_execution_strategy"
        const val EXTRA_TRAVEL_MODE = "handoff_travel_mode"
        const val EXTRA_ORIGIN_NAME = "handoff_origin_name"
        const val EXTRA_DESTINATION_NAME = "handoff_destination_name"

        fun createIntent(
            context: android.content.Context,
            mode: String,
            tourId: String,
            legIndex: Int,
        ): Intent = Intent(context, TransitHandoffActivity::class.java)
            .putExtra(EXTRA_MODE, mode)
            .putExtra(EXTRA_TOUR_ID, tourId)
            .putExtra(EXTRA_LEG_INDEX, legIndex)
    }
}

internal data class TransitHandoffRequestKey(
    val action: String,
    val tourId: String?,
    val legIndex: Int,
    val confirmEarly: Boolean,
)

internal data class TransitHandoffServiceResponse(
    val message: String?,
    val expectedLegIndex: Int?,
    val originLatitude: Double?,
    val originLongitude: Double?,
    val destinationLatitude: Double?,
    val destinationLongitude: Double?,
    val executionStrategy: String? = null,
    val travelMode: String? = null,
    val originName: String? = null,
    val destinationName: String? = null,
) {
    fun origin(): GeoPoint? = coordinate(originLatitude, originLongitude)

    fun destination(): GeoPoint? = coordinate(destinationLatitude, destinationLongitude)

    private fun coordinate(latitude: Double?, longitude: Double?): GeoPoint? {
        if (latitude == null || longitude == null) return null
        return runCatching { GeoPoint(latitude, longitude) }.getOrNull()
    }

    fun readyHandoff(): ReadyExternalHandoff? {
        val strategy = executionStrategy?.toEnumOrNull<TransitExecutionStrategy>() ?: return null
        val mode = travelMode?.toEnumOrNull<TravelMode>() ?: return null
        if (
            strategy == TransitExecutionStrategy.IN_APP_GOOGLE_ROUTES ||
            strategy == TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN && mode != TravelMode.TRANSIT
        ) {
            return null
        }
        return ReadyExternalHandoff(
            executionStrategy = strategy,
            travelMode = mode,
            originWgs84 = origin() ?: return null,
            destinationWgs84 = destination() ?: return null,
            originName = originName?.takeIf(String::isNotBlank) ?: return null,
            destinationName = destinationName?.takeIf(String::isNotBlank) ?: return null,
        )
    }
}

internal data class ReadyExternalHandoff(
    val executionStrategy: TransitExecutionStrategy,
    val travelMode: TravelMode,
    val originWgs84: GeoPoint,
    val destinationWgs84: GeoPoint,
    val originName: String,
    val destinationName: String,
)

private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
    enumValues<T>().firstOrNull { it.name == this }

internal data class TransitHandoffRequestResult(
    val sequence: Long,
    val request: TransitHandoffRequestKey,
    val resultCode: Int,
    val response: TransitHandoffServiceResponse,
)

internal sealed interface TransitHandoffRequestStatus {
    data object Idle : TransitHandoffRequestStatus

    data class Loading(val request: TransitHandoffRequestKey) : TransitHandoffRequestStatus

    data class Result(val value: TransitHandoffRequestResult) : TransitHandoffRequestStatus
}

internal class TransitHandoffRequestViewModel : ViewModel() {
    var status: TransitHandoffRequestStatus = TransitHandoffRequestStatus.Idle
        private set

    private var nextResultSequence = 1L
    private var listener: ((TransitHandoffRequestResult) -> Unit)? = null

    fun beginRequest(request: TransitHandoffRequestKey, explicitRetry: Boolean): Boolean {
        val current = status
        if (current is TransitHandoffRequestStatus.Loading) return false
        if (
            current is TransitHandoffRequestStatus.Result &&
            current.value.request == request &&
            !explicitRetry
        ) {
            return false
        }
        status = TransitHandoffRequestStatus.Loading(request)
        return true
    }

    fun completeRequest(
        request: TransitHandoffRequestKey,
        resultCode: Int,
        response: TransitHandoffServiceResponse,
    ): Boolean {
        val current = status as? TransitHandoffRequestStatus.Loading ?: return false
        if (current.request != request) return false
        val result = TransitHandoffRequestResult(
            sequence = nextResultSequence++,
            request = request,
            resultCode = resultCode,
            response = response,
        )
        status = TransitHandoffRequestStatus.Result(result)
        listener?.invoke(result)
        return true
    }

    fun attach(resultListener: (TransitHandoffRequestResult) -> Unit) {
        listener = resultListener
    }

    fun detach(resultListener: (TransitHandoffRequestResult) -> Unit) {
        if (listener === resultListener) listener = null
    }
}

private class TransitHandoffResultReceiver(
    handler: Handler,
    private val requestState: TransitHandoffRequestViewModel,
    private val request: TransitHandoffRequestKey,
) : ResultReceiver(handler) {
    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
        val data = resultData ?: Bundle.EMPTY
        requestState.completeRequest(
            request = request,
            resultCode = resultCode,
            response = TransitHandoffServiceResponse(
                message = data.getString(NavigationService.EXTRA_RESULT_MESSAGE),
                expectedLegIndex = data.optionalInt(NavigationService.EXTRA_EXPECTED_LEG_INDEX),
                originLatitude = data.optionalDouble(TransitHandoffActivity.EXTRA_ORIGIN_LATITUDE),
                originLongitude = data.optionalDouble(TransitHandoffActivity.EXTRA_ORIGIN_LONGITUDE),
                destinationLatitude = data.optionalDouble(TransitHandoffActivity.EXTRA_DESTINATION_LATITUDE),
                destinationLongitude = data.optionalDouble(TransitHandoffActivity.EXTRA_DESTINATION_LONGITUDE),
                executionStrategy = data.getString(TransitHandoffActivity.EXTRA_EXECUTION_STRATEGY),
                travelMode = data.getString(TransitHandoffActivity.EXTRA_TRAVEL_MODE),
                originName = data.getString(TransitHandoffActivity.EXTRA_ORIGIN_NAME),
                destinationName = data.getString(TransitHandoffActivity.EXTRA_DESTINATION_NAME),
            ),
        )
    }

    private fun Bundle.optionalInt(key: String): Int? = if (containsKey(key)) getInt(key) else null

    private fun Bundle.optionalDouble(key: String): Double? = if (containsKey(key)) getDouble(key) else null
}
