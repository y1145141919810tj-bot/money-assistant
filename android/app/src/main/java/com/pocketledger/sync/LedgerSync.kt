package com.pocketledger.sync

import android.content.Context
import android.util.Log
import com.pocketledger.data.LedgerRepository
import com.pocketledger.data.PendingEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 把本地队列送到家里的电脑，并取回月报。
 *
 * 网络失败时队列保留，下次打开 App 会再送。电脑对同一条 `source_key` 只入账一次。
 */
object LedgerSync {

    private const val TAG = "PocketLedger"
    private const val BATCH = 200

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pocketledger-sync").apply { isDaemon = true }
    }

    /** 后台补传。未配置服务器或网络失败时只记日志，不打断采集。 */
    fun enqueueFlush(context: Context) {
        val app = context.applicationContext
        if (!ServerPrefs(app).isConfigured()) return
        executor.execute {
            try {
                flush(app)
            } catch (e: Exception) {
                Log.w(TAG, "upload failed", e)
            }
        }
    }

    /**
     * 同步上传队列并打一次心跳。
     *
     * @returns 本次确认上传的条数。
     * @throws IllegalStateException 还没填写电脑地址或令牌。
     * @throws IOException 电脑拒绝或网络不可达。
     */
    fun flush(context: Context): Int {
        val prefs = ServerPrefs(context)
        if (!prefs.isConfigured()) throw IllegalStateException("server not configured")
        val repository = LedgerRepository.get(context)
        var uploaded = 0
        var rounds = 0
        while (rounds < 20) {
            val batch = repository.pendingEvents(BATCH)
            if (batch.isEmpty()) break
            postJson(prefs, "/v1/events:batch", eventsBody(batch))
            repository.ackUploaded(batch.map { it.id })
            uploaded += batch.size
            rounds += 1
            if (batch.size < BATCH) break
        }
        try {
            postJson(prefs, "/v1/heartbeat", heartbeatBody(context, repository.pendingUploadCount()))
        } catch (e: IOException) {
            Log.w(TAG, "heartbeat failed", e)
        }
        return uploaded
    }

    /**
     * @param month `YYYY-MM`。
     * @returns 月报 JSON：totals、byCategory、byDay、recent。
     */
    fun fetchMonth(context: Context, month: String): JSONObject {
        val prefs = ServerPrefs(context)
        if (!prefs.isConfigured()) throw IllegalStateException("server not configured")
        return JSONObject(get(prefs, "/v1/report/monthly?month=$month"))
    }

    private fun eventsBody(events: List<PendingEvent>): String {
        val array = JSONArray()
        for (event in events) {
            // 本地 text 已包含标题。再传 title 会让服务端拼接第二次，幂等键就会对不上。
            array.put(
                JSONObject()
                    .put("source", event.source)
                    .put("origin", event.origin)
                    .put("text", event.text)
                    .put("posted_at", event.postedAt)
                    .put("source_key", event.sourceKey),
            )
        }
        return JSONObject().put("events", array).toString()
    }

    private fun heartbeatBody(context: Context, pending: Int): String {
        val listenerOn = androidx.core.app.NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName)
        val smsOn = context.checkSelfPermission(android.Manifest.permission.READ_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        return JSONObject()
            .put("notification_ok", listenerOn)
            .put("sms_ok", smsOn)
            .put("pending_upload", pending)
            .toString()
    }

    private fun postJson(prefs: ServerPrefs, path: String, json: String): String {
        val connection = open(prefs, path, "POST")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return read(connection)
    }

    private fun get(prefs: ServerPrefs, path: String): String = read(open(prefs, path, "GET"))

    private fun open(prefs: ServerPrefs, path: String, method: String): HttpURLConnection {
        val connection = URL(prefs.baseUrl() + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 8_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Authorization", "Bearer ${prefs.token()}")
        connection.setRequestProperty("Accept", "application/json")
        return connection
    }

    private fun read(connection: HttpURLConnection): String {
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code ${body.take(180)}")
            return body
        } finally {
            connection.disconnect()
        }
    }
}
