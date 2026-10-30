package com.smartledger.service

import org.junit.Assert.*
import org.junit.Test

class PaymentDiagnosticTest {
    private fun entry(id: String) = PaymentDiagnostic(id, 1_800_000_000_000L, "微信支付",
        linkedMapOf("原始标题" to "微信支付", "普通正文" to "已支付¥12.00", "展开正文" to ""), "微信支付 已支付¥12.00")

    @Test fun recordingIsOffByDefault() {
        val history = PaymentDiagnosticHistory()
        assertFalse(history.enabled)
        assertFalse(history.add(entry("one")))
        assertTrue(history.entries.isEmpty())
    }

    @Test fun onlyLatestThirtyAreKeptNewestFirst() {
        val history = PaymentDiagnosticHistory(enabled = true)
        repeat(40) { history.add(entry(it.toString())) }
        assertEquals(30, history.entries.size)
        assertEquals("39", history.entries.first().id)
        assertEquals("10", history.entries.last().id)
    }

    @Test fun disablingStopsRecordingAndKeepsHistory() {
        val history = PaymentDiagnosticHistory(enabled = true)
        history.add(entry("one"))
        history.enabled = false
        assertFalse(history.add(entry("two")))
        history.update("one") { it.copy(status = "已入账") }
        assertEquals(listOf("one"), history.entries.map { it.id })
        assertEquals("处理中", history.entries.single().status)
    }

    @Test fun clearDoesNotChangeSwitchAndOldCallbacksCannotRecreateRecords() {
        val history = PaymentDiagnosticHistory(enabled = true)
        history.add(entry("one"))
        history.clear()
        history.update("one") { it.copy(status = "已入账") }
        assertTrue(history.enabled)
        assertTrue(history.entries.isEmpty())
        history.add(entry("two"))
        assertEquals("two", history.entries.single().id)
    }

    @Test fun updatesKeepOriginalFieldsAndDoNotMoveEntries() {
        val history = PaymentDiagnosticHistory(enabled = true)
        history.add(entry("one"))
        history.add(entry("two"))
        history.update("one") { it.copy(status = "已入账", amount = 12.0, category = "其他") }
        assertEquals(listOf("two", "one"), history.entries.map { it.id })
        assertEquals("已支付¥12.00", history.entries.last().fields["普通正文"])
        assertEquals("其他", history.entries.last().category)
    }

    @Test fun evictedRecordsCannotBeRecreatedByLateCallbacks() {
        val history = PaymentDiagnosticHistory(enabled = true)
        repeat(31) { history.add(entry(it.toString())) }
        history.update("0") { it.copy(status = "已入账") }
        assertEquals(30, history.entries.size)
        assertFalse(history.entries.any { it.id == "0" })
    }

    @Test fun discardingAnUnsafeGroupedChildDoesNotRestoreItOnCompletion() {
        val history = PaymentDiagnosticHistory(enabled = true)
        history.add(entry("one"))
        history.remove("one")
        history.update("one") { it.copy(status = "已入账") }
        assertTrue(history.entries.isEmpty())
        assertTrue(history.enabled)
    }

    @Test fun restoredHistoryKeepsEnabledFlagAndLimit() {
        val history = PaymentDiagnosticHistory(true, (40 downTo 1).map { entry(it.toString()) })
        assertTrue(history.enabled)
        assertEquals(30, history.entries.size)
        assertEquals("40", history.entries.first().id)
    }

    @Test fun paymentServiceAndGroupedPaymentTitlesAreAllowed() {
        listOf("微信支付", "[2条]微信支付", "【3条】微信支付", "微信支付：付款成功").forEach {
            assertTrue(PaymentDiagnosticPolicy.shouldRecord("com.tencent.mm", it, "已支付¥12.00", true))
        }
    }

    @Test fun ordinaryWechatChatIsExcludedEvenWhenItMentionsPayment() {
        listOf("张三", "工作群", "微信", "文件传输助手", "微信支付讨论群").forEach {
            assertFalse(PaymentDiagnosticPolicy.shouldRecord("com.tencent.mm", it, "微信支付：已支付¥12.00", true))
        }
    }

    @Test fun unsupportedAppsAreExcludedEvenIfTheyMentionPayment() {
        assertFalse(PaymentDiagnosticPolicy.shouldRecord("com.example.chat", "微信支付", "已支付¥12.00", false))
    }

    @Test fun otherChannelsIncludeUnrecognizedOrFilteredPaymentNotices() {
        assertTrue(PaymentDiagnosticPolicy.shouldRecord("com.eg.android.AlipayGphone", "支付宝", "支付成功", true))
        assertTrue(PaymentDiagnosticPolicy.shouldRecord("com.icbc", "动账通知", "支出信息不完整", true))
        assertFalse(PaymentDiagnosticPolicy.shouldRecord("com.jingdong.app.mall", "京东", "您的快递已签收", true))
    }

    @Test fun copiedDetailsDistinguishMissingMerchantFromAbsentNotificationData() {
        val text = entry("one").copy(status = "已入账", amount = 12.0, type = "expense",
            method = "微信支付", category = "其他").detailText()
        assertTrue(text.contains("普通正文：已支付¥12.00"))
        assertTrue(text.contains("展开正文：未提供"))
        assertTrue(text.contains("商家：未提取到商家"))
        assertTrue(text.contains("金额：12.00"))
        assertTrue(text.contains("分类：其他"))
        assertTrue(text.contains("最终处理结果：已入账"))
        assertFalse(text.contains("通知没有商家"))
    }
}
