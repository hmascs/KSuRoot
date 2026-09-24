package com.ting.root

import java.io.File

/**
 * 一次解析出来的载荷目标（**纯本地**）。
 *
 * 原先这里还有 `RemoteArtifact`、`SupportManifest`、`TargetProfile.matches()` 一整套 ——
 * 那是给"从 GitHub 在线清单里按机型挑一份下载"用的，机型全是三星 Galaxy。
 * 在线源移除后那套结构整体下线：现在所有载荷都是本地的，能描述一个目标的
 * 只有三个字段 —— 它叫什么、机型是谁、内核是哪个版本。
 */
data class TargetProfile(
    val profileId: String,
    val displayName: String,
    val models: Set<String>,
    val kernelVersions: Set<String>,
) {
    /** 机型列表的可读形式（日志与提示用）。 */
    val supportedModels: String get() = models.joinToString(" / ")

    /** 内核版本列表的可读形式。 */
    val supportedKernelVersions: String get() = kernelVersions.joinToString(" / ")
}

/** 一次安装实际用到的载荷文件。 */
data class VerifiedPayloads(
    val profile: TargetProfile,
    val exploit: File,
    /** 需要另行加载的 KernelSU payload；内置来源为 `null`（载荷自带）。 */
    val kernelSu: File?,
)
