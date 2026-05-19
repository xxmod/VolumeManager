package moe.chensi.volume

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityButtonController.AccessibilityButtonCallback
import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import moe.chensi.volume.compose.AppVolumeList
import moe.chensi.volume.compose.SystemVolumePanel
import moe.chensi.volume.compose.VolumeChangeObserver
import moe.chensi.volume.system.ActivityTaskManagerProxy
import moe.chensi.volume.ui.theme.VolumeManagerTheme
import org.joor.Reflect
import java.util.Objects

@SuppressLint("AccessibilityPolicy")
class Service : AccessibilityService() {
    companion object {
        const val ACTION_SHOW_VIEW = "moe.chensi.volume.ACTION_SHOW_VIEW"

        private const val TAG = "VolumeManager.Service"

        private const val ANIMATION_DURATION = 300L

        private const val AUTO_REPEAT_DELAY = 100L
        private const val AUTO_REPEAT_INITIAL_DELAY = 500L
    }

    private val windowManager: WindowManager by lazy {
        Objects.requireNonNull(
            getSystemService(
                WindowManager::class.java
            )!!
        )
    }
    private lateinit var manager: Manager

    // 面板展开动画的独立 Animator，避免与按钮动画的 currentAnimator 冲突
    private var panelAnimator: ValueAnimator? = null

    private val handler = object : Handler(Looper.getMainLooper()) {
        fun hideView() {
            if (viewVisible) {
                Log.i(TAG, "animate out")
                if (isExpanded) {
                    // 面板展开状态下，淡出隐藏
                    panelAnimator?.cancel()
                    panelAnimator = null
                    animateAlpha(layoutParams.alpha, 0f, ANIMATION_DURATION) {
                        if (!viewVisible) {
                            Log.i(TAG, "remove view")
                            view!!.background = null
                            lifecycle?.currentState = Lifecycle.State.DESTROYED
                            windowManager.removeView(view)
                            view = null
                        }
                    }
                } else {
                    // 按钮状态下，从设定位置向右滑出到屏幕外
                    currentAnimator?.cancel()
                    val screenWidth = resources.displayMetrics.widthPixels
                    val startX = manager.buttonOffsetX.roundToInt()
                    layoutParams.x = startX
                    if (view != null) windowManager.updateViewLayout(view, layoutParams)
                    animateSlideX(startX, screenWidth, ANIMATION_DURATION) {
                        if (!viewVisible) {
                            Log.i(TAG, "remove view")
                            view!!.background = null
                            lifecycle?.currentState = Lifecycle.State.DESTROYED
                            windowManager.removeView(view)
                            view = null
                        }
                    }
                }
                viewVisible = false
            }
        }

        private val hideViewRunnable = Runnable(::hideView)

        fun startIdleTimer() {
            removeCallbacks(hideViewRunnable)
            postDelayed(hideViewRunnable, (manager.idleTimeout * 1000).toLong())
        }

        private var repeatAdjustVolumeDirection = 0
        private val repeatAdjustVolumeRunnable: Runnable = Runnable {
            adjustVolume()
            postDelayed(repeatAdjustVolumeRunnable, AUTO_REPEAT_DELAY)
        }

        private fun adjustVolume() {
            manager.audioManager.adjustSuggestedStreamVolume(
                repeatAdjustVolumeDirection, AudioManager.USE_DEFAULT_STREAM_TYPE, 0
            )
            VolumeChangeObserver.notifyVolumeChanged()
            startIdleTimer()
        }

        fun startRepeatAdjustVolume(direction: Int) {
            repeatAdjustVolumeDirection = direction
            if (view != null) {
                adjustVolume()
            }
            postDelayed(repeatAdjustVolumeRunnable, AUTO_REPEAT_INITIAL_DELAY)
        }

        fun stopRepeatAdjustVolume() {
            removeCallbacks(repeatAdjustVolumeRunnable)
            startIdleTimer()
        }
    }

    private var lifecycle: LifecycleRegistry? = null

    /**
     * 展开面板：先将窗口移到屏幕右边缘，切换到面板UI，再启动滑入动画
     * 在主线程上同步调用，确保位置设置在 Compose 重组之前生效
     */
    private fun expandPanel() {
        currentAnimator?.cancel()
        currentAnimator = null

        // 在 Compose 重组之前将窗口移到屏幕右边缘，防止面板闪现在按钮位置
        val screenWidth = resources.displayMetrics.widthPixels
        layoutParams.x = screenWidth
        layoutParams.y = 0
        layoutParams.alpha = 1f
        if (view != null) windowManager.updateViewLayout(view, layoutParams)

        // 切换到面板 UI
        isExpanded = true

        // 用 Handler.post 确保在 Compose 完成重组后再启动面板滑入动画
        handler.post {
            panelAnimator?.cancel()
            val animator = ValueAnimator.ofInt(screenWidth, 0)
            animator.duration = manager.animationDuration.toLong()
            animator.interpolator = android.view.animation.DecelerateInterpolator()
            animator.addUpdateListener { animation ->
                layoutParams.x = animation.animatedValue as Int
                if (view != null) windowManager.updateViewLayout(view, layoutParams)
            }
            animator.addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationEnd(animation: Animator) {
                    layoutParams.x = 0
                    if (view != null) windowManager.updateViewLayout(view, layoutParams)
                }
                override fun onAnimationCancel(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
            })
            animator.start()
            panelAnimator = animator
        }

        handler.startIdleTimer()
    }

    private fun createView(): View {
        return object : AbstractComposeView(this) {
            init {
                val owner = object : SavedStateRegistryOwner {
                    private val lifecycleRegistry = LifecycleRegistry(this)

                    private val savedStateRegistryController =
                        SavedStateRegistryController.create(this)

                    init {
                        savedStateRegistryController.performRestore(null)
                        lifecycleRegistry.currentState = Lifecycle.State.STARTED
                        this@Service.lifecycle = lifecycleRegistry
                    }

                    override val lifecycle: Lifecycle
                        get() = lifecycleRegistry

                    override val savedStateRegistry: SavedStateRegistry
                        get() = savedStateRegistryController.savedStateRegistry
                }

                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
            }

            override fun onAttachedToWindow() {
                super.onAttachedToWindow()

                Log.i(TAG, "onAttachedToWindow manufacturer: ${Build.MANUFACTURER}")

                this@Service.handler.startIdleTimer()
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                Log.i(TAG, "onTouchEvent ${event.actionMasked}")

                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    this@Service.handler.hideView()
                    return true
                }

                return super.onTouchEvent(event)
            }

            @Composable
            override fun Content() {
                // Parse color from settings or fallback to primary container
                val backgroundColor = try {
                    Color(android.graphics.Color.parseColor(manager.buttonColor))
                } catch (e: Exception) {
                    MaterialTheme.colorScheme.primaryContainer
                }

                return VolumeManagerTheme {
                    // LaunchedEffect 仅用于设置模糊背景，不做任何位置管理
                    androidx.compose.runtime.LaunchedEffect(isExpanded) {
                        if (!isExpanded) {
                            background = null
                        } else {
                            @Suppress("SpellCheckingInspection") if (windowManager.isCrossWindowBlurEnabled && isHardwareAccelerated && Build.MANUFACTURER != "realme") {
                                background = org.joor.Reflect.on(rootSurfaceControl).call("createBackgroundBlurDrawable").apply {
                                    call("setBlurRadius", 200)
                                    call("setCornerRadius", 40f)
                                }.get()
                            }
                        }
                    }

                    // 设置页面实时调整按钮位置（仅在按钮未展开且无动画时）
                    androidx.compose.runtime.LaunchedEffect(manager.buttonOffsetX, manager.buttonOffsetY) {
                        if (!isExpanded && viewVisible) {
                            this@Service.layoutParams.x = manager.buttonOffsetX.roundToInt()
                            this@Service.layoutParams.y = manager.buttonOffsetY.roundToInt()
                            if (view != null) windowManager.updateViewLayout(view, this@Service.layoutParams)
                        }
                    }

                    if (!isExpanded) {
                        // Show floating round button
                        Box(
                            modifier = Modifier
                                .size(manager.buttonSize.dp)
                                .background(
                                    color = backgroundColor,
                                    shape = RoundedCornerShape(manager.buttonCornerRadius.dp)
                                )
                                .clickable {
                                    try {
                                        @Suppress("DEPRECATION")
                                        sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to close system dialogs", e)
                                    }
                                    expandPanel()
                                },
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.VolumeUp,
                                contentDescription = "Volume",
                                tint = Color.White
                            )
                        }
                    } else {
                        // Show original volume panel
                        Surface(
                            color = Color(1f, 1f, 1f, 0.3f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            shape = RoundedCornerShape(40f)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp, 16.dp)
                            ) {
                                AppVolumeList(
                                    apps = manager.apps.values,
                                    showAll = false,
                                    onChange = this@Service.handler::startIdleTimer
                                ) {
                                    item("system_volume_panel") {
                                        SystemVolumePanel(
                                            audioManager = manager.audioManager,
                                            notificationManagerProxy = manager.notificationManagerProxy,
                                            showCallVolumeAlways = false,
                                            applyVisibilityFilter = true,
                                            allowVisibilityConfig = false,
                                            isSliderVisible = manager::isSystemSliderVisible,
                                            onSliderVisibilityChange = manager::setSystemSliderVisible,
                                            onChange = this@Service.handler::startIdleTimer
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private val layoutParams by lazy {
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, // Width
            WindowManager.LayoutParams.WRAP_CONTENT, // Height
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT // Make the background translucent
        ).apply {
            gravity = Gravity.CENTER // Center the view
        }
    }

    private var view: View? = null
    private var viewVisible = false
    private var isExpanded by mutableStateOf(false)

    private fun showView() {
        if (view == null) {
            Log.i(TAG, "add view")
            // The view doesn't respond to input events if reused
            view = createView()
            // 初始位置在屏幕右边缘外，alpha=1，准备滑入
            val screenWidth = resources.displayMetrics.widthPixels
            layoutParams.alpha = 1f
            layoutParams.x = screenWidth
            layoutParams.y = manager.buttonOffsetY.roundToInt()
            windowManager.addView(view, layoutParams)
        }
        
        isExpanded = false

        // 如果视图已可见（如从面板折叠回按钮），立即设置按钮位置
        if (viewVisible) {
            panelAnimator?.cancel()
            panelAnimator = null
            layoutParams.x = manager.buttonOffsetX.roundToInt()
            layoutParams.y = manager.buttonOffsetY.roundToInt()
            layoutParams.alpha = 1f
            if (view != null) windowManager.updateViewLayout(view, layoutParams)
        }

        if (!viewVisible) {
            Log.i(TAG, "animate in - slide from right")
            // 确保起始位置在屏幕右边缘外
            val screenWidth = resources.displayMetrics.widthPixels
            val targetX = manager.buttonOffsetX.roundToInt()
            layoutParams.alpha = 1f
            layoutParams.x = screenWidth
            layoutParams.y = manager.buttonOffsetY.roundToInt()
            if (view != null) windowManager.updateViewLayout(view, layoutParams)
            // 用 Handler.post 确保在首次 Compose 组合完成后再启动动画，
            // 避免 Compose 初始化过程中的位置竞争
            handler.post {
                animateSlideX(screenWidth, targetX, ANIMATION_DURATION)
            }
            viewVisible = true
        }

        handler.startIdleTimer()
    }

    private var currentAnimator: ValueAnimator? = null

    private fun animateAlpha(from: Float, to: Float, duration: Long, onEnd: (() -> Unit)? = null) {
        currentAnimator?.cancel()

        val animator = ValueAnimator.ofFloat(from, to)
        animator.duration = duration
        animator.interpolator = AccelerateDecelerateInterpolator()

        animator.addUpdateListener { animation ->
            if (view != null) {
                layoutParams.alpha = animation.animatedValue as Float
                windowManager.updateViewLayout(view, layoutParams)
            }
        }

        animator.addListener(object : Animator.AnimatorListener {
            var canceled = false

            override fun onAnimationStart(animation: Animator) {}

            override fun onAnimationEnd(animation: Animator) {
                if (canceled) {
                    return
                }

                layoutParams.alpha = to
                if (view != null) windowManager.updateViewLayout(view, layoutParams)

                onEnd?.invoke()
            }

            override fun onAnimationCancel(animation: Animator) {
                canceled = true
            }

            override fun onAnimationRepeat(animation: Animator) {}
        })

        animator.start()
        currentAnimator = animator
    }

    /**
     * 水平滑动动画：将窗口 x 坐标从 fromX 动画移动到 toX
     */
    private fun animateSlideX(fromX: Int, toX: Int, duration: Long, onEnd: (() -> Unit)? = null) {
        currentAnimator?.cancel()

        val animator = ValueAnimator.ofInt(fromX, toX)
        animator.duration = duration
        animator.interpolator = AccelerateDecelerateInterpolator()

        animator.addUpdateListener { animation ->
            if (view != null) {
                layoutParams.x = animation.animatedValue as Int
                windowManager.updateViewLayout(view, layoutParams)
            }
        }

        animator.addListener(object : Animator.AnimatorListener {
            var canceled = false

            override fun onAnimationStart(animation: Animator) {}

            override fun onAnimationEnd(animation: Animator) {
                if (canceled) {
                    return
                }

                layoutParams.x = toX
                if (view != null) windowManager.updateViewLayout(view, layoutParams)

                onEnd?.invoke()
            }

            override fun onAnimationCancel(animation: Animator) {
                canceled = true
            }

            override fun onAnimationRepeat(animation: Animator) {}
        })

        animator.start()
        currentAnimator = animator
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "onReceive ${intent.action}")
            if (intent.action == ACTION_SHOW_VIEW) {
                showView()
            }
        }
    }

    override fun onServiceConnected() {
        Log.i(TAG, "onServiceConnected")

        val application = super.getApplication() as MyApplication
        manager = application.manager

        accessibilityButtonController.registerAccessibilityButtonCallback(object :
            AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController?) {
                if (manager.shizukuStatus == Manager.ShizukuStatus.Connected) {
                    showView()
                }
            }
        })

        registerReceiver(broadcastReceiver, IntentFilter(ACTION_SHOW_VIEW), RECEIVER_NOT_EXPORTED)

        Log.i(TAG, "onServiceConnected done ${serviceInfo.capabilities.toString(2)}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
    }

    override fun onInterrupt() {
        Log.i(TAG, "onInterrupt")
    }

    override fun onDestroy() {
        super.onDestroy()

        Log.i(TAG, "onDestroy")

        Toast.makeText(this, "Accessibility service died!", Toast.LENGTH_SHORT).show()

        unregisterReceiver(broadcastReceiver)
    }

    val activityTaskManager by lazy { ActivityTaskManagerProxy(this) }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        Log.i(
            TAG,
            "onKeyEvent action = ${event.action}, key code = ${event.keyCode}, shizuku permission = ${manager.shizukuStatus}"
        )

        // Only handle `VOLUME_UP` and `VOLUME_DOWN`
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }

        // Ignore if Shizuku is not ready
        if (manager.shizukuStatus != Manager.ShizukuStatus.Connected) {
            return false
        }

        // Ignore if volume key interception is disabled
        if (!manager.interceptVolumeKeys) {
            return false
        }

        // Check foreground task ignorance list
        val task = activityTaskManager.getForegroundTask()
        Log.i(TAG, "onKeyEvent foreground task: $task")

        if (task != null) {
            val app = manager.apps[task.app]
            if (app != null && app.disableVolumeButtons) {
                return false
            }
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            showView()
        }

        return false
    }
}
