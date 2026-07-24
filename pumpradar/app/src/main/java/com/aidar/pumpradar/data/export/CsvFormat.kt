package com.aidar.pumpradar.data.export

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * RFC 4180-совместимое кодирование CSV для ML-датасета.
 *
 * Гарантии:
 *  - десятичный разделитель — всегда точка (BigDecimal.toPlainString, независимо
 *    от локали устройства → эквивалент Locale.US);
 *  - малые ненулевые значения не округляются до нуля (масштаб 10 знаков);
 *  - поля с запятой/кавычкой/переводом строки экранируются кавычками, внутренние
 *    кавычки удваиваются;
 *  - число колонок в каждой строке фиксировано и проверяется.
 */
object CsvFormat {

    /** Числовое поле: точка-разделитель, без экспоненты, без округления малых до 0. */
    fun num(v: Double?): String {
        if (v == null) return ""
        if (v == 0.0) return "0"
        if (v.isNaN() || v.isInfinite()) return ""
        val bd = BigDecimal(v).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros()
        return bd.toPlainString()
    }

    /** Экранирование одного поля по RFC 4180. */
    fun escape(field: String): String {
        val needsQuote = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuote) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }

    /** Кодирование строки фиксированной ширины. Бросает, если ширина не совпала. */
    fun encodeRow(fields: List<String>, expectedColumns: Int): String {
        require(fields.size == expectedColumns) {
            "CSV row has ${fields.size} columns, expected $expectedColumns"
        }
        return fields.joinToString(",") { escape(it) }
    }

    /** Разбор одной строки CSV (RFC 4180, без встроенных переводов строк). */
    fun parseLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ }
                    else inQuotes = false
                } else sb.append(ch)
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    ',' -> { out.add(sb.toString()); sb.clear() }
                    else -> sb.append(ch)
                }
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
