package com.pocketledger.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.pocketledger.core.CaptureSource
import com.pocketledger.core.Draft
import com.pocketledger.core.ParseStatus
import com.pocketledger.core.TxnKind

/**
 * 账本存储。所有写入都必须经过 [ingest]，它是唯一保证幂等与不丢证据的入口。
 */
class LedgerRepository private constructor(context: Context) {

    private val helper = LedgerDb(context)

    /**
     * 写入一条候选记录。
     *
     * 判定为 [ParseStatus.NOT_FLOW] 或 [ParseStatus.REJECTED] 的条目直接丢弃，不留痕。
     * 其余条目先落原始证据；`source_key` 冲突即视为重复，不再写账目。
     *
     * @param draft 解析结果。
     * @returns 本次入库的实际结果。
     */
    fun ingest(draft: Draft): IngestOutcome {
        if (draft.status == ParseStatus.NOT_FLOW || draft.status == ParseStatus.REJECTED) {
            return IngestOutcome.DROPPED
        }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val rawId = db.insertWithOnConflict(
                "raw_event",
                null,
                rawValues(draft),
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            if (rawId == -1L) return IngestOutcome.DUPLICATE
            if (draft.status == ParseStatus.PARSED) {
                db.insertWithOnConflict("txn", null, txnValues(draft), SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.insert("upload_queue", null, ContentValues().apply {
                put("raw_event_id", rawId)
                put("enqueued_at", draft.capturedAt)
            })
            bumpSourceHealth(db, draft)
            db.setTransactionSuccessful()
            return if (draft.status == ParseStatus.PARSED) IngestOutcome.INSERTED else IngestOutcome.UNMATCHED
        } finally {
            db.endTransaction()
        }
    }

    /**
     * @param limit 最大返回条数。
     * @returns 按发生时间倒序的账目。
     */
    fun recentTxns(limit: Int): List<TxnRow> {
        val rows = mutableListOf<TxnRow>()
        helper.readableDatabase.query(
            "txn",
            arrayOf(
                "occurred_at", "kind", "amount_cents", "channel", "account_tail",
                "counterparty", "memo", "source", "template_id", "confidence",
            ),
            null,
            null,
            null,
            null,
            "occurred_at DESC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    TxnRow(
                        occurredAt = cursor.getLong(0),
                        kind = TxnKind.fromWire(cursor.getString(1)),
                        amountCents = cursor.getLong(2),
                        channel = cursor.getString(3),
                        accountTail = cursor.getString(4),
                        counterparty = cursor.getString(5),
                        memo = cursor.getString(6),
                        source = CaptureSource.fromWire(cursor.getString(7)),
                        templateId = cursor.getString(8),
                        confidence = cursor.getDouble(9),
                    ),
                )
            }
        }
        return rows
    }

    /**
     * @param limit 最大返回条数。
     * @returns 按发生时间倒序的未识别条目。
     */
    fun unmatched(limit: Int): List<UnmatchedRow> {
        val rows = mutableListOf<UnmatchedRow>()
        helper.readableDatabase.query(
            "raw_event",
            arrayOf("posted_at", "source", "origin", "text"),
            "parse_status = ?",
            arrayOf(ParseStatus.UNMATCHED.wire),
            null,
            null,
            "posted_at DESC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    UnmatchedRow(
                        postedAt = cursor.getLong(0),
                        source = CaptureSource.fromWire(cursor.getString(1)),
                        origin = cursor.getString(2),
                        text = cursor.getString(3).orEmpty(),
                    ),
                )
            }
        }
        return rows
    }

    /** @returns 账目数、未识别数与原始事件数。 */
    fun counts(): LedgerCounts {
        val db = helper.readableDatabase
        return LedgerCounts(
            txnCount = scalar(db, "SELECT COUNT(*) FROM txn"),
            unmatchedCount = scalar(db, "SELECT COUNT(*) FROM raw_event WHERE parse_status = ?", arrayOf(ParseStatus.UNMATCHED.wire)),
            parsedEventCount = scalar(db, "SELECT COUNT(*) FROM raw_event"),
        )
    }

    /** @returns 各来源健康度，按最近命中时间倒序。 */
    fun health(): List<HealthRow> {
        val rows = mutableListOf<HealthRow>()
        helper.readableDatabase.query(
            "source_health",
            arrayOf("origin", "hit_count", "last_hit_at", "unmatched_count", "last_unmatched_at"),
            null,
            null,
            null,
            null,
            "last_hit_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    HealthRow(
                        origin = cursor.getString(0),
                        hitCount = cursor.getInt(1),
                        lastHitAt = cursor.getLong(2),
                        unmatchedCount = cursor.getInt(3),
                        lastUnmatchedAt = cursor.getLong(4),
                    ),
                )
            }
        }
        return rows
    }

    /**
     * @param limit 单次最多取出的条数。服务端同样拒绝超过 200 条的批次。
     * @returns 尚未确认上传的原始事件，按发生时间正序。
     */
    fun pendingEvents(limit: Int): List<PendingEvent> {
        val rows = mutableListOf<PendingEvent>()
        helper.readableDatabase.rawQuery(
            """
            SELECT r.id, r.source, r.source_key, r.origin, r.title, r.text, r.posted_at
              FROM upload_queue q
              JOIN raw_event r ON r.id = q.raw_event_id
             ORDER BY r.posted_at ASC
             LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    PendingEvent(
                        id = cursor.getLong(0),
                        source = cursor.getString(1),
                        sourceKey = cursor.getString(2),
                        origin = cursor.getString(3),
                        title = if (cursor.isNull(4)) null else cursor.getString(4),
                        text = cursor.getString(5).orEmpty(),
                        postedAt = cursor.getLong(6),
                    ),
                )
            }
        }
        return rows
    }

    /** @returns 还没送到电脑上的条数。 */
    fun pendingUploadCount(): Int =
        scalar(helper.readableDatabase, "SELECT COUNT(*) FROM upload_queue")

    /**
     * 电脑已确认这批事件（含幂等重复）。只删队列，不改原始证据。
     *
     * @param ids [PendingEvent.id]，即 raw_event 主键。
     */
    fun ackUploaded(ids: List<Long>) {
        if (ids.isEmpty()) return
        val placeholders = ids.joinToString(",") { "?" }
        helper.writableDatabase.execSQL(
            "DELETE FROM upload_queue WHERE raw_event_id IN ($placeholders)",
            ids.map { it as Any }.toTypedArray(),
        )
    }

    /** 清空全部本地数据。仅用于开发期验证模板，正式使用不应调用。 */
    fun clearAll() {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("upload_queue", null, null)
            db.delete("txn", null, null)
            db.delete("raw_event", null, null)
            db.delete("source_health", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun rawValues(draft: Draft) = ContentValues().apply {
        put("source", draft.source.wire)
        put("source_key", draft.sourceKey)
        put("origin", draft.origin)
        put("title", draft.title)
        put("text", draft.text)
        put("posted_at", draft.postedAt)
        put("captured_at", draft.capturedAt)
        put("parse_status", draft.status.wire)
        put("template_id", draft.templateId)
    }

    private fun txnValues(draft: Draft) = ContentValues().apply {
        put("source", draft.source.wire)
        put("source_key", draft.sourceKey)
        put("kind", draft.kind.wire)
        put("occurred_at", draft.postedAt)
        put("amount_cents", draft.amountCents)
        put("balance_cents", draft.balanceCents)
        put("account_tail", draft.accountTail)
        put("channel", draft.channel)
        put("counterparty", draft.counterparty)
        put("memo", draft.memo)
        put("template_id", draft.templateId)
        put("confidence", draft.confidence)
        put("status", "normal")
        put("created_at", draft.capturedAt)
    }

    /**
     * 累加来源健康度。
     *
     * 刻意不用 `ON CONFLICT DO UPDATE`：Android 24 自带 SQLite 3.9 不支持 upsert 语法，
     * 用「先 INSERT OR IGNORE 再 UPDATE」在所有目标版本上都安全。
     */
    private fun bumpSourceHealth(db: SQLiteDatabase, draft: Draft) {
        db.execSQL(
            "INSERT OR IGNORE INTO source_health(origin, hit_count, last_hit_at, unmatched_count, last_unmatched_at) " +
                "VALUES(?, 0, 0, 0, 0)",
            arrayOf(draft.origin),
        )
        val unmatched = if (draft.status == ParseStatus.UNMATCHED) 1 else 0
        db.execSQL(
            "UPDATE source_health SET hit_count = hit_count + 1, last_hit_at = ?, " +
                "unmatched_count = unmatched_count + ?, " +
                "last_unmatched_at = CASE WHEN ? = 1 THEN ? ELSE last_unmatched_at END " +
                "WHERE origin = ?",
            arrayOf<Any?>(draft.postedAt, unmatched, unmatched, draft.postedAt, draft.origin),
        )
    }

    private fun scalar(db: SQLiteDatabase, sql: String, args: Array<String>? = null): Int =
        db.rawQuery(sql, args).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    companion object {
        @Volatile
        private var instance: LedgerRepository? = null

        /**
         * @param context 任意 Context，内部只保留 applicationContext。
         * @returns 进程内单例，避免通知回调与 UI 各自打开一份数据库句柄。
         */
        fun get(context: Context): LedgerRepository = instance ?: synchronized(this) {
            instance ?: LedgerRepository(context.applicationContext).also { instance = it }
        }
    }
}
