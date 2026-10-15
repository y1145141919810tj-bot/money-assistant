package com.pocketledger.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本地账本库。
 *
 * 金额列一律是以「分」为单位的整数。原始证据（raw_event）只追加、不修改、不删除，
 * 账目（txn）以 `source_key` 唯一约束保证幂等。
 */
class LedgerDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE raw_event (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source TEXT NOT NULL,
                source_key TEXT NOT NULL UNIQUE,
                origin TEXT NOT NULL,
                title TEXT,
                text TEXT,
                posted_at INTEGER NOT NULL,
                captured_at INTEGER NOT NULL,
                parse_status TEXT NOT NULL,
                template_id TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE txn (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source TEXT NOT NULL,
                source_key TEXT NOT NULL UNIQUE,
                kind TEXT NOT NULL,
                occurred_at INTEGER NOT NULL,
                amount_cents INTEGER NOT NULL,
                balance_cents INTEGER,
                account_tail TEXT,
                channel TEXT,
                counterparty TEXT,
                memo TEXT,
                template_id TEXT,
                confidence REAL NOT NULL,
                status TEXT NOT NULL DEFAULT 'normal',
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE source_health (
                origin TEXT PRIMARY KEY,
                hit_count INTEGER NOT NULL DEFAULT 0,
                last_hit_at INTEGER NOT NULL DEFAULT 0,
                unmatched_count INTEGER NOT NULL DEFAULT 0,
                last_unmatched_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_txn_time ON txn(occurred_at DESC)")
        db.execSQL("CREATE INDEX idx_raw_status ON raw_event(parse_status, posted_at DESC)")
        db.execSQL(
            """
            CREATE TABLE upload_queue (
                raw_event_id INTEGER PRIMARY KEY,
                enqueued_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    /**
     * 只追加迁移语句。直接重建表会丢失历史账目。
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS upload_queue (
                    raw_event_id INTEGER PRIMARY KEY,
                    enqueued_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            // 升级前已经落库的证据也要补传，否则只有更新之后的新流水会进电脑。
            db.execSQL(
                """
                INSERT INTO upload_queue (raw_event_id, enqueued_at)
                SELECT id, captured_at FROM raw_event
                """.trimIndent(),
            )
        }
    }

    companion object {
        const val DB_NAME = "ledger.db"

        /** 单调递增。任何结构变更都必须同时提升此值并补上迁移语句。 */
        const val DB_VERSION = 2
    }
}
