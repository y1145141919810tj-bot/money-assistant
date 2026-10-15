package com.pocketledger.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.pocketledger.R
import com.pocketledger.capture.CapturePipeline
import com.pocketledger.capture.SmsInboxReader
import com.pocketledger.core.TxnKind
import com.pocketledger.data.HealthRow
import com.pocketledger.data.LedgerCounts
import com.pocketledger.data.LedgerRepository
import com.pocketledger.data.TxnRow
import com.pocketledger.data.UnmatchedRow
import com.pocketledger.databinding.ActivityMainBinding
import com.pocketledger.parse.AmountParser
import com.pocketledger.sync.LedgerSync
import com.pocketledger.sync.ServerPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 采集状态面板与流水列表。
 *
 * P0 的验收就发生在这个界面上：授权后付一笔钱，列表里应当在数秒内出现该笔记录。
 */
class MainActivity : AppCompatActivity() {

    private enum class RowFilter { ALL, UNMATCHED }

    private lateinit var binding: ActivityMainBinding
    private val adapter = LedgerAdapter()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var smsReader: SmsInboxReader
    private var currentFilter = RowFilter.ALL
    private var receiverRegistered = false

    private val updatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshData()
        }
    }

    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                smsReader.start()
                toast(getString(R.string.toast_sms_granted))
            } else {
                toast(getString(R.string.toast_sms_denied))
            }
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        smsReader = SmsInboxReader(this)
        binding.rvRows.layoutManager = LinearLayoutManager(this)
        binding.rvRows.adapter = adapter

        binding.btnNotifSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        binding.btnSmsPermission.setOnClickListener { requestSmsPermission() }
        binding.btnBackfill.setOnClickListener { runBackfill() }
        binding.btnRefresh.setOnClickListener {
            refreshStatus()
            refreshData()
        }
        binding.btnFilterAll.setOnClickListener {
            currentFilter = RowFilter.ALL
            refreshData()
        }
        binding.btnFilterUnmatched.setOnClickListener {
            currentFilter = RowFilter.UNMATCHED
            refreshData()
        }
        binding.btnReport.setOnClickListener {
            startActivity(Intent(this, ReportActivity::class.java))
        }
        binding.btnServer.setOnClickListener { showServerDialog() }

        // 提前加载模板库，让首次捕获不受磁盘读取延迟影响。
        CapturePipeline.engine(this)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        smsReader.start()
        refreshData()
        LedgerSync.enqueueFlush(this)
    }

    private fun showServerDialog() {
        val prefs = ServerPrefs(this)
        val url = EditText(this).apply {
            setText(prefs.baseUrl())
            hint = getString(R.string.hint_server_url)
        }
        val token = EditText(this).apply {
            setText(prefs.token())
            hint = getString(R.string.hint_server_token)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(url)
            addView(token)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.server_dialog_title)
            .setView(box)
            .setPositiveButton(R.string.action_save) { _, _ ->
                prefs.save(url.text.toString(), token.text.toString())
                toast(getString(R.string.toast_server_saved))
                LedgerSync.enqueueFlush(this)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                updatedReceiver,
                IntentFilter(CapturePipeline.ACTION_LEDGER_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(updatedReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        smsReader.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun requestSmsPermission() {
        smsPermissionLauncher.launch(Manifest.permission.READ_SMS)
    }

    private fun runBackfill() {
        if (!smsReader.hasPermission()) {
            requestSmsPermission()
            return
        }
        toast(getString(R.string.toast_backfill_start))
        scope.launch {
            val result = withContext(Dispatchers.IO) { smsReader.backfill(BACKFILL_DAYS) }
            toast(getString(R.string.toast_backfill_done, result.inserted, result.unmatched))
            refreshStatus()
            refreshData()
            LedgerSync.enqueueFlush(this@MainActivity)
        }
    }

    private fun refreshStatus() {
        val listenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)
        binding.tvNotifStatus.text = getString(
            if (listenerEnabled) R.string.status_notif_on else R.string.status_notif_off,
        )
        binding.tvSmsStatus.text = getString(
            if (smsReader.hasPermission()) R.string.status_sms_on else R.string.status_sms_off,
        )
    }

    private fun refreshData() {
        val filter = currentFilter
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val repository = LedgerRepository.get(this@MainActivity)
                Snapshot(
                    rows = buildRows(repository, filter),
                    counts = repository.counts(),
                    health = repository.health(),
                )
            }
            adapter.submit(snapshot.rows)
            binding.tvEmpty.visibility = if (snapshot.rows.isEmpty()) View.VISIBLE else View.GONE
            binding.tvCounts.text = getString(
                R.string.status_counts,
                snapshot.counts.txnCount,
                snapshot.counts.unmatchedCount,
            )
            binding.tvListTitle.setText(
                if (filter == RowFilter.ALL) R.string.filter_all_title else R.string.filter_unmatched_title,
            )
            binding.tvHealth.text = formatHealth(snapshot.health)
        }
    }

    private fun buildRows(repository: LedgerRepository, filter: RowFilter): List<LedgerRow> =
        when (filter) {
            RowFilter.ALL -> repository.recentTxns(ROW_LIMIT).map(::txnToRow)
            RowFilter.UNMATCHED -> repository.unmatched(ROW_LIMIT).map(::unmatchedToRow)
        }

    private fun txnToRow(row: TxnRow): LedgerRow {
        val head = buildString {
            append(formatTime(row.occurredAt))
            append(" · ")
            append(row.channel ?: row.source.wire)
            row.accountTail?.let { append(" 尾号").append(it) }
        }
        val detail = listOfNotNull(
            row.counterparty,
            row.memo,
            row.source.wire,
            row.templateId,
            String.format(Locale.US, "conf %.2f", row.confidence),
        ).joinToString(" · ")
        return LedgerRow(
            head = head,
            amount = amountText(row.kind, row.amountCents),
            detail = detail,
            kind = row.kind,
            unmatched = false,
        )
    }

    private fun unmatchedToRow(row: UnmatchedRow): LedgerRow = LedgerRow(
        head = "${formatTime(row.postedAt)} · ${row.origin} · ${row.source.wire}",
        amount = "?",
        detail = row.text.take(DETAIL_LIMIT),
        kind = TxnKind.UNKNOWN,
        unmatched = true,
    )

    private fun amountText(kind: TxnKind, cents: Long): String {
        val formatted = AmountParser.format(cents)
        return when (kind) {
            TxnKind.EXPENSE -> "-$formatted"
            TxnKind.INCOME, TxnKind.REFUND -> "+$formatted"
            TxnKind.TRANSFER -> "↔$formatted"
            TxnKind.UNKNOWN -> "?$formatted"
        }
    }

    private fun formatHealth(rows: List<HealthRow>): String {
        if (rows.isEmpty()) return getString(R.string.health_empty)
        return rows.joinToString("\n") { row ->
            "${row.origin} 命中${row.hitCount} 未识别${row.unmatchedCount} 最近${formatTime(row.lastHitAt)}"
        }
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(millis))

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** IO 线程算好、主线程一次性渲染的快照。 */
    private data class Snapshot(
        val rows: List<LedgerRow>,
        val counts: LedgerCounts,
        val health: List<HealthRow>,
    )

    private companion object {
        const val ROW_LIMIT = 200
        const val BACKFILL_DAYS = 7
        const val DETAIL_LIMIT = 160
    }
}
