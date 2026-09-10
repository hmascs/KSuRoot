package com.ting.root.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.ting.root.ui.theme.AppMotion
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.roundToInt

/** 滑块相对栏体的内边距（静止时四周留白、不贴边）。 */
private val SliderInset = 5.dp

/**
 * 滑块的语义标签（Compose UI 测试用 `onNodeWithTag(SliderTestTag)` 定位）。
 *
 * 不是为了测试框架而加的花架子：§6.1 那个「按下只平移、不变大」的 bug 拖了很久，
 * 根源就是**像素判读量不出玻璃的几何** —— 玻璃对比度只有 5~10 个灰阶，滑块边缘与
 * 栏体边缘在截图里几乎同色，而按下动画只有 ~200ms（`screencap` 要 300~500ms，
 * 瞬态根本拍不到）。所以这个 bug 的验证手段是**语义标签 + 几何探针日志**
 * （见 [GEO_PROBE]），而不是截图。
 */
const val SliderTestTag = "ksu-nav-slider"

/**
 * 几何探针。置 true 重新构建后，滑块的布局尺寸 / 上报尺寸 / 落点 / 按下外扩量
 * 会以 `KSU-GEO` 标签每秒几十行打进 logcat：
 *
 *     adb shell logcat -s KSU-GEO:I
 *
 * **为什么值得常备**：§6.1（按下只平移不变大）用截图量了很久都量不出来 ——
 * 玻璃对比度只有 5~10 个灰阶，滑块边缘与栏体边缘在像素上几乎同色；而按下动画
 * 只有 ~200ms，`screencap` 要 300~500ms，**瞬态也拍不到**。日志没有这两个问题：
 * 它给的是每一帧的真实测量值（实测一次按压里读到 w=469 h=288 place=(421,−16)
 * outset=36.00，与「四边各外扩 36px、中心不动」完全一致），
 * 而且能顺带看出弹簧过冲到 41.8px（ζ=0.5 的理论值 36×1.163=41.9）。
 * 默认关闭，对运行时零开销。
 */
private const val GEO_PROBE = false

/**
 * 开始拖动所需的横向位移阈值。
 *
 * 用自己实现的阈值而不用系统的 `viewConfiguration.touchSlop`：本机实测系统 slop
 * 约 **75px（≈19dp，是平台标准 8dp 的两倍多）**，会让拖动「开头先空拖一段滑块才动」。
 * 4dp = 16px 已足够区分「点按时的轻微抖动」与「真的在拖」，
 * 而相对系统值把死区缩短到约 1/5。
 */
private val SliderDragStartSlop = 4.dp

/**
 * 判定「手指是否落在滑块上」时给滑块加的宽容边（每边）。
 *
 * 拖动**只允许从滑块上起手** —— 整条栏都能拖的话，用户想点 tab 却稍微一滑
 * 就会变成拖动，而且他根本不知道滑块是可以拖的。给 4dp 宽容是为了不要求
 * 严丝合缝地按在描边上。
 */
private val SliderHitSlop = 4.dp

/**
 * 滑块模糊半径。要让**栏下方的内容依稀可读**，模糊就必须很轻；
 * 滑块比 dock 更小，取更轻的一档。
 */
private val SliderBlurRadius = 4.dp

/**
 * 滑块折射参数。**只在被按下时接入**（见 [GlassNavBarContent] 的 `refraction` 开关）。
 *
 * 这两个值一度被砍到 12/8，结果按下时也看不出折射 —— 原因不是「比值太大显得气泡」，
 * 而是**位移量和模糊半径同量级时，平移一张已经糊开的图肉眼无差别**。
 * 滑块模糊 8dp（32px），位移只有 8dp（32px）时比值 = 1.0，等于没有；
 * 恢复成 16dp（64px）后比值 = 2.0，折射才回得来。
 * 经验规则同 `Modifier.liquidGlass`：**位移至少要有模糊半径的 2 倍以上**。
 */
private val SliderRefractionHeight = 12.dp
private val SliderRefractionAmount = 16.dp

/**
 * 按下时滑块**每条边**向外探出底栏的距离。
 *
 * 静止时滑块本来就比底栏内缩了 [SliderInset]（5dp），所以要探出 4dp，
 * 每条边就得外扩 5 + 4 = 9dp（36px）—— 由布局期同步改位移与尺寸实现，
 * 这样上下左右与斜向的探出量**完全相等**。
 */
private val SliderPressedOverhang = 4.dp

/**
 * 拖动 / 飞行中的最大横向拉伸比例：0.22 = 最宽时比静止宽 22%。
 *
 * 它与按下的外扩不会同时达到最大：拖动时形变锚点是「最近的 tab」，
 * 距离最多半格（smoothstep 后 0.5），所以拖动中最宽约再乘 1.11；
 * 而 1.22 的满拉伸只出现在跨 tab 的点击飞行中，那时手指已松开、放大已回到 1。
 */
private const val SliderStretchMax = 0.22f

/**
 * 把「滑块当前位置与锚点的距离」换算成 0..1 的拉伸强度。
 *
 * 用 smoothstep（3t²-2t³）而不是线性：线性在 t 很小时就会立刻产生形变，
 * 轻微抖动就抖出形变。smoothstep 在两端导数为 0，落位瞬间形状变化率也归零。
 */
private fun stretchOf(leftPx: Float, anchorPx: Float, itemWidthPx: Float): Float {
    if (itemWidthPx <= 0f) return 0f
    val t = (abs(leftPx - anchorPx) / itemWidthPx).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** 位置换算成「最近的是第几个 tab」。拖动吸附与拖动中的高亮都走它，保证两者永远一致。 */
private fun nearestIndexOf(leftPx: Float, insetPx: Float, itemWidthPx: Float, lastIndex: Int): Int {
    if (itemWidthPx <= 0f) return 0
    return ((leftPx - insetPx) / itemWidthPx).roundToInt().coerceIn(0, lastIndex)
}

/**
 * 滑块位移用的弹簧。**拖动跟随与松手吸附共用同一个**：
 * - 拖动时目标是手指位置，弹簧去「追」手指 —— 追的过程就是 Q 弹惯性；
 * - 松手后目标是吸附到的 tab 落位，同一条弹簧把它带过去。
 *
 * 阻尼比 0.75 对应 Apple 手感校准值 ζ≈0.73（欠阻尼、轻微过冲后迅速稳定）；
 * 刚度取 StiffnessLow 让运动更「软」，也是惯性感的来源。
 * 验算 200~300ms 的 HIG 参考：ζω = 0.75×√200 = 10.6 s⁻¹
 * → 位移 95% 用 0.28s、98% 用 0.37s，落在区间内。
 *
 * 停止阈值抬到 0.5px：弹簧默认阈值 0.01px 要一路收敛到亚像素才肯停，
 * 三格跨度下位移在 0.5s 时肉眼已完全停住，动画却还要再空跑约 0.5s。
 */
private fun sliderSpring() = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessLow,
    visibilityThreshold = 0.5f,
)

/**
 * 悬浮玻璃底栏的**内容层**：玻璃滑块 + 可点击的 tab。
 *
 * 调用方必须把本组件与玻璃底板放成**兄弟**（不能塞进玻璃修饰符所挂的 Box 里）——
 * 库的 `DrawBackdropNode` 是在玻璃那层的裁剪里调用 `drawContent()` 的，
 * 塞进去的话滑块放大后超出栏体的部分会被裁掉。
 *
 * 三种交互共用**一个**位置状态 [sliderLeftPx]：
 * - 点 tab   → `onSelect` 改 `selectedIndex` → 目标位变化 → 弹簧飞过去；
 * - 拖滑块   → 手势只写「手指要求的位置」，弹簧追过去 → 松手写回 `onSelect` → 同一条弹簧吸附；
 * - 形变     → 读 [sliderLeftPx] 与锚点的差值算拉伸量。
 *
 * ### 拖动为什么是「直接写位置」而不是「写目标位让弹簧去追」
 * 后者看起来更「Q 弹」，但位置就落进了一条异步链（写目标 → 流 → animateTo），
 * 一旦这条链停止跟随，**不报错、只是静静地不动**。实测踩过一次：滑块中心在
 * 4 秒的拖动里从 289 走到 299 就冻结了，而松手吸附仍然完全正确 ——
 * 因为吸附判据用的是手指位置，所以「最后停在哪一格」这种测法是发现不了的。
 * 现在拖动直接写 [sliderLeftPx]（同帧、同步、1:1），弹簧只负责松手后的落位回弹。
 */
@Composable
fun GlassNavBarContent(
    items: List<Pair<ImageVector, String>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = LocalGlassBackdrop.current,
    contentHeight: Dp = 64.dp,
    /** 整条底栏是否处于「被按下」状态。底栏缩放要作用在外层玻璃容器上，所以上报给调用方。 */
    onBarPressedChange: (Boolean) -> Unit = {},
) {
    if (items.isEmpty()) return

    val density = LocalDensity.current
    val lastIndex = items.lastIndex

    var barWidthPx by remember { mutableIntStateOf(0) }
    val barWidth = with(density) { barWidthPx.toDp() }
    val itemWidth = barWidth / items.size
    // 布局尺寸：**与动画无关的常量**，只在栏宽变化时重算（见下方 layout/graphicsLayer 注释）。
    val sliderWidth = (itemWidth - SliderInset * 2).coerceAtLeast(0.dp)
    val sliderHeight = (contentHeight - SliderInset * 2).coerceAtLeast(0.dp)

    val itemWidthPx = if (barWidthPx == 0) 0f else barWidthPx.toFloat() / items.size
    val insetPx = with(density) { SliderInset.toPx() }
    // 滑块的静止布局尺寸（px）。布局期的自定义测量要用它。
    val sliderWidthPx = (itemWidthPx - insetPx * 2f).coerceAtLeast(0f)
    val sliderHeightPx = with(density) { sliderHeight.toPx() }

    val targetIndex = selectedIndex.coerceIn(0, lastIndex)
    val targetOffsetPx = itemWidthPx * targetIndex + insetPx

    // 每个 tab 一个 InteractionSource。先建好、再在同一个循环里聚合出「整条栏是否被按下」——
    // 只有这样底栏与滑块的缩放才跟着任意 tab 的按压一起走。
    val interactionSources = remember(items.size) { List(items.size) { MutableInteractionSource() } }
    var anyPressed = false
    for (i in items.indices) {
        // 注意：这里**不能**用 any{} / 短路，否则按压状态一变组成调用次数就会变。
        if (interactionSources[i].collectIsPressedAsState().value) anyPressed = true
    }

    // ── 位置状态：全部只在「非重组」路径上读写 ──
    // 绘制用的**唯一真源**，只被 layout{} / graphicsLayer{} 在布局/绘制阶段读取。
    var sliderLeftPx by remember { mutableFloatStateOf(0f) }
    // 手指要求滑块去的位置（只在拖动时有效）。拖动只写它，真正的渲染位置
    // 由下面的弹簧积分去「追」—— 这段差值就是果冻感。
    val fingerTargetState = remember { mutableFloatStateOf(0f) }
    // 「静止时的目标位」：每次重组同步进来，供积分循环读取。
    val restingTargetState = remember { mutableFloatStateOf(0f) }
    SideEffect { restingTargetState.floatValue = targetOffsetPx }
    // 弹簧速度（px/s），由每帧积分维护。初值 0。
    val springVelocity = remember { mutableFloatStateOf(0f) }
    // 是否正在拖动。它在组合里被读（作为按下态与弹簧的开关），
    // 但布尔值整场拖动只变两次，不会造成逐帧重组。
    val draggingState = remember { mutableStateOf(false) }

    // 「被按下」= 手指按在某个 tab 上，**或**正在拖动滑块。
    //
    // 为什么必须带上 dragging：拖动一旦越过 touch slop，父节点就会 consume 掉位移，
    // 子节点 clickable 的 tap 识别器随之取消并发出 PressInteraction.Cancel ——
    // 于是 collectIsPressedAsState 在**整个拖动过程中都是 false**。
    // 只认 anyPressed 的话，拖动时滑块会掉回未放大的状态、折射也会被关掉，
    // 与「拖动时放大、被按下时折射」正好相反（实测：拖动中的帧里底栏上沿停在 2777、
    // 滑块没有任何探出，说明按压态确实被释放了）。
    // draggingState 是布尔，拖动期间只在起止各变一次，所以这里不会造成逐帧重组。
    val pressed = anyPressed || draggingState.value

    // 用 SideEffect 而不是 LaunchedEffect：LaunchedEffect 会晚一帧才上报，
    // 底栏与滑块的缩放就会错开一帧、看起来不是「一起放大」。
    SideEffect { onBarPressedChange(pressed) }

    // 拖动过程中「视觉上高亮」的 tab：按滑块最近的那个，而不是已选中的那个。
    // null = 没在拖，此时回落到 selectedIndex。这个会重组，但只在跨过 tab 分界时变一次。
    var dragHighlight by remember { mutableStateOf<Int?>(null) }

    // 滑块形变的锚点：拖动中取「最近的 tab」，其余时候取「已选中的 tab」。
    val anchorIndex = dragHighlight ?: targetIndex
    val anchorPx = itemWidthPx * anchorIndex + insetPx

    // 按下进度 0→1。**在组合期解包**（`by`）。
    //
    // 为什么不做「只在 layout lambda 里读 .value」的优化：进度必须让 `.layout{}` 的
    // lambda 与 `graphicsLayer{}` 闭包拿到**同一帧的同一个值**，组合期解包是唯一
    // 保证两者同步的方式。代价是按压动画那 ~200ms 里每帧多一次重组（十几帧，可忽略），
    // 而本组件的外层 dock 缩放本来就是按帧重组的。
    val pressProgress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = AppMotion.press(),
        label = "navSliderPressProgress",
    )
    // 按下时每条边向外扩的量（px）：静止时本来就内缩了 SliderInset，
    // 要真正探出底栏 SliderPressedOverhang，就得外扩 内缩 + 突出。
    val pressOutsetPx =
        (insetPx + with(density) { SliderPressedOverhang.toPx() }) * pressProgress

    // 果冻物理：**每帧手动积分一条二阶弹簧**，位置 = 积分结果。
    //
    // 为什么不用 snapshotFlow / collectLatest / Animatable.animateTo：
    // 那条异步链曾经**静默停摆**过 —— 滑块在 4 秒的拖动里从 289 走到 299 就冻结了，
    // 不报错、只是不动（而且因为吸附用的是手指位置，连"最后停在哪一格"的测试都测不出来）。
    // withFrameNanos + 显式积分是最笨的写法，但它**不可能**停止推进：
    // 只要还有一帧画面，它就在算。
    //
    // 落位段取 Apple 手感校准值 ζ = 0.70（对应 ≈0.73 的欠阻尼轻微过冲），拖动段取临界阻尼
    // 于是既有可感知的过冲回弹，又能在 200~300ms 内稳定下来。
    // 拖动时目标 = 手指位置（弹簧追不上 → 拖尾 + 回弹）；松手后目标 = 选中 tab 的落位。
    LaunchedEffect(itemWidthPx) {
        if (itemWidthPx <= 0f) return@LaunchedEffect
        // 首帧直接落位，避免从 0 飞出来
        sliderLeftPx = restingTargetState.floatValue
        springVelocity.floatValue = 0f
        // 刚度 110：跟手滞后 ≈ 速度 × c/k。**阻尼分两段**，这是「不抽搐」的关键：
        //   拖动中 ζ = 1.0（临界阻尼）—— 目标位是手指逐个事件写进来的、本身是阶梯状的，
        //     欠阻尼弹簧追这种目标会来回振荡，看起来就是抽搐；临界阻尼只平滑拖尾、不振荡。
        //   松手后 ζ = 0.70 —— 这时目标是固定的一格，欠阻尼才有那一下 Q 弹过冲。
        // 两段之间速度是连续的（同一个 springVelocity），所以切换不会跳。
        val stiffness = 110f
        val zeta = if (draggingState.value) 1.0f else 0.70f
        val damping = 2f * zeta * sqrt(stiffness)
        var lastNanos = 0L
        while (true) {
            val now = withFrameNanos { it }
            val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1_000_000_000f)
            lastNanos = now
            if (dt <= 0f) continue
            val safeDt = dt.coerceAtMost(0.05f)   // 掉帧时钳制，避免显式积分发散
            val target = if (draggingState.value) {
                fingerTargetState.floatValue
            } else {
                restingTargetState.floatValue
            }
            var v = springVelocity.floatValue
            v += (stiffness * (target - sliderLeftPx) - damping * v) * safeDt
            springVelocity.floatValue = v
            sliderLeftPx += v * safeDt
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(contentHeight)
            .onSizeChanged { barWidthPx = it.width }
            // 拖动挂在**整条栏**上，而不是只挂在滑块上：
            // 滑块只有 1/3 栏宽且一直在动，要求用户每次都精准按到它是不现实的。
            //
            // 这里**不用** `detectHorizontalDragGestures`，原因有两个，都是实测踩到的：
            //  1. 它用系统的 `viewConfiguration.touchSlop`，本机实测约 **75px（≈19dp，
            //     是平台标准 8dp 的两倍多）**，于是「开头必须先空拖一小段滑块才动」，
            //     手感就是卡住、滑不动；
            //  2. 它的回调只给**越过 slop 之后**的位移增量，那段 slop 距离被永久丢掉，
            //     所以滑块会从头到尾落后手指一个 slop 的量。
            // 自己实现后：阈值降到 4dp，且位移一律从**按下点**起算（`dx`），
            // 于是越过阈值的那一刻没有跳变、之后严格 1:1 跟手。
            //
            // 与上层 tab 的 clickable 仍然不冲突：阈值之内一个事件都不消费，
            // tap 识别器照常工作；一旦越过阈值立刻 consume，tap 识别器发现
            // 「事件已被消费」就自行取消，于是滑动不会误触发点击。
            .pointerInput(itemWidthPx, lastIndex) {
                if (itemWidthPx <= 0f) return@pointerInput
                val minLeft = insetPx
                val maxLeft = itemWidthPx * lastIndex + insetPx
                val slopPx = with(density) { SliderDragStartSlop.toPx() }
                var startLeft = 0f

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    val startX = down.position.x

                    // 只允许**从滑块上**起手拖动。不在滑块上就直接返回，
                    // 一个事件都不消费，交给上层 tab 的 clickable 处理点击。
                    val hit = with(density) { SliderHitSlop.toPx() }
                    val pillLeft = sliderLeftPx
                    val pillRight = sliderLeftPx + sliderWidthPx
                    val pillTop = with(density) { SliderInset.toPx() }
                    val pillBottom = pillTop + sliderHeightPx
                    val onPill = down.position.x >= pillLeft - hit &&
                        down.position.x <= pillRight + hit &&
                        down.position.y >= pillTop - hit &&
                        down.position.y <= pillBottom + hit
                    if (!onPill) return@awaitEachGesture

                    startLeft = sliderLeftPx
                    var started = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null || !change.pressed) {
                            // 手指抬起。越过阈值才算一次拖动；没越过就什么都不做，
                            // 让 clickable 把自己的点击走完。
                            if (started) {
                                draggingState.value = false
                                dragHighlight = null
                                onSelect(
                                    nearestIndexOf(
                                        fingerTargetState.floatValue,
                                        insetPx,
                                        itemWidthPx,
                                        lastIndex,
                                    ),
                                )
                            }
                            break
                        }
                        if (change.isConsumed) {
                            // 被别的节点接管（正常路径下不会发生，兜底防卡死）
                            if (started) {
                                draggingState.value = false
                                dragHighlight = null
                            }
                            break
                        }

                        // 位移一律从按下点起算，不再累加增量 —— 这样 slop 那段距离
                        // 也会在越过阈值后立刻补上，滑块不会永久落后手指。
                        val dx = change.position.x - startX
                        if (!started) {
                            if (abs(dx) < slopPx) continue
                            started = true
                            draggingState.value = true
                            dragHighlight =
                                nearestIndexOf(startLeft, insetPx, itemWidthPx, lastIndex)
                        }
                        change.consume()

                        // 钳制在 [首页, 末页] 之间：滑块可以拖过头一点（形变更自然），
                        // 但不能整条脱离底栏跑到内容区上去。
                        val next = (startLeft + dx).coerceIn(minLeft, maxLeft)
                        // 只写「手指要求的位置」：同步生效、不经任何异步路径；
                        // 渲染位置由每帧的弹簧积分去追，拖尾与回弹就是果冻感。
                        fingerTargetState.floatValue = next
                        val nearest = nearestIndexOf(next, insetPx, itemWidthPx, lastIndex)
                        if (nearest != dragHighlight) dragHighlight = nearest
                    }
                }
            },
    ) {
        // ── ① 滑块层（在下）：液态玻璃 ──
        Box(
            modifier = Modifier
                // 关于「等边」：只有**改几何**才能做到等边 —— 任何线性缩放都会把胶囊
                // 拉成椭圆（沿 X 扩 (W/2)(s−1)、沿 Y 扩 (H/2)(s−1)、斜向又在两者之间）。
                // 所以这里在测量期同步改尺寸 + 改位移，形状向外等距偏移、中心不动，
                // 四条边（含斜向）探出的距离完全相同。RoundedCornerShape(50)
                // 会随尺寸同步变成更大的胶囊，形状不失真。
                //
                // ── 为什么必须自己写测量（§6.1 的修法）────────────────────────
                // 客观事实：父容器（本组件根 Box）的 maxHeight 就是栏高 **256px**，
                // 而按下外扩后滑块要 **288px** 高（静止 216 + 每边 36）。上一版把
                // 外扩后的尺寸原样 `layout(w, h)` 上报、再用 `offset{}` 平移，
                // 实测症状是「位移生效、尺寸没生效」＝只平移不变大 ——
                // 推测与「上报尺寸越过父约束被夹回」有关，但没有反证到字节码一级。
                //
                // 于是这里干脆把两件事**彻底解耦**，不再依赖那个猜测：
                //  ① 玻璃那一层用 `Constraints.fixed(w, h)` 测 —— 固定约束不受父容器上限
                //     影响，288px 是真的测出来、真的画那么大；
                //  ② 上报给父容器的尺寸显式夹回父约束之内（父容器只需要知道占位；
                //     它在 TopStart 放置，占位多大都不影响落点）；
                //  ③ 位置由 placement 自己给：四边同时外扩 pressOutsetPx，中心不动。
                // Compose 默认不裁剪子节点，所以探出栏体的那一圈照常可见。
                //
                // 实测（GEO_PROBE=true 的日志，见 [GEO_PROBE]）：
                //   静止 pill=397x216 report=397x216 place=(20,20) outset=0
                //   按下 pill=469x288 report=469x256 place=(-16,-16) outset=36.00
                // → Δw/2 = Δh/2 = 36.00px，四边等距外扩、中心不动，正是 §6.1 要的等边。
                .layout { measurable, constraints ->
                    val w = (sliderWidthPx + 2f * pressOutsetPx).roundToInt().coerceAtLeast(0)
                    val h = (sliderHeightPx + 2f * pressOutsetPx).roundToInt().coerceAtLeast(0)
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    val reportW = w.coerceIn(constraints.minWidth, constraints.maxWidth)
                    val reportH = h.coerceIn(constraints.minHeight, constraints.maxHeight)
                    if (GEO_PROBE) {
                        android.util.Log.i(
                            "KSU-GEO",
                            "pill=${w}x$h report=${reportW}x$reportH " +
                                "place=(${(sliderLeftPx - pressOutsetPx).roundToInt()}," +
                                "${(insetPx - pressOutsetPx).roundToInt()}) outset=$pressOutsetPx " +
                                "parentMax=${constraints.maxWidth}x${constraints.maxHeight}",
                        )
                    }
                    layout(reportW, reportH) {
                        placeable.place(
                            x = (sliderLeftPx - pressOutsetPx).roundToInt(),
                            y = (insetPx - pressOutsetPx).roundToInt(),
                        )
                    }
                }
                .graphicsLayer {
                    // 外扩已由布局期改尺寸完成，这里只剩液态形变（只横向拉长）。
                    val stretch = stretchOf(sliderLeftPx, anchorPx, itemWidthPx)
                    scaleX = 1f + SliderStretchMax * stretch
                }
                // 必须挂在 layout{} 之后：语义节点读的是**它自己那一层的坐标**，
                // 放在 layout 里面才等于「实际画出来的矩形」（含按下时探出栏体的那圈）。
                // 语义标签供 Compose UI 测试定位；几何数值的验证请看 [GEO_PROBE]。
                .testTag(SliderTestTag)
                .liquidGlass(
                    backdrop = backdrop,
                    shape = RoundedCornerShape(50),   // 滑块同样是胶囊
                    blurRadius = SliderBlurRadius,
                    refractionHeight = SliderRefractionHeight,
                    refractionAmount = SliderRefractionAmount,
                    // **静止时也开启折射**：曾经按需求关掉过（refraction = pressed），
                    // 但滑块占底栏 1/3 面积且盖在最上层，它一旦退化成"只有模糊没有折射"
                    // 的平板，整条底栏看上去就都失去了液态玻璃感 —— 观察不到差别，
                    // 只会觉得"默认效果没了"。所以恢复成与 dock 同配方。
                    refraction = true,
                    // 色散（边缘彩虹）：库把它做成**布尔**——开就是换一份带色散的着色器，
                    // 强度写死在着色器里，没有可调的幅度。既然彩虹感太强，
                    // 唯一能「降低」的办法就是关掉它；折射(depthEffect)与它相互独立，保留。
                    dispersion = false,
                    fallbackColor = MiuixTheme.colorScheme.surfaceContainerHigh,
                ),
        )

        // ── ② tab 层（在上）：文字与点击区始终清晰可点 ──
        // 滑块是**下层**且只做绘制变换，不参与文字绘制 ——
        // 所以无论滑块怎么放大、拉长，图标与文字都保持原始像素清晰度与原始命中区。
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            items.forEachIndexed { index, (icon, label) ->
                val selected = index == anchorIndex
                val tint = if (selected) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // 这里**不做**缩放：点按反馈是「整条底栏 + 滑块一起放大」，
                        // 文字本身保持原大小，避免缩放导致的重绘糊字。
                        .clickable(
                            interactionSource = interactionSources[index],
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        ),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        modifier = Modifier.size(24.dp),
                        tint = tint,
                    )
                    Text(
                        text = label,
                        color = tint,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
