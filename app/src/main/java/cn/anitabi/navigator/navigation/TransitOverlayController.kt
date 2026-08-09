package cn.anitabi.navigator.navigation

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import cn.anitabi.navigator.MainActivity
import cn.anitabi.navigator.R
import cn.anitabi.navigator.core.model.NavigationProgress
import cn.anitabi.navigator.core.model.NavigationState
import cn.anitabi.navigator.core.model.TourPlan
import cn.anitabi.navigator.core.model.TransitExecutionStrategy
import cn.anitabi.navigator.core.model.TravelMode
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

internal class TransitOverlayController(
    private val context: Context,
) {
    private data class RenderData(
        val plan: TourPlan,
        val progress: NavigationProgress,
        val targetDistanceMeters: Double?,
    )

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var root: FrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var layoutState: TransitOverlayLayout? = null
    private var displayedForm: TransitOverlayForm? = null
    private var latestData: RenderData? = null
    private var titleView: TextView? = null
    private var summaryView: TextView? = null
    private var statusView: TextView? = null
    private var primaryButton: Button? = null
    private var earlyLeaveButton: Button? = null
    private var bubbleProgressView: TextView? = null
    private var legacyWindowInsets: Rect? = null
    private var layoutDensity = context.resources.displayMetrics.density
    private var layoutFontScale = context.resources.configuration.fontScale

    val isShowing: Boolean
        get() = root != null

    fun render(plan: TourPlan, progress: NavigationProgress, targetDistanceMeters: Double?) {
        if (
            !NavigationControlAvailability.overlayVisible(context) ||
            progress.isPaused ||
            progress.state in setOf(NavigationState.COMPLETED, NavigationState.ENDED)
        ) {
            remove()
            return
        }
        latestData = RenderData(plan, progress, targetDistanceMeters)
        val contentMetricsChanged = rescaleLayoutForDensityIfNeeded()
        if (root == null && !attach()) return
        if (contentMetricsChanged) layoutState?.form?.let(::rebuildContent)
        applyCurrentLayout()
        updateContent()
    }

    fun reflow() {
        val attachedRoot = root ?: return
        attachedRoot.post {
            if (root === attachedRoot) {
                val contentMetricsChanged = rescaleLayoutForDensityIfNeeded()
                if (contentMetricsChanged) layoutState?.form?.let(::rebuildContent)
                applyCurrentLayout()
                updateContent()
            }
        }
    }

    fun remove() {
        persistLayout()
        val attachedRoot = root ?: return
        clearAttachedState()
        runCatching { windowManager.removeViewImmediate(attachedRoot) }
    }

    private fun attach(): Boolean {
        val viewport = safeViewport()
        val sizing = sizing()
        val state = layoutState ?: restoreLayout(viewport, sizing).also { layoutState = it }
        val created = createRoot()
        val frame = transitOverlayFrame(state, viewport, sizing)
        val params = createLayoutParams(frame)
        root = created
        layoutParams = params
        rebuildContent(state.form)
        updateContent()
        return runCatching { windowManager.addView(created, params) }
            .fold(
                onSuccess = { true },
                onFailure = {
                    clearAttachedState()
                    false
                },
            )
    }

    private fun createRoot(): FrameLayout = FrameLayout(context).apply {
        elevation = dp(12).toFloat()
        clipToOutline = true
        setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                legacyWindowInsets = legacySafeInsets(insets)
            }
            post { reflow() }
            insets
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun createLayoutParams(frame: TransitOverlayFrame): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            frame.width,
            frame.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = frame.x
            y = frame.y
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun rebuildContent(form: TransitOverlayForm) {
        val container = root ?: return
        displayedForm = form
        titleView = null
        summaryView = null
        statusView = null
        primaryButton = null
        earlyLeaveButton = null
        bubbleProgressView = null
        container.removeAllViews()
        container.setOnClickListener(null)
        container.isClickable = false
        container.setOnTouchListener(null)
        when (form) {
            TransitOverlayForm.PANEL -> buildPanel(container)
            TransitOverlayForm.BUBBLE -> buildBubble(container)
        }
    }

    private fun buildPanel(container: FrameLayout) {
        container.background = roundedBackground(
            color = Color.argb(250, 255, 255, 255),
            radiusDp = 16,
            strokeColor = Color.rgb(210, 207, 199),
        )
        container.contentDescription = "外部分段导航悬浮控制面板"

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        panel.addView(
            header,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)),
        )

        val dragRegion = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            contentDescription = "拖动移动，轻触切换停靠位置"
            setOnClickListener { cyclePanelDock() }
        }
        header.addView(
            dragRegion,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
        )
        dragRegion.addView(
            OverlayDragGripView(context),
            LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.MATCH_PARENT),
        )
        titleView = textView(size = 15f, bold = true).also { title ->
            title.maxLines = 1
            title.ellipsize = TextUtils.TruncateAt.END
            title.gravity = Gravity.CENTER_VERTICAL
            dragRegion.addView(
                title,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
            )
        }
        installMoveGesture(dragRegion)

        header.addView(
            ImageButton(context).apply {
                setImageResource(R.drawable.ic_overlay_collapse)
                imageTintList = ColorStateList.valueOf(Color.rgb(55, 53, 49))
                backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                contentDescription = "收起为悬浮球"
                setOnClickListener { switchForm(TransitOverlayForm.BUBBLE) }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )

        val scroller = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        panel.addView(
            scroller,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        scroller.addView(
            body,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        summaryView = textView(size = 13f).also { summary ->
            summary.maxLines = 1
            summary.ellipsize = TextUtils.TruncateAt.END
            summary.gravity = Gravity.CENTER_VERTICAL
            summary.minimumHeight = dp(20)
            body.addView(
                summary,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        statusView = textView(size = 12f).also { status ->
            status.maxLines = 1
            status.ellipsize = TextUtils.TruncateAt.END
            status.gravity = Gravity.CENTER_VERTICAL
            status.minimumHeight = dp(20)
            body.addView(
                status,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val primaryActions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        panel.addView(
            primaryActions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(8)
                rightMargin = dp(8)
            },
        )
        primaryButton = actionButton("打开本段", primary = true) { performPrimaryAction() }.also { button ->
            primaryActions.addView(
                button,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        earlyLeaveButton = actionButton("提前离开", primary = true) {
            performEarlyLeaveAction()
        }.also { button ->
            button.visibility = View.GONE
            primaryActions.addView(
                button,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(8)
                },
            )
        }

        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        panel.addView(
            toolbar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(8)
            },
        )
        toolbar.addView(
            actionButton("暂停") { pauseAndReturnToApp() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        toolbar.addView(
            actionButton("应用") { returnToApp() }.apply {
                contentDescription = "返回巡礼手帖"
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        toolbar.addView(
            actionButton("结束", destructive = true) { performEndAction() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        toolbar.addView(
            OverlayResizeHandleView(context).apply {
                isClickable = true
                contentDescription = "拖动调整大小，轻触切换紧凑尺寸"
                setOnClickListener { toggleCompactPanelSize() }
                installResizeGesture(this)
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
    }

    private fun buildBubble(container: FrameLayout) {
        container.background = roundedBackground(
            color = Color.rgb(201, 62, 79),
            radiusDp = 30,
        )
        container.isClickable = true
        container.setOnClickListener { switchForm(TransitOverlayForm.PANEL) }
        installMoveGesture(container)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        container.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        content.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_navigation_notification)
                contentDescription = null
            },
            LinearLayout.LayoutParams(dp(25), dp(25)),
        )
        bubbleProgressView = textView(size = 10f, bold = true, color = Color.WHITE).also { progress ->
            progress.gravity = Gravity.CENTER
            progress.maxLines = 1
            progress.ellipsize = TextUtils.TruncateAt.END
            content.addView(
                progress,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateContent() {
        val data = latestData ?: return
        val state = layoutState ?: return
        if (displayedForm != state.form) rebuildContent(state.form)
        val legCount = data.plan.legs.size.coerceAtLeast(1)
        val legNumber = (data.progress.legIndex + 1).coerceAtMost(legCount)
        val leg = data.plan.legs.getOrNull(data.progress.legIndex)
        val targetName = leg?.destinationPointId?.let { id ->
            data.plan.selectedPoints.firstOrNull { it.id == id }?.name
        } ?: "返回起点"
        val distance = data.targetDistanceMeters?.let(::formatDistance) ?: "等待定位"
        val status = stateLabel(data.plan, data.progress)
        val controlLabel = externalControlLabel(data.plan)

        if (state.form == TransitOverlayForm.BUBBLE) {
            bubbleProgressView?.text = "$legNumber/$legCount"
            root?.contentDescription =
                "$controlLabel 第 $legNumber/$legCount 段，目标 $targetName，$status。轻触展开控制，拖动可移动"
            return
        }

        titleView?.text = "目标 · $targetName"
        summaryView?.text = "第 $legNumber/$legCount 段 · $distance"
        statusView?.text = status
        root?.contentDescription =
            "$controlLabel 控制面板。目标 $targetName，第 $legNumber/$legCount 段，直线距离 $distance，$status"
        primaryButton?.apply {
            visibility = View.VISIBLE
            isEnabled = true
            text = when (data.progress.state) {
                NavigationState.NAVIGATING -> "打开本段"
                NavigationState.ARRIVING -> "确认到达"
                NavigationState.NEXT_STOP -> "开始下一段"
                NavigationState.DWELLING -> "停留中"
                else -> "查看行程"
            }
            isEnabled = data.progress.state != NavigationState.DWELLING
            backgroundTintList = ColorStateList.valueOf(
                if (isEnabled) Color.rgb(201, 62, 79) else Color.rgb(205, 201, 194),
            )
        }
        earlyLeaveButton?.visibility =
            if (data.progress.state == NavigationState.DWELLING) View.VISIBLE else View.GONE
    }

    private fun actionButton(
        label: String,
        primary: Boolean = false,
        destructive: Boolean = false,
        onClick: () -> Unit,
    ): Button = Button(context).apply {
        text = label
        textSize = if (primary) 14f else 12f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(0, 0, 0, 0)
        setTextColor(
            when {
                primary -> Color.WHITE
                destructive -> Color.rgb(154, 32, 48)
                else -> Color.rgb(45, 43, 39)
            },
        )
        backgroundTintList = ColorStateList.valueOf(
            when {
                primary -> Color.rgb(201, 62, 79)
                destructive -> Color.rgb(255, 235, 237)
                else -> Color.rgb(243, 240, 234)
            },
        )
        setOnClickListener { onClick() }
    }

    private fun textView(
        size: Float,
        bold: Boolean = false,
        color: Int = Color.rgb(35, 34, 31),
    ): TextView = TextView(context).apply {
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun installMoveGesture(handle: View) {
        var downRawX = 0f
        var downRawY = 0f
        var startLayout: TransitOverlayLayout? = null
        var moved = false
        var gestureCancelled = false
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startLayout = layoutState
                    moved = false
                    gestureCancelled = false
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (moved) persistLayout()
                    startLayout = null
                    gestureCancelled = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val start = startLayout ?: return@setOnTouchListener true
                    val deltaX = event.rawX - downRawX
                    val deltaY = event.rawY - downRawY
                    if (!moved && hypot(deltaX.toDouble(), deltaY.toDouble()) >= touchSlop) moved = true
                    if (moved) {
                        layoutState = moveTransitOverlay(
                            start,
                            deltaX.roundToInt(),
                            deltaY.roundToInt(),
                            safeViewport(),
                            sizing(),
                        )
                        applyCurrentLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!gestureCancelled) {
                        if (moved) persistLayout() else view.performClick()
                    }
                    startLayout = null
                    gestureCancelled = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moved) persistLayout()
                    startLayout = null
                    gestureCancelled = false
                    true
                }
                else -> true
            }
        }
    }

    private fun installResizeGesture(handle: View) {
        var downRawX = 0f
        var downRawY = 0f
        var startLayout: TransitOverlayLayout? = null
        var resized = false
        var gestureCancelled = false
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startLayout = layoutState
                    resized = false
                    gestureCancelled = false
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (resized) persistLayout()
                    startLayout = null
                    gestureCancelled = true
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val start = startLayout ?: return@setOnTouchListener true
                    val deltaWidth = event.rawX - downRawX
                    val deltaHeight = event.rawY - downRawY
                    if (!resized && hypot(deltaWidth.toDouble(), deltaHeight.toDouble()) >= touchSlop) resized = true
                    if (resized) {
                        layoutState = resizeTransitOverlay(
                            start,
                            deltaWidth.roundToInt(),
                            deltaHeight.roundToInt(),
                            safeViewport(),
                            sizing(),
                        )
                        applyCurrentLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!gestureCancelled) {
                        if (resized) persistLayout() else view.performClick()
                    }
                    startLayout = null
                    gestureCancelled = false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (resized) persistLayout()
                    startLayout = null
                    gestureCancelled = false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> true
            }
        }
    }

    private fun switchForm(form: TransitOverlayForm) {
        val current = layoutState ?: return
        if (current.form == form) return
        val viewport = safeViewport()
        val sizing = sizing()
        layoutState = when (form) {
            TransitOverlayForm.PANEL -> expandTransitOverlay(current, viewport, sizing)
            TransitOverlayForm.BUBBLE -> collapseTransitOverlay(current, viewport, sizing)
        }
        persistLayout()
        rebuildContent(form)
        applyCurrentLayout()
        updateContent()
    }

    private fun toggleCompactPanelSize() {
        val current = layoutState ?: return
        if (current.form != TransitOverlayForm.PANEL) return
        val viewport = safeViewport()
        val sizing = sizing()
        val frame = transitOverlayFrame(current, viewport, sizing)
        val useDefault = frame.width <= sizing.minimumPanelWidth && frame.height <= sizing.minimumPanelHeight
        val targetWidth = if (useDefault) sizing.defaultPanelWidth else sizing.minimumPanelWidth
        val targetHeight = if (useDefault) sizing.defaultPanelHeight else sizing.minimumPanelHeight
        layoutState = resizeTransitOverlayFromNearestCorner(
            current,
            targetWidth,
            targetHeight,
            viewport,
            sizing,
        )
        persistLayout()
        applyCurrentLayout()
    }

    private fun cyclePanelDock() {
        val current = layoutState ?: return
        if (current.form != TransitOverlayForm.PANEL) return
        val viewport = safeViewport()
        val sizing = sizing()
        val frame = transitOverlayFrame(current, viewport, sizing)
        val onRight = frame.x + frame.width / 2 > viewport.left + viewport.width / 2
        val onBottom = frame.y + frame.height / 2 > viewport.top + viewport.height / 2
        val targetX: Int
        val targetY: Int
        when {
            onRight && !onBottom -> {
                targetX = viewport.right - frame.width
                targetY = viewport.bottom - frame.height
            }
            onRight -> {
                targetX = viewport.left
                targetY = viewport.bottom - frame.height
            }
            onBottom -> {
                targetX = viewport.left
                targetY = viewport.top
            }
            else -> {
                targetX = viewport.right - frame.width
                targetY = viewport.top
            }
        }
        layoutState = moveTransitOverlay(
            current,
            targetX - frame.x,
            targetY - frame.y,
            viewport,
            sizing,
        )
        persistLayout()
        applyCurrentLayout()
    }

    private fun performPrimaryAction() {
        val data = latestData ?: return
        val mode = when (data.progress.state) {
            NavigationState.NAVIGATING -> TransitHandoffActivity.MODE_OPEN
            NavigationState.ARRIVING -> TransitHandoffActivity.MODE_CONFIRM_ARRIVAL
            NavigationState.NEXT_STOP -> TransitHandoffActivity.MODE_NEXT
            else -> return
        }
        openHandoff(mode, data.plan.id, data.progress.legIndex)
    }

    private fun performEndAction() {
        val data = latestData ?: return
        openHandoff(TransitHandoffActivity.MODE_END, data.plan.id, data.progress.legIndex)
    }

    private fun performEarlyLeaveAction() {
        val data = latestData ?: return
        if (data.progress.state != NavigationState.DWELLING) return
        openHandoff(TransitHandoffActivity.MODE_NEXT, data.plan.id, data.progress.legIndex)
    }

    private fun pauseAndReturnToApp() {
        context.startService(
            Intent(context, NavigationService::class.java).setAction(NavigationService.ACTION_PAUSE_EXTERNAL),
        )
        returnToApp()
    }

    private fun returnToApp() {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    private fun openHandoff(mode: String, tourId: String, legIndex: Int) {
        context.startActivity(
            TransitHandoffActivity.createIntent(context, mode, tourId, legIndex)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun applyCurrentLayout(): Boolean {
        val attachedRoot = root ?: return false
        val params = layoutParams ?: return false
        val state = layoutState ?: return false
        val frame = transitOverlayFrame(state, safeViewport(), sizing())
        if (
            params.x == frame.x && params.y == frame.y &&
            params.width == frame.width && params.height == frame.height
        ) {
            return true
        }
        params.x = frame.x
        params.y = frame.y
        params.width = frame.width
        params.height = frame.height
        return runCatching { windowManager.updateViewLayout(attachedRoot, params) }
            .fold(
                onSuccess = { true },
                onFailure = {
                    persistLayout()
                    clearAttachedState()
                    runCatching { windowManager.removeViewImmediate(attachedRoot) }
                    false
                },
            )
    }

    private fun clearAttachedState() {
        root = null
        layoutParams = null
        displayedForm = null
        titleView = null
        summaryView = null
        statusView = null
        primaryButton = null
        earlyLeaveButton = null
        bubbleProgressView = null
    }

    private fun restoreLayout(
        viewport: TransitOverlayViewport,
        sizing: TransitOverlaySizing,
    ): TransitOverlayLayout {
        val defaults = defaultTransitOverlayLayout(viewport, sizing)
        if (!preferences.contains(KEY_PANEL_WIDTH_DP)) return defaults
        val form = if (safeBoolean(KEY_COLLAPSED, false)) {
            TransitOverlayForm.BUBBLE
        } else {
            TransitOverlayForm.PANEL
        }
        return restoreTransitOverlayLayout(
            form = form,
            position = TransitOverlayPosition(
                horizontalFraction = safeFloat(
                    KEY_HORIZONTAL_FRACTION,
                    defaults.position.horizontalFraction,
                ),
                verticalFraction = safeFloat(
                    KEY_VERTICAL_FRACTION,
                    defaults.position.verticalFraction,
                ),
            ),
            panelWidth = dp(safeInt(KEY_PANEL_WIDTH_DP, DEFAULT_PANEL_WIDTH_DP)),
            panelHeight = dp(safeInt(KEY_PANEL_HEIGHT_DP, DEFAULT_PANEL_HEIGHT_DP)),
            sizing = sizing,
        )
    }

    private fun persistLayout() {
        rescaleLayoutForDensityIfNeeded()
        val state = layoutState ?: return
        preferences.edit {
            putBoolean(KEY_COLLAPSED, state.form == TransitOverlayForm.BUBBLE)
            putFloat(KEY_HORIZONTAL_FRACTION, state.position.horizontalFraction)
            putFloat(KEY_VERTICAL_FRACTION, state.position.verticalFraction)
            putInt(KEY_PANEL_WIDTH_DP, pxToDp(state.panelWidth))
            putInt(KEY_PANEL_HEIGHT_DP, pxToDp(state.panelHeight))
        }
    }

    private fun safeBoolean(key: String, defaultValue: Boolean): Boolean =
        runCatching { preferences.getBoolean(key, defaultValue) }.getOrDefault(defaultValue)

    private fun safeFloat(key: String, defaultValue: Float): Float =
        runCatching { preferences.getFloat(key, defaultValue) }.getOrDefault(defaultValue)

    private fun safeInt(key: String, defaultValue: Int): Int =
        runCatching { preferences.getInt(key, defaultValue) }.getOrDefault(defaultValue)

    private fun safeViewport(): TransitOverlayViewport {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = Rect(metrics.bounds)
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            Rect(
                bounds.left + insets.left,
                bounds.top + insets.top,
                bounds.right - insets.right,
                bounds.bottom - insets.bottom,
            )
        } else {
            legacySafeBounds()
        }
        val margin = dp(8)
        val left = raw.left + margin
        val top = raw.top + margin
        val right = raw.right - margin
        val bottom = raw.bottom - margin
        return if (right > left && bottom > top) {
            TransitOverlayViewport(left, top, right, bottom)
        } else {
            TransitOverlayViewport(raw.left, raw.top, raw.right.coerceAtLeast(raw.left + 1), raw.bottom.coerceAtLeast(raw.top + 1))
        }
    }

    @Suppress("DEPRECATION")
    private fun legacySafeBounds(): Rect {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        legacyWindowInsets?.let { insets ->
            val left = insets.left.coerceIn(0, metrics.widthPixels - 1)
            val top = insets.top.coerceIn(0, metrics.heightPixels - 1)
            val right = (metrics.widthPixels - insets.right).coerceAtLeast(left + 1)
            val bottom = (metrics.heightPixels - insets.bottom).coerceAtLeast(top + 1)
            return Rect(left, top, right, bottom)
        }
        var right = metrics.widthPixels
        var bottom = metrics.heightPixels
        val top = systemDimension("status_bar_height")
        val navigationHeight = systemDimension("navigation_bar_height")
        val navigationWidth = systemDimension("navigation_bar_width")
        if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && navigationWidth > 0) {
            right = (right - navigationWidth).coerceAtLeast(1)
        } else {
            bottom = (bottom - navigationHeight).coerceAtLeast(top + 1)
        }
        return Rect(0, top, right, bottom)
    }

    @SuppressLint("DiscouragedApi")
    private fun systemDimension(name: String): Int {
        val identifier = context.resources.getIdentifier(name, "dimen", "android")
        return if (identifier == 0) 0 else context.resources.getDimensionPixelSize(identifier)
    }

    @Suppress("DEPRECATION")
    private fun legacySafeInsets(insets: WindowInsets): Rect {
        val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            displayCutoutSafeInsets(insets)
        } else {
            Rect()
        }
        return Rect(
            max(max(insets.systemWindowInsetLeft, insets.stableInsetLeft), cutout.left),
            max(max(insets.systemWindowInsetTop, insets.stableInsetTop), cutout.top),
            max(max(insets.systemWindowInsetRight, insets.stableInsetRight), cutout.right),
            max(max(insets.systemWindowInsetBottom, insets.stableInsetBottom), cutout.bottom),
        )
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun displayCutoutSafeInsets(insets: WindowInsets): Rect {
        val cutout = insets.displayCutout ?: return Rect()
        return Rect(
            cutout.safeInsetLeft,
            cutout.safeInsetTop,
            cutout.safeInsetRight,
            cutout.safeInsetBottom,
        )
    }

    private fun sizing(): TransitOverlaySizing = TransitOverlaySizing(
        defaultPanelWidth = dp(DEFAULT_PANEL_WIDTH_DP),
        defaultPanelHeight = dp(DEFAULT_PANEL_HEIGHT_DP),
        minimumPanelWidth = dp(MINIMUM_PANEL_WIDTH_DP),
        minimumPanelHeight = dp(MINIMUM_PANEL_HEIGHT_DP),
        bubbleSize = dp(BUBBLE_SIZE_DP),
        initialTopOffset = dp(INITIAL_TOP_OFFSET_DP),
    )

    private fun rescaleLayoutForDensityIfNeeded(): Boolean {
        val metrics = context.resources.displayMetrics
        val currentDensity = metrics.density
        val currentFontScale = context.resources.configuration.fontScale
        if (currentDensity == layoutDensity && currentFontScale == layoutFontScale) return false
        if (currentDensity != layoutDensity) {
            layoutState = layoutState?.let { state ->
                state.copy(
                    panelWidth = (state.panelWidth / layoutDensity * currentDensity).roundToInt(),
                    panelHeight = (state.panelHeight / layoutDensity * currentDensity).roundToInt(),
                )
            }
        }
        layoutDensity = currentDensity
        layoutFontScale = currentFontScale
        return true
    }

    private fun roundedBackground(
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1).coerceAtLeast(1), strokeColor)
    }

    private fun stateLabel(plan: TourPlan, progress: NavigationProgress): String = when {
        progress.isPaused -> "已暂停"
        progress.state == NavigationState.ARRIVING -> "已接近目标，请确认到达"
        progress.state == NavigationState.DWELLING -> "停留中"
        progress.state == NavigationState.NEXT_STOP -> "停留结束，等待手动开始下一段"
        plan.executionStrategy == TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN ->
            "路线、班次和换乘由 Google 地图提供"
        else -> "本段${plan.mode.overlayModeLabel()}由高德地图提供"
    }

    private fun externalControlLabel(plan: TourPlan): String =
        if (plan.executionStrategy == TransitExecutionStrategy.EXTERNAL_GOOGLE_MAPS_JAPAN) {
            "日本公交"
        } else {
            "高德${plan.mode.overlayModeLabel()}"
        }

    private fun TravelMode.overlayModeLabel(): String = when (this) {
        TravelMode.DRIVE -> "驾车导航"
        TravelMode.BIKE -> "骑行导航"
        TravelMode.WALK -> "步行导航"
        TravelMode.TRANSIT -> "公交路线"
    }

    private fun formatDistance(meters: Double): String =
        if (meters >= 1_000.0) "%.1f km".format(meters / 1_000.0) else "${meters.roundToInt()} m"

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun pxToDp(value: Int): Int = (value / context.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val PREFERENCES_NAME = "transit_overlay_layout"
        const val KEY_COLLAPSED = "collapsed"
        const val KEY_HORIZONTAL_FRACTION = "horizontal_fraction"
        const val KEY_VERTICAL_FRACTION = "vertical_fraction"
        const val KEY_PANEL_WIDTH_DP = "panel_width_dp"
        const val KEY_PANEL_HEIGHT_DP = "panel_height_dp"
        const val DEFAULT_PANEL_WIDTH_DP = 232
        const val DEFAULT_PANEL_HEIGHT_DP = 212
        const val MINIMUM_PANEL_WIDTH_DP = 216
        const val MINIMUM_PANEL_HEIGHT_DP = 188
        const val BUBBLE_SIZE_DP = 60
        const val INITIAL_TOP_OFFSET_DP = 72
    }
}

private class OverlayDragGripView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(116, 112, 104)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val radius = 1.5f * density
        val centerX = width / 2f
        val centerY = height / 2f
        listOf(-4f, 4f).forEach { xOffset ->
            listOf(-7f, 0f, 7f).forEach { yOffset ->
                canvas.drawCircle(centerX + xOffset * density, centerY + yOffset * density, radius, paint)
            }
        }
    }
}

private class OverlayResizeHandleView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(116, 112, 104)
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        paint.strokeWidth = 1.8f * density
        val right = width - 10f * density
        val bottom = height - 10f * density
        listOf(0f, 6f, 12f).forEach { inset ->
            canvas.drawLine(
                right - inset,
                bottom,
                right,
                bottom - inset,
                paint,
            )
        }
    }
}
