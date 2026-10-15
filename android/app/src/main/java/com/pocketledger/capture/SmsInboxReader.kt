package com.pocketledger.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import com.pocketledger.core.CaptureSource
import com.pocketledger.data.IngestOutcome

/**
 * 短信收件箱采集。
 *
 * 用 `ContentObserver` 而不是 `SMS_RECEIVED` 广播：Android 4.4 起该广播只投递给默认短信 App，
 * 观察收件箱内容提供者才是稳定路径。
 *
 * 首次启动只建立基线（记下当前最大 `_id`），不回补历史——否则换机或重装会一次性灌入数年短信。
 * 历史回补由用户显式触发 [backfill]。
 */
class SmsInboxReader(private val context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var observer: ContentObserver? = null
    private var worker: HandlerThread? = null

    /** @returns 是否已获得读取短信权限。 */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /** 开始监听新短信。未获得权限时静默返回，权限授予后由调用方再次调用。 */
    fun start() {
        if (observer != null || !hasPermission()) return
        val thread = HandlerThread("pocketledger-sms").apply { start() }
        worker = thread
        val contentObserver = object : ContentObserver(Handler(thread.looper)) {
            override fun onChange(selfChange: Boolean) {
                readNew()
            }
        }
        observer = contentObserver
        runCatching { appContext.contentResolver.registerContentObserver(INBOX_URI, true, contentObserver) }
            .onFailure { Log.w(TAG, "register sms observer failed", it) }
        if (!prefs.contains(KEY_LAST_ID)) baseline()
        readNew()
    }

    /** 停止监听并释放工作线程。 */
    fun stop() {
        observer?.let { contentObserver ->
            runCatching { appContext.contentResolver.unregisterContentObserver(contentObserver) }
                .onFailure { Log.w(TAG, "unregister sms observer failed", it) }
        }
        observer = null
        worker?.quitSafely()
        worker = null
    }

    /**
     * 把基线设为当前收件箱最大 `_id`，不导入任何历史短信。
     *
     * @returns 基线 `_id`；读取失败时返回 0。
     */
    fun baseline(): Long {
        val maxId = queryMaxId() ?: return 0L
        prefs.edit().putLong(KEY_LAST_ID, maxId).apply()
        return maxId
    }

    /**
     * 回补最近若干天短信。用于首次部署时验证模板库是否命中。
     *
     * @param days 回补天数。
     * @returns 扫描、新增、未识别与重复的条数。
     */
    fun backfill(days: Int): BackfillResult {
        if (!hasPermission()) return BackfillResult(0, 0, 0, 0)
        val since = System.currentTimeMillis() - days * DAY_MS
        var scanned = 0
        var inserted = 0
        var unmatched = 0
        var duplicate = 0
        try {
            appContext.contentResolver.query(
                INBOX_URI,
                COLUMNS,
                "date >= ?",
                arrayOf(since.toString()),
                "date ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    scanned++
                    when (ingestRow(cursor.getLong(0), cursor.getString(1), cursor.getLong(3), cursor.getString(2))) {
                        IngestOutcome.INSERTED -> inserted++
                        IngestOutcome.UNMATCHED -> unmatched++
                        IngestOutcome.DUPLICATE -> duplicate++
                        IngestOutcome.DROPPED -> Unit
                    }
                    rememberId(cursor.getLong(0))
                }
            }
        } catch (e: SecurityException) {
            // 权限在读取途中被撤销，返回已统计的部分结果。
            Log.w(TAG, "sms backfill denied mid-read", e)
        }
        return BackfillResult(scanned, inserted, unmatched, duplicate)
    }

    private fun readNew() {
        if (!hasPermission()) return
        val lastId = prefs.getLong(KEY_LAST_ID, 0L)
        try {
            appContext.contentResolver.query(
                INBOX_URI,
                COLUMNS,
                "_id > ?",
                arrayOf(lastId.toString()),
                "_id ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    ingestRow(cursor.getLong(0), cursor.getString(1), cursor.getLong(3), cursor.getString(2))
                    rememberId(cursor.getLong(0))
                }
            }
        } catch (e: SecurityException) {
            // 权限被撤销；由 UI 的状态面板提示用户重新授权。
            Log.w(TAG, "sms read denied", e)
        }
    }

    private fun ingestRow(id: Long, address: String?, date: Long, body: String?): IngestOutcome {
        val draft = CapturePipeline.engine(appContext).parse(
            source = CaptureSource.SMS,
            origin = address.orEmpty(),
            title = null,
            body = body.orEmpty(),
            postedAt = date,
            capturedAt = System.currentTimeMillis(),
        )
        val outcome = CapturePipeline.ingestSync(appContext, draft)
        Log.d(TAG, "sms $id outcome=$outcome template=${draft.templateId}")
        return outcome
    }

    private fun rememberId(id: Long) {
        if (id > prefs.getLong(KEY_LAST_ID, 0L)) {
            prefs.edit().putLong(KEY_LAST_ID, id).apply()
        }
    }

    private fun queryMaxId(): Long? = try {
        appContext.contentResolver.query(INBOX_URI, arrayOf("_id"), null, null, "_id DESC LIMIT 1")
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
    } catch (e: SecurityException) {
        // 无权限时无基线可建，交由 UI 提示授权。
        Log.w(TAG, "sms baseline denied", e)
        null
    }

    /**
     * 回补结果。
     *
     * @property scanned 扫描到的短信条数。
     * @property inserted 新增账目数。
     * @property unmatched 未命中模板的条数（补模板的依据）。
     * @property duplicate 幂等键已存在而被跳过的条数。
     */
    data class BackfillResult(
        val scanned: Int,
        val inserted: Int,
        val unmatched: Int,
        val duplicate: Int,
    )

    private companion object {
        const val TAG = "PocketLedger"
        const val PREFS = "pocketledger_sms"
        const val KEY_LAST_ID = "last_sms_id"
        const val DAY_MS = 24L * 60 * 60 * 1000

        val INBOX_URI: Uri = Uri.parse("content://sms/inbox")
        val COLUMNS = arrayOf("_id", "address", "body", "date")
    }
}
