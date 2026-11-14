package com.anylyric.theme

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView

/**
 * 主题切换的过渡：**先拍一张旧界面，重建后再让它淡出**。
 *
 * 为什么必须这么做：换明暗只能重建 Activity（换 Context 才会让 `values-night`
 * 生效），而 `recreate()` 的默认表现是「白闪一下 → 新界面直接出现」，
 * 在车机上这一下非常刺眼。
 *
 * 做法只有三步，不需要 Activity 转场、不涉及 Window 动画：
 *  ① 切换前 `capture()` 把当前窗口画进一张位图；
 *  ② `recreate()`；
 *  ③ 新界面第一帧铺上这张位图（与旧界面像素完全一致，看着像没重建），
 *     再 320ms 淡出并轻微放大 —— 新界面在其底下同步淡入，读起来就是一次交叉淡化。
 *
 * 位图用完即回收；只在用户主动切换时产生一张，没有常驻开销。
 */
object ThemeSwitch {

    private const val DURATION_MS = 320L

    /** 淡出时旧界面放大到 1.03：给「换了一层」一点纵深，不做的话只是变亮。 */
    private const val EXIT_SCALE = 1.03f

    @Volatile
    private var pending: Bitmap? = null

    /** 切换前调用：拍下当前窗口。拍不到（宽高为 0、绘制失败）就退化成无动画，不报错。 */
    fun capture(activity: Activity) {
        val root = activity.window?.decorView ?: return
        if (root.width <= 0 || root.height <= 0) return
        val shot = runCatching {
            Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also {
                root.draw(Canvas(it))
            }
        }.getOrNull() ?: return
        pending?.recycle()
        pending = shot
    }

    /** 新界面成帧后调用。没有待播的快照就什么也不做。 */
    fun play(activity: Activity) {
        val shot = pending ?: return
        pending = null
        if (activity.isFinishing || activity.isDestroyed) {
            shot.recycle()
            return
        }
        val decor = activity.window?.decorView as? ViewGroup ?: run {
            shot.recycle()
            return
        }
        val overlay = ImageView(activity).apply {
            setImageBitmap(shot)
            // 位图与窗口同尺寸，等比拉伸会把 1px 的取整误差摊到整屏，肉眼看不出来。
            scaleType = ImageView.ScaleType.FIT_XY
            isClickable = false
            isFocusable = false
        }
        decor.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // 新内容从半透明淡入：两张图同时变化，中间不会出现「都半透明」的灰蒙感。
        val content: View? = activity.findViewById(android.R.id.content)
        content?.alpha = 0.55f

        overlay.animate()
            .alpha(0f)
            .scaleX(EXIT_SCALE)
            .scaleY(EXIT_SCALE)
            .setDuration(DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                decor.removeView(overlay)
                shot.recycle()
            }
            .start()

        content?.animate()
            ?.alpha(1f)
            ?.setDuration(DURATION_MS)
            ?.setInterpolator(DecelerateInterpolator())
            ?.start()
    }
}
