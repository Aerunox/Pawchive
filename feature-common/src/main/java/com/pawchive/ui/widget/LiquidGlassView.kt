package com.pawchive.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver

/**
 * 液态玻璃底栏背景层（FEAT-UI-190）。
 *
 * 原理（BlurView 同款算法）：
 * - 挂在内容视图的 PreDrawListener 上，每次内容绘制前把本视图覆盖区域
 *   按 1/8 缩放绘制进复用位图（避免每帧分配）；
 * - 自身绘制时把位图放大铺满，经 [RenderEffect.createBlurEffect] 模糊
 *   （Android 12+），再叠一层半透明表面色罩与顶部高光线，呈现磨砂玻璃质感；
 * - Android 12 以下或调用方判定不做模糊时，退化为半透明纯色（仍有玻璃层次）。
 *
 * 性能约束：内容树在 1/8 缩放栅格下重绘，像素开销约为原 1/64；
 * 调用方应在「减少动画」开启时禁用模糊。
 */
class LiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var contentView: View? = null
    private var blurEnabled = false
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null

    private val downscale = 8f
    private var bitmap: Bitmap? = null
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint().apply { style = Paint.Style.FILL }
    private val highlightPaint = Paint().apply { style = Paint.Style.FILL; color = 0x33FFFFFF }
    private val bounds = RectF()

    /** 绑定内容视图并启用模糊；[blur] 为 false 时仅绘制半透明罩。 */
    fun attach(content: View, blur: Boolean, tintColor: Int) {
        contentView = content
        val api31 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        blurEnabled = blur && api31
        tintPaint.color = tintColor
        if (api31) {
            if (blurEnabled) {
                setRenderEffect(
                    RenderEffect.createBlurEffect(BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.CLAMP)
                )
            } else {
                setRenderEffect(null)
            }
        }
        preDrawListener?.let { content.viewTreeObserver.removeOnPreDrawListener(it) }
        preDrawListener = ViewTreeObserver.OnPreDrawListener {
            if (visibility == VISIBLE && width > 0 && height > 0) {
                prepareBitmap()
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
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap
        if (blurEnabled && bmp != null && !bmp.isRecycled) {
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(bmp, null, bounds, drawPaint)
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

    private fun prepareBitmap() {
        val content = contentView ?: return
        val w = (width / downscale).toInt().coerceAtLeast(1)
        val h = (height / downscale).toInt().coerceAtLeast(1)
        var bmp = bitmap
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap = bmp
        }
        val canvas = Canvas(bmp!!)
        canvas.save()
        canvas.scale(1 / downscale, 1 / downscale)
        // 把内容坐标系平移/裁剪到本视图覆盖的区域
        val self = IntArray(2); val host = IntArray(2)
        getLocationInWindow(self)
        content.getLocationInWindow(host)
        val dx = (self[0] - host[0]).toFloat()
        val dy = (self[1] - host[1]).toFloat()
        canvas.translate(-dx, -dy)
        canvas.clipRect(dx, dy, dx + width, dy + height)
        content.draw(canvas)
        canvas.restore()
    }

    private companion object {
        /** 磨砂模糊半径（像素）。 */
        private const val BLUR_RADIUS = 28f
    }
}
