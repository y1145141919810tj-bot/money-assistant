package com.pocketledger.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 通知监听服务。这是后台自动捕获微信、支付宝、银行推送的唯一可靠入口。
 *
 * 该服务由系统绑定，比普通前台服务抗杀；但通知使用权由用户在系统设置中手动授予，
 * 可能在系统更新、清理缓存或换机后丢失，因此 [com.pocketledger.ui.MainActivity] 会定期自检。
 */
class LedgerNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val body = collectBody(extras)
        if (title.isNullOrBlank() && body.isBlank()) return
        CapturePipeline.onNotification(
            context = this,
            packageName = sbn.packageName,
            postedAt = sbn.postTime,
            title = title,
            body = body,
        )
    }

    /**
     * 聚合通知正文的多个来源。不同 App 把正文放在不同 extra 中，
     * 只读 `EXTRA_TEXT` 会漏掉大量银行与支付通知。
     */
    private fun collectBody(extras: android.os.Bundle): String {
        val parts = mutableListOf<String>()
        for (key in BODY_KEYS) {
            extras.getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }?.let(parts::add)
        }
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { line ->
            line?.toString()?.takeIf { it.isNotBlank() }?.let(parts::add)
        }
        return parts.distinct().joinToString(" | ")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "notification listener connected")
    }

    private companion object {
        const val TAG = "PocketLedger"

        val BODY_KEYS = listOf(
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,
            Notification.EXTRA_INFO_TEXT,
        )
    }
}
