package com.smartledger.util

import com.smartledger.data.db.entity.Transaction
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class CsvImportFormatTest {
    private val header = "日期,时间,类型,金额,分类,商户,支付方式,备注,来源"
    private val row = "2026-10-30,12:34:56,支出,19.99,餐饮,测试餐厅,微信支付,午餐,自动"
    private fun parse(body: String) = CsvImportFormat.parse(StringReader("$header\n$body"))
    private fun transaction(row: CsvImportRow, offset: Long = 0) = Transaction(
        amount = row.amount, type = row.type, merchant = row.merchant,
        transactionTime = row.time + offset, source = row.source
    )

    @Test fun identicalBackupIsSuccessfulWithNoNewRecords() {
        val data = parse(row)
        val report = CsvImportFormat.plan(data, listOf(transaction(data.rows.single(), 999))).report
        assertTrue(report.success)
        assertEquals(0, report.added)
        assertEquals(1, report.duplicates)
        assertEquals(0, report.invalid)
        assertEquals("没有新增账单，1 笔记录均已存在。", report.message)
    }

    @Test fun previewDoesNotWriteAndConfirmRechecksNewArrivals() {
        val data = parse(row)
        val existing = mutableListOf<Transaction>()
        assertEquals(1, CsvImportFormat.plan(data, existing).report.added)
        assertTrue(existing.isEmpty())
        existing.add(transaction(data.rows.single()))
        val confirm = CsvImportFormat.plan(data, existing)
        assertEquals(0, confirm.report.added)
        assertEquals(1, confirm.report.duplicates)
    }

    @Test fun mixedNewDuplicateAndInvalidRowsAreCountedSeparately() {
        val data = parse("$row\n${row.replace("19.99", "20.00")}\n${row.replace("19.99", "NaN")}")
        val plan = CsvImportFormat.plan(data, listOf(transaction(data.rows.first())))
        assertTrue(plan.report.success)
        assertEquals(1, plan.report.added)
        assertEquals(1, plan.report.duplicates)
        assertEquals(1, plan.report.invalid)
        assertEquals(20.0, plan.rows.single().amount, 0.0)
    }

    @Test fun fileDuplicatesAreSkippedAndReimportAddsNothing() {
        val data = parse("$row\n$row")
        val first = CsvImportFormat.plan(data, emptyList())
        assertEquals(1, first.report.added)
        assertEquals(1, first.report.duplicates)
        val second = CsvImportFormat.plan(data, first.rows.map { transaction(it) })
        assertEquals(0, second.report.added)
        assertEquals(2, second.report.duplicates)
        assertTrue(second.report.success)
    }

    @Test fun differentMerchantTypeAmountOrTimeIsNotCollapsed() {
        val data = parse(row)
        val base = transaction(data.rows.single())
        listOf(
            base.copy(merchant = "另一餐厅"), base.copy(type = "income"),
            base.copy(amount = 20.0), base.copy(transactionTime = base.transactionTime + 1001)
        ).forEach { assertEquals(1, CsvImportFormat.plan(data, listOf(it)).report.added) }
    }

    @Test fun bomCrLfEscapedQuotesCommaAndMultilineFieldsAreRead() {
        val csv = "\uFEFF$header\r\n2026-10-30,12:34:56,支出,19.99,\"餐饮,聚餐\",\"测试\"\"餐厅\",微信支付,\"第一行\r\n第二行\",自动\r\n"
        val data = CsvImportFormat.parse(StringReader(csv))
        assertNull(data.error)
        assertEquals(0, data.invalid)
        val parsed = data.rows.single()
        assertEquals("餐饮,聚餐", parsed.category)
        assertEquals("测试\"餐厅", parsed.merchant)
        assertEquals("第一行\n第二行", parsed.note)
        assertEquals("auto", parsed.source)
    }

    @Test fun emptyFileAndHeaderOnlyHaveSpecificErrors() {
        val empty = CsvImportFormat.parse(StringReader("\uFEFF\n"))
        assertTrue(empty.error!!.contains("文件为空"))
        val headerOnly = parse("")
        assertTrue(headerOnly.error!!.contains("没有账单记录"))
        assertFalse(CsvImportFormat.plan(headerOnly, emptyList()).report.success)
    }

    @Test fun foreignOrMissingHeaderIsRejected() {
        listOf("交易时间,交易类型,金额\n2026-10-30,消费,1", row).forEach {
            val data = CsvImportFormat.parse(StringReader(it))
            assertTrue(data.error!!.contains("文件格式不支持"))
            assertTrue(data.rows.isEmpty())
        }
    }

    @Test fun invalidDatesTypesAmountsAndColumnCountsAreSkipped() {
        val invalid = listOf(
            row.replace("2026-10-30", "2026-02-30"), row.replace("12:34:56", "25:00:00"),
            row.replace("支出", "未知"), row.replace("19.99", "Infinity"),
            row.replace("19.99", "-1"), row.replace("19.99", "0"), row.replace("19.99", "1e100"),
            row.substringBeforeLast(','), row.replace("自动", "未知")
        )
        val data = parse((listOf(row) + invalid).joinToString("\n"))
        assertNull(data.error)
        assertEquals(1, data.rows.size)
        assertEquals(invalid.size, data.invalid)
    }

    @Test fun allInvalidRowsAreNotReportedAsDuplicates() {
        val report = CsvImportFormat.plan(parse(row.replace("19.99", "abc")), emptyList()).report
        assertFalse(report.success)
        assertEquals(1, report.invalid)
        assertEquals(0, report.duplicates)
        assertTrue(report.error!!.contains("未找到有效账单"))
    }

    @Test fun unclosedQuoteRejectsWholeFile() {
        val data = parse("$row\n\"未闭合")
        assertTrue(data.error!!.contains("引号格式错误"))
        assertTrue(data.rows.isEmpty())
    }

    @Test fun invalidQuoteRowDoesNotHideNextValidRow() {
        val data = parse("${row.replace("测试餐厅", "测试\"餐厅")}\n$row")
        assertEquals(1, data.invalid)
        assertEquals(1, data.rows.size)
    }

    @Test fun legacySevenColumnCsvStillImports() {
        val csv = header.substringBefore(",备注") + "\n" + row.substringBefore(",午餐")
        val data = CsvImportFormat.parse(StringReader(csv))
        assertNull(data.error)
        assertEquals("manual", data.rows.single().source)
        assertNull(data.rows.single().note)
    }
}
