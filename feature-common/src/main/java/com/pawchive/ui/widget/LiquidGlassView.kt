package com.pawchive.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver

/**
 * 液态玻璃底栏背景层（FEAT-UI-190）。
 *
 * 原理（RenderNode 方案，BUG-GLASS-HW-BITMAP 修复后）：
 * - 挂在内容视图的 PreDrawListener 上，内容每帧绘制前触发自身失效；
 * - 自身绘制时把内容视图录进 [RenderNode]（**延迟显示列表，不发生软件光栅化**，
 *   Coil 硬件位图仅被引用——首版用软件位图重绘内容树，遇到硬件位图即抛
 *   "Software rendering doesn't support hardware bitmaps" 崩溃）；
 * - 回放时经 [RenderEffect.createBlurEffect] 由 GPU 应用模糊，再叠半透明
 *   表面色罩与顶部高光线，呈现磨砂玻璃质感。
 *
 * 降级路径：Android 12 以下、调用方判定不模糊、画布非硬件加速或录制异常时，
 * 只绘制半透明罩（无模糊）。
 */
class LiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var contentView: View? = null
    private var blurEnabled = false
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null

    /** 模糊路径一旦异常即永久降级，避免每帧重抛（BUG-GLASS-HW-BITMAP）。 */
    private var blurBroken = false

    private var contentNode: RenderNode? = null
    private var blurEffect: RenderEffect? = null

    private val tintPaint = Paint().apply { style = Paint.Style.FILL }
    private val highlightPaint = Paint().apply { style = Paint.Style.FILL; color = 0x33FFFFFF }
    private val locSelf = IntArray(2)
    private val locContent = IntArray(2)

    /** 绑定内容视图；[blur] 为 false 时仅绘制半透明罩。 */
    fun attach(content: View, blur: Boolean, tintColor: Int) {
        contentView = content
        val api31 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        blurEnabled = blur && api31
        tintPaint.color = tintColor
        if (api31) {
            blurEffect = if (blurEnabled) {
                RenderEffect.createBlurEffect(BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.CLAMP)
            } else {
                null
            }
        }
        preDrawListener?.let { content.viewTreeObserver.removeOnPreDrawListener(it) }
        preDrawListener = ViewTreeObserver.OnPreDrawListener {
            if (visibility == VISIBLE && width > 0 && height > 0) {
                invalidate()
            }
            true
        }.also { content.viewTreeObserver.addOnPreDrawListener(it) }
        invalidate()
    }

    fun release() {
        preDrawListener?.let { contentView?.viewTreeObserver?.removeOnPreDrawListener(it) }
        preDrawListener = null
        contentView = null
        contentNode = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            canvas.isHardwareAccelerated && blurEnabled && !blurBroken
        ) {
            // 录制/回放失败时永久降级为纯色罩，不崩不重试（BUG-GLASS-HW-BITMAP）
            runCatching { drawBlurredContent(canvas) }.onFailure {
                blurBroken = true
                android.util.Log.w(TAG, "glass blur disabled after failure", it)
            }
        }
        // 半透明罩（磨砂感的主要来源）+ 顶部高光细线
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tintPaint)
        val hairline = resources.displayMetrics.density
        canvas.drawRect(0f, 0f, width.toFloat(), hairline, highlightPaint)
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun drawBlurredContent(canvas: Canvas) {
        val content = contentView ?: return
        val node = contentNode ?: RenderNode("liquid-glass-content").also { contentNode = it }
        node.setPosition(0, 0, width, height)
        node.setRenderEffect(blurEffect)

        val rc = node.beginRecording(width, height)
        try {
            // 内容坐标系平移到本视图覆盖的区域；beginRecording 尺寸即裁剪边界
            content.getLocationInWindow(locContent)
            getLocationInWindow(locSelf)
            rc.translate(
                -(locSelf[0] - locContent[0]).toFloat(),
                -(locSelf[1] - locContent[1]).toFloat()
            )
            content.draw(rc)
        } finally {
            node.endRecording()
        }
        canvas.drawRenderNode(node)
    }

    private companion object {
        private const val TAG = "LiquidGlassView"

        /** 磨砂模糊半径（像素）。 */
        private const val BLUR_RADIUS = 28f
    }
}
