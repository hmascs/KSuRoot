package com.ting.root

import android.content.Context
import java.io.File

/**
 * 载荷解析的统一入口。
 *
 * ### 三条来源，一条优先级链
 *
 * | 来源 | 说明 | 是否需要网络 |
 * |---|---|---|
 * | [PayloadSource.Bundled] | 随包内置的厂商载荷（38 份 `libksu_*.so` + 2 份通用库） | 否 |
 * | [PayloadSource.Custom] | 用户自己导入的 `.so` | 否 |
 * | ~~`Online`~~ | **已移除** —— 原先是三星专用的 GitHub 在线源 | — |
 *
 * ### 内置来源怎么挑库
 *
 * 全部委托给 [BundledPayloadCatalog]：设备机型 + 内核版本 + 内核 commit 三级匹配，
 * 命中不了就回落到"同厂商 + 同内核大版本"的兄弟机型（标成**可能可用**）。
 * 这里**不再**做任何猜测 —— 所有规则都摆在那张显式登记的表里。
 *
 * ### 为什么不再有在线源
 *
 * 上游那份 GitHub 清单（`support/targets-v3.json`）登记的机型全是三星 Galaxy，
 * 载荷也是为三星内核编的。随包内置库覆盖小米与 vivo 之后，这条路径既没有可用目标、
 * 又会在启动时白白拉一次 GitHub API —— 已整体删除。
 */
class PayloadRepository(private val context: Context) {

    /** 本次安装里实际存在的内置载荷数量，用于自检与界面提示。 */
    val bundledCount: Int
        get() = BundledPayloadCatalog.ALL.count { BundledPayloadCatalog.fileFor(context, it) != null }

    /**
     * 内置来源：为当前设备挑一份随包载荷。
     *
     * @param allowSimilar 是否允许回落到"可能可用"的兄弟机型载荷。
     * @throws IllegalStateException 设备不在内置清单里（调用方应提示用户改用自定义载荷）。
     */
    fun bundledTarget(
        snapshot: DeviceSnapshot,
        allowSimilar: Boolean = true,
    ): BundledResolution {
        val resolution = BundledPayloadCatalog.resolve(context, snapshot, allowSimilar)
            ?: error(context.getString(R.string.error_device_unsupported, snapshot.model))
        return BundledResolution(resolution)
    }

    /** 把命中结果物化成一份可执行文件（必要时 staged 到私有目录）。 */
    fun bundledPayloads(resolution: BundledResolution): VerifiedPayloads {
        val entry = resolution.resolution.entry
        val staged = PayloadStaging.ensureReadable(context, resolution.resolution.file)
        require(staged.canRead()) { context.getString(R.string.error_bundled_missing) }
        return VerifiedPayloads(
            profile = bundledProfile(entry),
            exploit = staged,
            kernelSu = null,
        )
    }

    /** 供界面展示用的 profile（不再是远端工件，一律本地）。 */
    fun bundledProfile(entry: BundledPayloadCatalog.BundledPayload): TargetProfile = TargetProfile(
        profileId = entry.library,
        displayName = entry.displayName,
        models = entry.model?.toSet() ?: setOf(entry.displayName),
        kernelVersions = setOfNotNull(entry.kernelVersion),
    )

    /** 自定义来源：用户导入的 `.so`，原样使用。 */
    fun customTarget(snapshot: DeviceSnapshot): TargetProfile = TargetProfile(
        profileId = CUSTOM_PROFILE_ID,
        displayName = context.getString(R.string.payload_source_custom),
        models = setOf(snapshot.model),
        kernelVersions = setOf(snapshot.kernelVersion),
    )

    fun customPayloads(profile: TargetProfile, info: CustomPayloadInfo): VerifiedPayloads {
        require(info.file.exists()) { context.getString(R.string.custom_import_failed) }
        val exploit = PayloadStaging.ensureReadable(context, info.file)
        require(exploit.canRead()) { context.getString(R.string.custom_import_failed) }
        return VerifiedPayloads(profile, exploit, null)
    }

    /** 载荷列表里可手动选用的条目（含无法自动匹配的通用包）。 */
    fun listBundled(): List<BundledPayloadCatalog.BundledPayload> =
        BundledPayloadCatalog.ALL.filter { BundledPayloadCatalog.fileFor(context, it) != null }

    /** 手动选用某一条内置载荷（用户从列表里点选）。 */
    fun selectBundled(library: String): BundledResolution? {
        val entry = BundledPayloadCatalog.byLibrary(library) ?: return null
        val file = BundledPayloadCatalog.fileFor(context, entry) ?: return null
        return BundledResolution(
            BundledPayloadCatalog.Resolution(entry, BundledPayloadCatalog.MatchTier.Exact, file),
        )
    }

    companion object {
        const val CUSTOM_PROFILE_ID = "custom-payload"
    }
}

/** 一次内置载荷命中的包装，带上它是怎么被挑中的。 */
data class BundledResolution(val resolution: BundledPayloadCatalog.Resolution) {
    val entry: BundledPayloadCatalog.BundledPayload get() = resolution.entry
    val tier: BundledPayloadCatalog.MatchTier get() = resolution.tier

    /** 是否属于"可能可用"，界面需要额外提示。 */
    val provisional: Boolean get() = resolution.provisional
}
