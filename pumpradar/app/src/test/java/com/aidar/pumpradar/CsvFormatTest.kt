package com.aidar.pumpradar

import com.aidar.pumpradar.data.export.CsvFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** RFC 4180 кодирование/разбор + фиксированное число колонок. */
class CsvFormatTest {

    @Test fun numUsesPointAndKeepsSmall() {
        assertEquals("", CsvFormat.num(null))
        assertEquals("0", CsvFormat.num(0.0))
        assertEquals("1", CsvFormat.num(1.0))
        assertEquals("0.1", CsvFormat.num(0.1))
        assertEquals("-1.5", CsvFormat.num(-1.5))
        // Малое ненулевое не округляется до нуля.
        assertEquals("0.0000001", CsvFormat.num(0.0000001))
    }

    @Test fun escapeQuotesFieldsWithSpecials() {
        assertEquals("plain", CsvFormat.escape("plain"))
        assertEquals("\"a,b\"", CsvFormat.escape("a,b"))
        assertEquals("\"a\"\"b\"", CsvFormat.escape("a\"b"))
    }

    @Test fun roundTripPreservesFields() {
        val fields = listOf("BTCUSDT", "a,b", "q\"q", "", "1.5", "0")
        val line = CsvFormat.encodeRow(fields, fields.size)
        val parsed = CsvFormat.parseLine(line)
        assertEquals(fields, parsed)
    }

    @Test fun thirtyColumnRoundTrip() {
        val fields = (1..30).map { if (it % 5 == 0) "v,$it" else it.toString() }
        val line = CsvFormat.encodeRow(fields, 30)
        val parsed = CsvFormat.parseLine(line)
        assertEquals(30, parsed.size)
        assertEquals(fields, parsed)
    }

    @Test fun wrongColumnCountThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            CsvFormat.encodeRow(listOf("a", "b"), 3)
        }
    }
}
