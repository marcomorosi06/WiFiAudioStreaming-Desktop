/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */

sealed interface SnapJson {

    data object Null : SnapJson

    data class Bool(val value: Boolean) : SnapJson

    data class Num(val value: Double) : SnapJson

    data class Str(val value: String) : SnapJson

    data class Arr(val items: List<SnapJson>) : SnapJson

    data class Obj(val fields: Map<String, SnapJson>) : SnapJson

    companion object {

        fun parse(text: String): SnapJson? = runCatching { Parser(text).parseDocument() }.getOrNull()

        fun escape(raw: String): String {
            val sb = StringBuilder(raw.length + 8)
            for (c in raw) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else ->
                        if (c < ' ' || c == '\u007F') sb.append("\\u").append(String.format("%04x", c.code))
                        else sb.append(c)
                }
            }
            return sb.toString()
        }
    }

    private class Parser(private val src: String) {

        private var pos = 0

        fun parseDocument(): SnapJson {
            val value = parseValue()
            skipWhitespace()
            return value
        }

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun expect(c: Char) {
            if (pos >= src.length || src[pos] != c) error("expected '$c' at $pos")
            pos++
        }

        fun parseValue(): SnapJson {
            skipWhitespace()
            if (pos >= src.length) error("unexpected end of input")
            return when (src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> Str(parseString())
                't' -> { literal("true"); Bool(true) }
                'f' -> { literal("false"); Bool(false) }
                'n' -> { literal("null"); Null }
                else -> parseNumber()
            }
        }

        private fun literal(word: String) {
            if (!src.startsWith(word, pos)) error("invalid literal at $pos")
            pos += word.length
        }

        private fun parseObject(): Obj {
            expect('{')
            val fields = LinkedHashMap<String, SnapJson>()
            skipWhitespace()
            if (pos < src.length && src[pos] == '}') { pos++; return Obj(fields) }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                fields[key] = parseValue()
                skipWhitespace()
                if (pos >= src.length) error("unterminated object")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return Obj(fields) }
                    else -> error("unexpected '${src[pos]}' at $pos")
                }
            }
        }

        private fun parseArray(): Arr {
            expect('[')
            val items = ArrayList<SnapJson>()
            skipWhitespace()
            if (pos < src.length && src[pos] == ']') { pos++; return Arr(items) }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                if (pos >= src.length) error("unterminated array")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return Arr(items) }
                    else -> error("unexpected '${src[pos]}' at $pos")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= src.length) error("unterminated string")
                when (val c = src[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) error("unterminated escape")
                        when (val e = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) error("truncated unicode escape")
                                sb.append(src.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> error("invalid escape '$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Num {
            val start = pos
            if (pos < src.length && (src[pos] == '-' || src[pos] == '+')) pos++
            while (pos < src.length && (src[pos].isDigit() || src[pos] in ".eE+-")) pos++
            val text = src.substring(start, pos)
            return Num(text.toDoubleOrNull() ?: error("invalid number '$text'"))
        }
    }
}

fun SnapJson?.field(key: String): SnapJson? = (this as? SnapJson.Obj)?.fields?.get(key)

fun SnapJson?.asString(): String? = (this as? SnapJson.Str)?.value

fun SnapJson?.asInt(): Int? = (this as? SnapJson.Num)?.value?.toInt()

fun SnapJson?.asLong(): Long? = (this as? SnapJson.Num)?.value?.toLong()

fun SnapJson?.asBool(): Boolean? = (this as? SnapJson.Bool)?.value

fun SnapJson?.asArray(): List<SnapJson> = (this as? SnapJson.Arr)?.items ?: emptyList()

fun SnapJson?.stringAt(key: String): String? = field(key).asString()

fun SnapJson?.intAt(key: String): Int? = field(key).asInt()

fun SnapJson?.boolAt(key: String): Boolean? = field(key).asBool()

class SnapJsonWriter {

    private val sb = StringBuilder(256)

    fun obj(block: ObjectScope.() -> Unit): String {
        sb.setLength(0)
        ObjectScope().block()
        sb.append('}')
        return sb.toString()
    }

    fun arrayOfObjects(count: Int, block: ObjectScope.(Int) -> Unit): String {
        sb.setLength(0)
        sb.append('[')
        for (i in 0 until count) {
            if (i > 0) sb.append(',')
            sb.append('{')
            val scope = ObjectScope(opened = true)
            scope.block(i)
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    inner class ObjectScope(opened: Boolean = false) {

        private var first = true

        init {
            if (!opened) sb.append('{')
        }

        private fun key(name: String) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(SnapJson.escape(name)).append("\":")
        }

        fun put(name: String, value: String) {
            key(name)
            sb.append('"').append(SnapJson.escape(value)).append('"')
        }

        fun put(name: String, value: Int) {
            key(name)
            sb.append(value)
        }

        fun put(name: String, value: Long) {
            key(name)
            sb.append(value)
        }

        fun put(name: String, value: Boolean) {
            key(name)
            sb.append(if (value) "true" else "false")
        }

        fun putRaw(name: String, rawJson: String) {
            key(name)
            sb.append(rawJson)
        }

        fun putNull(name: String) {
            key(name)
            sb.append("null")
        }

        fun obj(name: String, block: ObjectScope.() -> Unit) {
            key(name)
            sb.append('{')
            ObjectScope(opened = true).block()
            sb.append('}')
        }

        fun arrayOfStrings(name: String, values: List<String>) {
            key(name)
            sb.append('[')
            values.forEachIndexed { index, value ->
                if (index > 0) sb.append(',')
                sb.append('"').append(SnapJson.escape(value)).append('"')
            }
            sb.append(']')
        }

        fun rawArray(name: String, rawItems: List<String>) {
            key(name)
            sb.append('[')
            rawItems.forEachIndexed { index, value ->
                if (index > 0) sb.append(',')
                sb.append(value)
            }
            sb.append(']')
        }
    }

    companion object {

        fun write(block: ObjectScope.() -> Unit): String = SnapJsonWriter().obj(block)
    }
}
