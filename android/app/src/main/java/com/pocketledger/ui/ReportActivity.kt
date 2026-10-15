package com.pocketledger.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pocketledger.R
import com.pocketledger.core.TxnKind
import com.pocketledger.databinding.ActivityReportBinding
import com.pocketledger.parse.AmountParser
import com.pocketledger.sync.LedgerSync
import com.pocketledger.sync.ServerPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 月报。数字来自家里的电脑，不在手机上重算。
 */
class ReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReportBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val zone: TimeZone = TimeZone.getTimeZone("GMT+8")
    private lateinit var month: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        month = savedInstanceState?.getString(STATE_MONTH) ?: currentMonth()
        binding.btnPrev.setOnClickListener {
            month = shiftMonth(month, -1)
            load()
        }
        binding.btnNext.setOnClickListener {
            month = shiftMonth(month, 1)
            load()
        }
        binding.btnReload.setOnClickListener { load() }
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_MONTH, month)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun load() {
        binding.tvMonth.text = month
        if (!ServerPrefs(this).isConfigured()) {
            showMessage(getString(R.string.report_not_configured))
            return
        }
        binding.tvSyncStatus.setText(R.string.report_loading)
        val selected = month
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val uploaded = LedgerSync.flush(this@ReportActivity)
                    Loaded(uploaded, LedgerSync.fetchMonth(this@ReportActivity, selected))
                } catch (e: Exception) {
                    Failed(e.message ?: e.javaClass.simpleName)
                }
            }
            if (selected != month) return@launch
            when (result) {
                is Loaded -> render(result.uploaded, result.report)
                is Failed -> showMessage(getString(R.string.report_failed, result.message))
            }
        }
    }

    private fun render(uploaded: Int, report: JSONObject) {
        val totals = report.getJSONObject("totals")
        val expense = totals.getLong("expense_cents")
        val income = totals.getLong("income_cents")
        val refund = totals.getLong("refund_cents")
        binding.tvSyncStatus.text = getString(R.string.report_synced, uploaded)
        binding.tvExpense.text = getString(R.string.report_expense, AmountParser.format(expense))
        binding.tvIncome.text = getString(R.string.report_income, AmountParser.format(income))
        binding.tvRefund.text = getString(R.string.report_refund, AmountParser.format(refund))
        binding.tvCategories.text = formatCategories(report, expense)
        binding.tvTrend.text = formatTrend(report)
        binding.tvRecent.text = formatRecent(report)
    }

    private fun showMessage(message: String) {
        binding.tvSyncStatus.text = message
        binding.tvExpense.text = ""
        binding.tvIncome.text = ""
        binding.tvRefund.text = ""
        binding.tvCategories.setText(R.string.report_empty)
        binding.tvTrend.setText(R.string.report_empty)
        binding.tvRecent.setText(R.string.report_empty)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun formatCategories(report: JSONObject, expenseCents: Long): String {
        val rows = report.getJSONArray("byCategory")
        if (rows.length() == 0) return getString(R.string.report_empty)
        return buildString {
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val cents = row.getLong("cents")
                val percent = if (expenseCents <= 0L) 0 else (cents * 100 / expenseCents)
                if (index > 0) append('\n')
                append(row.getString("category"))
                append("  ")
                append(AmountParser.format(cents))
                append("  ")
                append(percent)
                append('%')
            }
        }
    }

    private fun formatTrend(report: JSONObject): String {
        val rows = report.getJSONArray("byDay")
        if (rows.length() == 0) return getString(R.string.report_empty)
        return buildString {
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                if (index > 0) append('\n')
                append(row.getString("day").substring(5))
                append("  支出 ")
                append(AmountParser.format(row.getLong("expense_cents")))
                append("  收入 ")
                append(AmountParser.format(row.getLong("income_cents")))
            }
        }
    }

    private fun formatRecent(report: JSONObject): String {
        val rows = report.getJSONArray("recent")
        if (rows.length() == 0) return getString(R.string.report_empty)
        return buildString {
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                if (index > 0) append('\n')
                append(formatTime(row.getLong("occurred_at")))
                append("  ")
                append(amountText(row.getString("kind"), row.getLong("amount_cents")))
                append("  ")
                append(row.optString("channel", getString(R.string.report_unknown_channel)))
                val memo = row.optString("memo")
                val counterparty = row.optString("counterparty")
                val extra = listOf(counterparty, memo).filter { it.isNotBlank() }.joinToString(" ")
                if (extra.isNotBlank()) {
                    append("  ")
                    append(extra)
                }
            }
        }
    }

    private fun amountText(kind: String, cents: Long): String {
        val formatted = AmountParser.format(cents)
        return when (TxnKind.fromWire(kind)) {
            TxnKind.EXPENSE -> "-$formatted"
            TxnKind.INCOME, TxnKind.REFUND -> "+$formatted"
            TxnKind.TRANSFER -> "↔$formatted"
            TxnKind.UNKNOWN -> formatted
        }
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).apply { timeZone = zone }.format(Date(millis))

    private fun currentMonth(): String =
        SimpleDateFormat("yyyy-MM", Locale.US).apply { timeZone = zone }.format(Date())

    private fun shiftMonth(month: String, delta: Int): String {
        val format = SimpleDateFormat("yyyy-MM", Locale.US).apply { timeZone = zone }
        val calendar = Calendar.getInstance(zone)
        calendar.time = format.parse(month) ?: Date()
        calendar.add(Calendar.MONTH, delta)
        return format.format(calendar.time)
    }

    private data class Loaded(val uploaded: Int, val report: JSONObject)
    private data class Failed(val message: String)

    private companion object {
        const val STATE_MONTH = "month"
    }
}
