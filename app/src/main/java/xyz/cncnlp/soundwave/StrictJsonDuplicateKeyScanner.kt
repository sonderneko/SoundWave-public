package xyz.cncnlp.soundwave

/** Strict JSON grammar and duplicate-key validation before org.json can collapse object keys. */
internal object StrictJsonDuplicateKeyScanner {
    fun validate(text: String, maxStringChars: Int) {
        Scanner(text, maxStringChars).validate()
    }

    private class Scanner(private val text: String, private val maxStringChars: Int) {
        private var index = 0
        private var nodes = 0

        fun validate() {
            skipWhitespace()
            parseValue(0)
            skipWhitespace()
            require(index == text.length) { "JSON 含有尾随内容" }
        }

        private fun parseValue(depth: Int) {
            require(depth <= 16) { "JSON 嵌套层级超过上限" }
            nodes++
            require(nodes <= 3_000_000) { "JSON 结构超过导入上限" }
            skipWhitespace()
            require(index < text.length) { "JSON 内容不完整" }
            when (text[index]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> parseString(capture = false)
                't' -> parseLiteral("true")
                'f' -> parseLiteral("false")
                'n' -> parseLiteral("null")
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("JSON 语法无效")
            }
        }

        private fun parseObject(depth: Int) {
            index++
            skipWhitespace()
            if (consume('}')) return
            val seen = HashSet<String>()
            var members = 0
            while (true) {
                require(index < text.length && text[index] == '"') { "JSON 对象键无效" }
                val key = parseString(capture = true)!!
                require(seen.add(key)) { "JSON 含有重复字段" }
                members++
                require(members <= 100_000) { "JSON 对象字段数量超过上限" }
                skipWhitespace()
                require(consume(':')) { "JSON 对象缺少冒号" }
                parseValue(depth + 1)
                skipWhitespace()
                if (consume('}')) return
                require(consume(',')) { "JSON 对象分隔符无效" }
                skipWhitespace()
            }
        }

        private fun parseArray(depth: Int) {
            index++
            skipWhitespace()
            if (consume(']')) return
            var elements = 0
            while (true) {
                parseValue(depth + 1)
                elements++
                require(elements <= 1_000_000) { "JSON 列表长度超过上限" }
                skipWhitespace()
                if (consume(']')) return
                require(consume(',')) { "JSON 列表分隔符无效" }
                skipWhitespace()
            }
        }

        private fun parseString(capture: Boolean): String? {
            require(consume('"')) { "JSON 字符串无效" }
            val result = if (capture) StringBuilder() else null
            var length = 0
            while (index < text.length) {
                val char = text[index++]
                if (char == '"') return result?.toString()
                val decoded = when {
                    char == '\\' -> {
                        require(index < text.length) { "JSON 转义无效" }
                        when (val escape = text[index++]) {
                            '"', '\\', '/' -> escape
                            'b' -> '\b'
                            'f' -> '\u000c'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> {
                                require(index + 4 <= text.length) { "JSON Unicode 转义无效" }
                                val hex = text.substring(index, index + 4)
                                require(hex.all { it.digitToIntOrNull(16) != null }) { "JSON Unicode 转义无效" }
                                index += 4
                                hex.toInt(16).toChar()
                            }
                            else -> throw IllegalArgumentException("JSON 转义无效")
                        }
                    }
                    char.code < 0x20 -> throw IllegalArgumentException("JSON 字符串含有控制字符")
                    else -> char
                }
                length++
                require(length <= maxStringChars) { "JSON 文本长度超过导入上限" }
                result?.append(decoded)
            }
            throw IllegalArgumentException("JSON 字符串未闭合")
        }

        private fun parseNumber() {
            if (consume('-')) require(index < text.length) { "JSON 数字无效" }
            if (consume('0')) {
                require(index >= text.length || text[index] !in '0'..'9') { "JSON 数字格式无效" }
            } else {
                require(index < text.length && text[index] in '1'..'9') { "JSON 数字无效" }
                while (index < text.length && text[index] in '0'..'9') index++
            }
            if (consume('.')) {
                require(index < text.length && text[index] in '0'..'9') { "JSON 小数无效" }
                while (index < text.length && text[index] in '0'..'9') index++
            }
            if (index < text.length && text[index] in charArrayOf('e', 'E')) {
                index++
                if (index < text.length && text[index] in charArrayOf('+', '-')) index++
                require(index < text.length && text[index] in '0'..'9') { "JSON 指数无效" }
                while (index < text.length && text[index] in '0'..'9') index++
            }
        }

        private fun parseLiteral(expected: String) {
            require(text.regionMatches(index, expected, 0, expected.length)) { "JSON 字面值无效" }
            index += expected.length
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index] in charArrayOf(' ', '\t', '\n', '\r')) index++
        }

        private fun consume(char: Char): Boolean {
            if (index >= text.length || text[index] != char) return false
            index++
            return true
        }
    }
}
