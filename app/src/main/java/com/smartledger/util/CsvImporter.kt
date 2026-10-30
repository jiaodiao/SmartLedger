package com.smartledger.util

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.smartledger.data.db.AppDatabase
import com.smartledger.data.db.entity.Transaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

object CsvImporter {
    data class Preview(val fileName: String, val data: CsvImportData, val report: CsvImportReport)

    suspend fun restore(context: Context, fileName: String): CsvImportReport = withContext(Dispatchers.IO) {
        val entry = BackupStorage.listBackups(context).firstOrNull { it.fileName == fileName }
            ?: return@withContext CsvImportReport(error = "找不到备份文件，请重新选择文件。")
        restoreFromEntry(context, entry)
    }

    suspend fun restoreFromEntry(context: Context, entry: BackupStorage.Entry): CsvImportReport =
        withContext(Dispatchers.IO) {
            try {
                val data = BackupStorage.openReader(context, entry)?.use { CsvImportFormat.parse(it) }
                    ?: return@withContext CsvImportReport(error = "无法读取备份，请检查文件是否存在及访问权限。")
                importData(context, data)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                CsvImportReport(error = "恢复失败，无法读取文件或写入账单，请重试。")
            }
        }

    suspend fun previewFromUri(context: Context, uri: Uri): Preview = withContext(Dispatchers.IO) {
        var fileName = uri.lastPathSegment ?: "所选文件"
        try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) fileName = it.getString(0) ?: fileName
            }
            val data = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                CsvImportFormat.parse(it)
            } ?: CsvImportData(error = "无法读取文件，请重新选择并允许访问。")
            val existing = if (data.error == null) AppDatabase.getInstance(context).transactionDao().getAll().first() else emptyList()
            Preview(fileName, data, CsvImportFormat.plan(data, existing).report)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val data = CsvImportData(error = "无法读取文件或账单，请检查文件及访问权限后重试。")
            Preview(fileName, data, CsvImportReport(error = data.error))
        }
    }

    /** 使用预览时读取的内容；确认时在事务中重新去重，失败时整批回滚。 */
    suspend fun importData(context: Context, data: CsvImportData): CsvImportReport = withContext(Dispatchers.IO) {
        if (data.error != null) return@withContext CsvImportReport(invalid = data.invalid, error = data.error)
        try {
            val db = AppDatabase.getInstance(context)
            db.withTransaction {
                val plan = CsvImportFormat.plan(data, db.transactionDao().getAll().first())
                val categories = db.categoryDao().getAllOnce().associateBy { "${it.type}:${it.name}" }
                plan.rows.forEach { row ->
                    db.transactionDao().insert(Transaction(
                        amount = row.amount,
                        type = row.type,
                        categoryId = (categories["${row.type}:${row.category}"] ?: categories["${row.type}:其他"])?.id,
                        merchant = row.merchant,
                        paymentMethod = row.method,
                        note = row.note,
                        source = row.source,
                        notificationKey = null,
                        transactionTime = row.time
                    ))
                }
                plan.report
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            CsvImportReport(invalid = data.invalid, error = "写入失败，本次操作已撤销，已有账单不受影响。请重试。")
        }
    }
}
