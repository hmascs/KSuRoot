package com.kernelpack.export

import com.kernelpack.Hex
import com.kernelpack.model.TargetProfile

/**
 * 极简 JSON 输出（不引入任何第三方依赖，方便塞进任何 Android 工程）。
 *
 * 产出的 JSON 既能给图形界面读，也能作为"基线档位"回灌给
 * [com.kernelpack.profile.BaselineProfile] —— 这样别的项目/别的机型
 * 只要跑一次本工具、存一份 JSON，下次就能直接给同型号设备打包。
 */
object OffsetsJson {

    fun write(profile: TargetProfile, indent: String = "  "): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append(indent).append("\"variantLabel\": ").append(str(profile.variantLabel)).append(",\n")
        sb.append(indent).append("\"versionNumber\": ").append(str(profile.versionNumber)).append(",\n")
        sb.append(indent).append("\"architecture\": ").append(str(profile.architecture)).append(",\n")
        sb.append(indent).append("\"imageBase\": ").append(str(Hex.u64(profile.imageBase))).append(",\n")

        sb.append(indent).append("\"memoryLayout\": {\n")
        sb.append(mapBody(profile.memoryLayout, indent + indent) { Hex.u64(it) })
        sb.append(indent).append("},\n")

        sb.append(indent).append("\"symbolOffsets\": {\n")
        sb.append(
            profile.offsets.entries.joinToString(",\n") { (k, e) ->
                val value = e.offset?.let { "\"${Hex.u(it)}\"" } ?: "null"
                "$indent$indent${str(k)}: { \"value\": $value, \"source\": ${str(e.source.name)}, \"detail\": ${str(e.detail)} }"
            }
        )
        sb.append("\n").append(indent).append("},\n")

        sb.append(indent).append("\"structOffsets\": {\n")
        sb.append(mapBody(profile.structOffsets, indent + indent) { Hex.u(it) })
        sb.append(indent).append("},\n")

        sb.append(indent).append("\"unresolved\": [")
        sb.append(profile.unresolved.joinToString(", ") { str(it) })
        sb.append("]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun mapBody(
        map: Map<String, Long>,
        indent: String,
        fmt: (Long) -> String,
    ): String = map.entries.joinToString(",\n") { (k, v) -> "$indent${str(k)}: \"${fmt(v)}\"" } + "\n"

    private fun str(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
