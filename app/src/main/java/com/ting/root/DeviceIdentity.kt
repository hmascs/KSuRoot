package com.ting.root

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * 设备**身份串**的收集与「市场名」的解析。
 *
 * ## 要解决的问题
 *
 * 厂商把"这台机器叫什么"放在**哪个字段**是完全不统一的：
 * ```
 *   大多数机型        Build.MODEL = "Pixel 9 Pro"        ← 直接就是市场名
 *   vivo / iQOO       Build.MODEL = "V2463A"             ← 这是**型号代码**
 *                     Build.DEVICE = "PD2463"            ← 这是**开发代号**
 * ```
 * 本工程原来的载荷匹配只拿 `Build.MODEL` 当 haystack，于是 vivo 机型上
 * 拿 `V2463A` 去匹配目录里登记的 `"iqoo 13"` —— **必然匹配不上**，
 * 用户看到的就是"识别不出来"，而其实机器就在目录里。
 *
 * ## 做法（两件互不依赖的事）
 *
 * 1. [marketName]：按优先级去找"人能读懂的名字"。不同厂商放的位置不同，
 *    所以列一串候选，取第一个非空的。
 * 2. [identityText]：把**所有**身份串拼成一个 haystack 交给匹配器。
 *    这样无论名字落在哪个字段，只要目录里登记了，就能命中 ——
 *    这是纯粹的召回率提升，不需要新增任何数据。
 *
 * ## 诚实声明
 *
 * [marketName] 在部分机型上**确实取不到**（本机 vivo V2463A 就是：
 * `ro.product.marketname` / `ro.config.marketing_name` / `ro.vivo.product.model`
 * 全为空）。这不是 bug，是厂商没写。**我们不会用型号代码去猜市场名** ——
 * 猜错会让用户以为识别对了，比识别不出来更糟。
 */
object DeviceIdentity {

    /**
     * 市场名候选属性，**按可信度排序**。
     *
     * 顺序有讲究：`marketname` 系列是 Android 官方约定的"市场名"；
     * 各家 OEM 的自定义属性放在后面，避免它们的非标准语义盖过标准字段。
     */
    val MARKET_NAME_PROPS: List<String> = listOf(
        // Android 官方约定（多分区都可能有）
        "ro.product.marketname",
        "ro.product.system.marketname",
        "ro.product.vendor.marketname",
        "ro.product.odm.marketname",
        "ro.product.product.marketname",
        // 通用别名
        "ro.config.marketing_name",
        "ro.config.market_name",
        // 厂商自定义
        // [勘误] 这里原来有 `ro.vivo.product.model`，但**本机实测它返回的是代号**
        // （V2463A/PD2463 那台机器上 = "PD2463"），不是市场名。把它当名字用，
        // 界面就会把代号显示成设备名 —— 正是要避免的那件事。
        // 已移除；即便有厂商把市场名放这里，下面的 looksLikeCode 兜底也会把它拦下。
        "ro.vivo.market.name",
        "ro.oppo.market.name",       // OPPO / realme / 一加
        "ro.oplus.market.name",
        "ro.miui.product.name",      // 小米 / Redmi
        "ro.product.mod_device",
        "ro.config.device_name",
        // 华为 / 荣耀
        "ro.huawei.market.name",
        "ro.honor.market.name",
    )

    /** 形如 `V2463A` / `PD2463` / `SM-S918B` 的**型号代码**：短、无空格、字母数字混排。 */
    private val CODE_LIKE = Regex("^[A-Za-z]{1,4}[-_]?[A-Za-z0-9]{2,10}$")

    /**
     * 名字看起来像**代码**而不是市场名吗？
     *
     * 判据刻意保守：只有"整体像代码"才判 true。像 `iQOO Neo10 Pro+` 这种带空格/
     * 小写词的明显是市场名；`V2463A` 这种紧凑全大写才是代码。
     *
     * [为什么要这个] 型号代码本身**没有错**（那是系统给的真值），
     * 但它对用户没意义。界面据此决定是"直接显示"还是"标注这是代号"。
     */
    fun looksLikeCode(name: String?): Boolean {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return false
        if (n.contains(' ')) return false                 // 有空格 → 市场名
        if (n.any { it.isLowerCase() }) return false      // 有小写 → 多半是市场名（iqoo…）
        return CODE_LIKE.matches(n)
    }

    /**
     * 取市场名。取不到返回 null —— **不回落**到型号代码（调用方自己决定怎么显示）。
     *
     * @param prop 属性读取器，注入是为了可测。
     */
    fun marketName(prop: (String) -> String?): String? {
        for (key in MARKET_NAME_PROPS) {
            val v = prop(key)?.trim().orEmpty()
            if (v.isEmpty()) continue
            // [兜底] 值本身像型号代码（`PD2463` / `V2463A`）就不认它是市场名。
            // 与其把代号当名字显示（用户会以为识别对了），不如如实返回 null，
            // 让界面去标注"这是型号代码"。
            if (looksLikeCode(v)) continue
            return v
        }
        return null
    }

    /** 真实实现：系统属性 + `Settings.Global.device_name`（用户可见名，常是市场名）。 */
    fun marketName(context: Context?): String? {
        marketName { key -> runCatching { systemProperty(key) }.getOrNull() }?.let { return it }
        // 兜底：蓝牙/热点里那个"设备名称"。很多机型出厂就写的是市场名。
        val cr = context?.contentResolver ?: return null
        return runCatching {
            Settings.Global.getString(cr, "device_name")?.trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 把所有可用的身份串拼成一个 haystack（小写、空格分隔）。
     *
     * 交给匹配器后：无论厂商把名字放在 MODEL / DEVICE / PRODUCT / 市场名属性
     * 的哪一个里，只要目录登记过，就能命中。
     */
    fun identityText(
        manufacturer: String?,
        brand: String?,
        model: String?,
        device: String?,
        product: String?,
        board: String?,
        market: String?,
    ): String = listOfNotNull(manufacturer, brand, model, device, product, board, market)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(" ")
        .lowercase()

    /** 从当前设备收集（`Build.*` + [marketName]）。 */
    fun identityText(context: Context?): String = identityText(
        manufacturer = Build.MANUFACTURER,
        brand = Build.BRAND,
        model = Build.MODEL,
        device = Build.DEVICE,
        product = Build.PRODUCT,
        board = Build.BOARD,
        market = marketName(context),
    )

    /** 读系统属性。`System.getProperty` 读不到 Android 属性，必须走反射或 getprop。 */
    private fun systemProperty(key: String): String? = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        val m = c.getMethod("get", String::class.java)
        (m.invoke(null, key) as? String)?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
