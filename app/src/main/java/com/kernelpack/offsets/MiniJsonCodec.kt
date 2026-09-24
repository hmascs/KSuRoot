package com.kernelpack.offsets

/**
 * [MiniJson] 的解析与输出。
 *
 * 刻意做成**无依赖、可在纯 JVM 单测里跑**：offsets.json 是跨项目互通格式，
 * 它的编解码必须能脱离 Android 运行时单独验证 —— 否则只能靠装机试，
 * 而"导入再导出丢了字段"这类问题装机是看不出来的。
 */
object MiniJsonCodec {

    fun parse(text: String): MiniJson {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        return v
    }

    fun emit(v: MiniJson, indent: String = "  "): String = buildString { write(this, v, indent, 0) }

    private fun write(sb: StringBuilder, v: MiniJson, indent: String, depth: Int) {
        val pad = indent.repeat(depth)
        val padIn = indent.repeat(depth + 1)
        when (v) {
            is MiniJson.Str -> sb.append(quote(v.value))
            is MiniJson.Num -> sb.append(v.raw)
            is MiniJson.Bool -> sb.append(if (v.value) "true" else "false")
            MiniJson.Null -> sb.append("null")
            is MiniJson.Arr -> {
                if (v.items.isEmpty()) { sb.append("[]"); return }
                sb.append("[\n")
                v.items.forEachIndexed { i, item ->
                    sb.append(padIn); write(sb, item, indent, depth + 1)
                    if (i != v.items.lastIndex) sb.append(',')
                    sb.append('\n')
                }
                sb.append(pad).append(']')
            }
            is MiniJson.Obj -> {
                if (v.fields.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                v.fields.entries.forEachIndexed { i, (k, value) ->
                    sb.append(padIn).append(quote(k)).append(": ")
                    write(sb, value, indent, depth + 1)
                    if (i != v.fields.size - 1) sb.append(',')
                    sb.append('\n')
                }
                sb.append(pad).append('}')
            }
        }
    }

    fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    /** 递归下降解析器。输入不合法时抛 [IllegalArgumentException]，不静默返回空对象。 */
    private class Parser(private val s: String) {
        private var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): MiniJson {
            skipWs()
            require(i < s.length) { "JSON 意外结束（位置 $i）" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> MiniJson.Str(string())
                't' -> { expect("true"); MiniJson.Bool(true) }
                'f' -> { expect("false"); MiniJson.Bool(false) }
                'n' -> { expect("null"); MiniJson.Null }
                else -> if (c == '-' || c.isDigit()) num()
                else throw IllegalArgumentException("JSON 非法字符 '$c'（位置 $i）")
            }
        }

        private fun expect(word: String) {
            require(s.startsWith(word, i)) { "期望 $word（位置 $i）" }
            i += word.length
        }

        private fun obj(): MiniJson.Obj {
            i++ // {
            val m = LinkedHashMap<String, MiniJson>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return MiniJson.Obj(m) }
            while (true) {
                skipWs()
                val k = string()
                skipWs()
                require(i < s.length && s[i] == ':') { "期望 ':'（位置 $i）" }
                i++
                m[k] = value()
                skipWs()
                require(i < s.length) { "对象未闭合" }
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return MiniJson.Obj(m) }
                    else -> throw IllegalArgumentException("对象里期望 ',' 或 '}'（位置 $i）")
                }
            }
        }

        private fun arr(): MiniJson.Arr {
            i++ // [
            val list = ArrayList<MiniJson>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return MiniJson.Arr(list) }
            while (true) {
                list.add(value())
                skipWs()
                require(i < s.length) { "数组未闭合" }
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return MiniJson.Arr(list) }
                    else -> throw IllegalArgumentException("数组里期望 ',' 或 ']'（位置 $i）")
                }
            }
        }

        private fun string(): String {
            require(i < s.length && s[i] == '"') { "期望字符串（位置 $i）" }
            i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "字符串未闭合" }
                when (val c = s[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        require(i < s.length) { "转义未结束" }
                        when (val e = s[i]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                require(i + 4 < s.length) { "\\u 转义不完整" }
                                sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4
                            }
                            else -> throw IllegalArgumentException("未知转义 \\$e")
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
        }

        private fun num(): MiniJson.Num {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                        s[i] == '+' || s[i] == '-')
            ) i++
            return MiniJson.Num(s.substring(start, i))
        }
    }
}
