package com.ting.root.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Dopamine 风格的动效令牌。
 *
 * 核心原则：**一律使用低刚度弹簧，禁止线性 tween**。
 * 低刚度（StiffnessLow）让动画有明显的「跟手 + 回弹」尾韵，
 * DampingRatioMediumBouncy 提供约一次可感知的过冲，这就是 Dopamine 那种「Q 弹」的来源。
 * 线性 tween 在同样时长下观感生硬，因此项目内不再用它做交互动画。
 *
 * 用泛型函数而不是共享 val：`SpringSpec<T>` 需要由调用处推断目标类型，
 * 写成 `val bouncy = spring<Float>(...)` 会导致 Dp / Color / Offset 动画无法复用同一令牌。
 */
object AppMotion {
    /** 主交互：低刚度 + 中等回弹。按钮、卡片按压、页面切换都用它。 */
    fun <T> bouncy(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow,
    )

    /** 大幅位移 / 需要更柔和的收尾时使用（光斑漂移、底栏浮入）。 */
    fun <T> gentle(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessVeryLow,
    )

    /** 高频小位移：按压缩放、选中指示器位移。刚度略高，避免延迟感。 */
    fun <T> snappy(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /**
     * 点按反馈专用：介于 [snappy] 与 [bouncy] 之间。
     *
     * 为什么不能直接用 [bouncy]：StiffnessLow 是为「大位移 + 慢收尾」调的手感，
     * 用在按下/松手这种 0.14 量级的缩放上，按下那一下要等约 150ms 才看得出压下去，
     * 会觉得「点了没反应」。StiffnessMedium 让按压当帧就跟手，
     * 阻尼仍保持 MediumBouncy，所以松手依然有一次可感知的回弹。
     */
    fun <T> press(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** 列表错峰入场的基础步长：每项 +40ms。 */
    const val StaggerMillis = 40L

    /** 按压缩放的下限，越小「压得越深」。 */
    const val PressedScale = 0.96f
}

/**
 * 列表项错峰入场：第 [index] 项延迟 `index * 40ms` 后以弹簧入场。
 *
 * 用在 `LazyColumn` 的 item / items 内部（而不是外层容器），
 * 这样滚动时新进入视口的项也会各自播放一次，符合 Dopamine 的观感。
 */
@Composable
fun Modifier.staggeredEntry(
    index: Int,
    baseDelayMillis: Long = AppMotion.StaggerMillis,
    initialOffsetY: Dp = 26.dp,
    maxStaggerIndex: Int = 8,
): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(index) {
        if (progress.value == 0f) {
            // 上限 8 项：否则列表越长，靠后的项等待越久（第 30 项要等 1.2s）
            delay(index.coerceIn(0, maxStaggerIndex) * baseDelayMillis)
        }
        progress.animateTo(1f, animationSpec = AppMotion.bouncy<Float>())
    }
    return this.graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * initialOffsetY.toPx()
        // 轻微放大，让入场有「浮起」的层次感
        val s = 0.94f + 0.06f * p
        scaleX = s
        scaleY = s
    }
}
