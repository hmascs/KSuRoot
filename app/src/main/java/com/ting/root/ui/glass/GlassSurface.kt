package com.ting.root.ui.glass

import android.graphics.Bitmap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import kotlin.random.Random
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** backdrop 的采集源（底栏与滑块折射、模糊用）。 */
val LocalGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * 应用背景。
 *
 * 取值直接来自 **miuix 语义色**（`MiuixTheme.colorScheme.surface`）：
 * 浅色 #F7F7F7、深色 #000000 —— 这也正是 miuix `Scaffold` 的默认 `containerColor`。
 *
 * 为什么不再是「浅色纯白」：卡片用的是 miuix `Card` 默认色 `surfaceContainer`
 * （浅色纯白、深色 #242424）。若页面也铺纯白，**卡片与背景之间就是 0 个灰阶的差**，
 * 整屏退化成一张白纸；而玻璃的两条栏做的都是背景模糊 —— 模糊是低通滤波，
 * 「背后什么都没有」时它数学上必然不可见（实测反复印证：白底上看不出模糊）。
 * 页面灰、卡片白之后，底栏下方就有了可被糊掉的真实结构（卡片边、分隔线、正文），
 * 玻璃才终于有东西可糊。这一条是 §6.2「没有全局模糊」的根因之一。
 */
@Composable
fun AppBackground(modifier: Modifier = Modifier) {
    val background = MiuixTheme.colorScheme.surface
    Box(modifier = modifier.fillMaxSize().background(background))
}

/**
 * Apple `Liquid Glass` `.regular` 档的**社区等效模糊半径**。
 *
 * 依据：iOS 26 的 `.regular` 变体强制包含背景模糊，社区校准的等效值是 24dp。
 * 但本工程的要求不同：**栏下方的正文要依稀可读**（起码能辨认出字是什么），
 * 24dp / 16dp 这种量级会把字彻底糊掉，所以调到 5dp —— 只剩一层很薄的磨砂，
 * 既保留玻璃感，又不牺牲可读性。
 */
val GlassRegularBlurRadius = 5.dp

/**
 * 玻璃自己的**半透明填充色**。
 *
 * 为什么必须有它：折射是纯位移、模糊不改变平坦区域的平均色 —— 这两者对
 * 「背后是一片纯白」都无能为力（实测：位移从 18dp 加到 40dp 只改变 2~10 个灰阶；
 * 色散也因为采样邻域里没有亮度阶跃而完全不出彩边）。**只有玻璃自己的填充色
 * 才能让它在任何背景上都成立。**
 *
 * 为什么是冷灰而不是白：半透明白色压在浅色页面上得到的结果还是同一个浅色 —— 等于没加。
 * 要「看得见」就必须与背景**有一点点色相/明度差**，但不能差太多 ——
 * 太浓就不「干净透亮」了。3% 的冷灰只改变 6~8 个灰阶：有实体感、同时保持透亮。
 *
 * 想调浓淡直接改这里的 alpha（0 就是不填充）。
 */
val GlassSurfaceTint = Color(0xFF6E7D96).copy(alpha = 0.03f)

/**
 * 磨砂颗粒强度。0 = 关闭。
 *
 * 注意它是**乘**在贴图自带 alpha 上的（`drawRect(alpha = ...)` 与画笔 alpha 相乘），
 * 所以贴图里的 alpha 取的是「满强度」值（0~48/255），由本系数统一缩放。
 * 第一版把两边都取了小值，实测颗粒 σ 只有 0.48 个灰阶 —— 等于没加。
 *
 * 为什么需要它：**模糊是低通滤波，它抹掉多少高频，就看得见多少变化**。
 * 平坦背景的高频为零，模糊前后都是同一片平坦 —— 所以只靠 blur 的话，
 * 整条玻璃上唯一看得出被糊过的地方就是高频细节（文字、图标边缘）。
 * 实测印证：栏内最有锐度的东西是「被糊过的文字」，边缘锐度 1.019，
 * 正好等于 16dp 模糊把 Δ≈100 的文字阶跃摊开后的理论值（Δ/(2.5σ)≈1.25）。
 *
 * 磨砂玻璃的「毛」本质是**表面微粗糙**，与背后有没有东西无关。
 * 补一层极淡的随机颗粒后，平坦区域也有了自己的质感，整条玻璃在任何背景上
 * 都呈磨砂状。取值很小（约 ±4 个灰阶的高频起伏），只提供质感、不改变整体色调。
 */
const val GlassGrainAlpha = 0.12f

private const val GRAIN_TILE = 96

/**
 * 一次性生成一张可平铺的颗粒贴图，并包成 [ShaderBrush]。
 *
 * 颜色与 alpha 都是随机的：明暗颗粒成对出现，**平均色接近中性**，
 * 所以颗粒只带来「质感」，不会额外改变玻璃的整体明暗（这正是它区别于
 * 一层纯色填充的地方）。
 */
@Composable
private fun rememberGrainBrush(): ShaderBrush = remember {
    val random = Random(0x5EEDBEEF)
    val pixels = IntArray(GRAIN_TILE * GRAIN_TILE) {
        val alpha = random.nextInt(0, 49)                       // 满强度 0~48/255，由 grainAlpha 统一缩放
        val level = random.nextInt(60, 201)                     // 60~200 的中性灰
        (alpha shl 24) or (level shl 16) or (level shl 8) or level
    }
    val bitmap = Bitmap.createBitmap(pixels, GRAIN_TILE, GRAIN_TILE, Bitmap.Config.ARGB_8888)
    ShaderBrush(
        ImageShader(
            bitmap.asImageBitmap(),
            TileMode.Repeated,
            TileMode.Repeated,
        ),
    )
}

/**
 * 悬浮玻璃导航栏容器（dock）。
 *
 * 与内部滑块**共用同一套配方**（见 [liquidGlass]），只有模糊半径与折射带
 * 按元素大小缩放：dock 是 64dp 高的大面板，滑块只有 54dp 且是移动元素。
 *
 * ⚠️ 调用方注意：库的 `DrawBackdropNode.draw()` 顺序是
 * `onDrawBehind → drawBackdropLayer(玻璃，含形状裁剪) → onDrawSurface → drawContent()`，
 * **子内容是在玻璃那一层的裁剪里画的**。所以玻璃修饰符必须挂在一个
 * **不含子内容**的 Box 上，内容层要做它的**兄弟**；否则超出栏体的子元素会被裁掉。
 */
@Composable
fun Modifier.glassNavBar(
    backdrop: LayerBackdrop? = LocalGlassBackdrop.current,
    shape: Shape = RoundedCornerShape(28.dp),
    blurRadius: Dp = GlassRegularBlurRadius,
): Modifier = liquidGlass(
    backdrop = backdrop,
    shape = shape,
    blurRadius = blurRadius,
    fallbackColor = MiuixTheme.colorScheme.surfaceContainer,
)

/**
 * 通用液态玻璃修饰符。效果链**按书写顺序执行**
 * （库内部是 `RenderEffect.createChainEffect(prev, next)`）：
 *
 * `vibrancy()`（饱和度 ×1.5）→ `blur()`（毛玻璃）→ `lens()`（折射 + 色散）
 *
 * ### 关于「折射被模糊吃掉」这个说法（已纠正）
 * 曾经认为 blur 放在 lens 前面会把背景糊平、令折射失效。反编译着色器后确认**不成立**：
 * lens 编译成的 RuntimeShader 主函数核心是 `refractedCoord = coord + d * grad`
 * ——**纯位移**。位移不改变对比度，所以「背景是平的 ⇒ 折射看不见」与模糊无关。
 * `blur → lens` 恰恰是 Apple 的做法：先糊背景再掰弯它，
 * 而色散彩边依旧锐利（彩边是着色器把 RGB 三通道错位采样得到的，与输入糊不糊无关）。
 *
 * 玻璃的「可辨识度」由 [Highlight] 承担 —— 它按形状绘制，**不依赖背后内容**，
 * 因此在任何页面上都在（这是「dock 像块白板」的真正原因）。
 *
 * ### 折射强度怎么调（踩过坑，这条是实测结论）
 * 这套库**没有** `refractiveIndex` 这种折射率标量，能调的是：
 * - [refractionHeight]：折射边带宽度，只有距边缘这么宽的一条带内才发生位移；
 * - [refractionAmount]：边缘处最大位移像素数；
 * - `depthEffect`：位移方向里额外叠加 `normalize(centeredCoord)`（即「气泡感」本体）。
 *
 * 曾经按「避免夸张气泡」把 `amount` 砍到 `height` 的 0.64 倍，结果折射**直接看不见了**。
 * 原因不是 `amount/height` 这个比值，而是 **`refractionAmount` 与模糊半径的量级关系**：
 * blur 会把背景里的中频结构抹平，当位移量与模糊半径同量级时，把一张已经糊开的图
 * 平移几十像素，肉眼几乎看不出差别（灰度在该尺度上已近似线性，平移前后一致）。
 * 实测：dock `位移 72px / 模糊 64px = 1.13` → 看不出折射；
 * 恢复成 `位移 160px / 模糊 64px = 2.5` → 折射回来。
 * **经验规则：`refractionAmount` 至少要是模糊半径的 2 倍以上，2.5 倍更稳。**
 * 另外这个比值同时受「降低模糊度」帮助 —— 模糊变小，同样的位移就更显眼。
 *
 * 注意参数类型：`shape` / `highlight` 是 **lambda**，
 * 而 `blur` / `lens` 收的是**像素 Float**，不是 Dp —— 必须 `.toPx()`。
 *
 * @param refraction 关掉时整条 lens 不接入效果链，只剩 vibrancy + blur 的磨砂胶囊。
 */
@Composable
fun Modifier.liquidGlass(
    backdrop: LayerBackdrop?,
    shape: Shape,
    blurRadius: Dp = GlassRegularBlurRadius,
    refractionHeight: Dp = 28.dp,
    refractionAmount: Dp = 40.dp,
    refraction: Boolean = true,
    /**
     * 色散（边缘虹彩）。库只提供布尔开关：打开即换用带色散的着色器，
     * **强度写死在着色器里、没有幅度参数**。所以「降低彩虹感」的唯一手段是关掉它。
     * 折射（[refraction]）与色散是两个独立的开关，关掉色散不影响折射。
     */
    dispersion: Boolean = false,
    highlight: Highlight = Highlight.Ambient,
    /** 玻璃自己的半透明填充；见 [GlassSurfaceTint]。alpha=0 即不填充。 */
    surfaceTint: Color = GlassSurfaceTint,
    /** 磨砂颗粒强度；见 [GlassGrainAlpha]。0 = 关闭。 */
    grainAlpha: Float = GlassGrainAlpha,
    fallbackColor: Color = MiuixTheme.colorScheme.surfaceContainer,
): Modifier {
    if (backdrop == null) {
        return this.clip(shape).background(fallbackColor.copy(alpha = 0.96f))
    }
    // 颗粒笔刷要在**组合期**取好：onDrawSurface 是绘制期的 lambda，
    // 里面不能调用 @Composable。无条件调用以保证组合调用的次数稳定。
    val grainBrush = rememberGrainBrush()

    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        highlight = { highlight },
        // onDrawSurface 的执行时机是「玻璃层画完、drawContent 之前」，
        // 而且处在玻璃那层的形状裁剪之内 —— 填充既不会盖住上面的文字/图标，
        // 也不会溢出胶囊形状。drawRect 的默认尺寸就是 DrawScope 的尺寸。
        onDrawSurface = {
            if (surfaceTint.alpha > 0f) drawRect(color = surfaceTint)
            if (grainAlpha > 0f) drawRect(brush = grainBrush, alpha = grainAlpha)
        },
        effects = {
            // ① 饱和度增强。库内置的 vibrancy() 反编译后就是
            //    colorControls(brightness = 0, contrast = 1, saturation = 1.5)，
            //    对应社区给 Apple 玻璃校准的 1.4× 饱和度。必须最先：
            //    放在模糊后面的话，颜色已经被摊淡，再加饱和度只会得到灰。
            vibrancy()
            // ② 毛玻璃模糊（.regular 档）
            blur(blurRadius.toPx())
            // ③ 折射 + 色散：depthEffect(按距离掰弯背景) 与 chromaticAberration(三通道错位) 同时开。
            //    这是整套玻璃唯一的「形变」来源，必须放在最后 —— 它的 content 输入
            //    是上一级的输出，所以折射的是「已经糊好的」背景，彩边则依旧锐利。
            if (refraction) {
                lens(
                    refractionHeight = refractionHeight.toPx(),
                    refractionAmount = refractionAmount.toPx(),
                    depthEffect = true,
                    chromaticAberration = dispersion,
                )
            }
        },
    )
}
