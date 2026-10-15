package com.pocketledger.capture

import android.content.Context
import android.content.Intent
import android.util.Log
import com.pocketledger.core.CaptureSource
import com.pocketledger.core.Draft
import com.pocketledger.data.IngestOutcome
import com.pocketledger.data.LedgerRepository
import com.pocketledger.parse.OriginRegistry
import com.pocketledger.sync.LedgerSync
import com.pocketledger.parse.TemplateEngine
import com.pocketledger.parse.TemplateSet
import com.pocketledger.parse.TextNormalizer
import java.io.File
import java.util.concurrent.Executors

/**
 * 采集入口。通知监听服务、短信观察者、以及后续的上报队列都从这里进。
 *
 * 写入放在单线程执行器上：通知回调运行在 binder 线程，不应在其中做磁盘 I/O。
 */
object CapturePipeline {

    /** 账本更新广播，仅用于 UI 实时刷新（就地验证抓取是否生效）。 */
    const val ACTION_LEDGER_UPDATED = "com.pocketledger.action.LEDGER_UPDATED"

    private const val TAG = "PocketLedger"

    /**
     * 云端热更新落点：P1 阶段把下发的模板写到这里即可覆盖内置模板，无需发版。
     */
    private const val OVERRIDE_TEMPLATE_FILE = "templates.json"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pocketledger-ingest").apply { isDaemon = true }
    }

    @Volatile
    private var engine: TemplateEngine? = null

    /**
     * 处理一条通知。
     *
     * @param context 任意 Context。
     * @param packageName 通知包名。
     * @param postedAt `StatusBarNotification.postTime`。
     * @param title 通知标题。
     * @param body 通知正文（已合并 bigText/textLines/subText）。
     */
    fun onNotification(
        context: Context,
        packageName: String,
        postedAt: Long,
        title: String?,
        body: String?,
    ) {
        val appContext = context.applicationContext
        val joined = listOfNotNull(title, body).joinToString(" ")
        val normalized = TextNormalizer.normalize(joined)
        if (normalized.isBlank()) return
        if (!OriginRegistry.shouldInspect(packageName, normalized)) return
        val draft = engine(appContext).parse(
            source = CaptureSource.NOTIFICATION,
            origin = packageName,
            title = title,
            body = body.orEmpty(),
            postedAt = postedAt,
            capturedAt = System.currentTimeMillis(),
        )
        ingestAsync(appContext, draft)
    }

    /**
     * 处理一条短信。
     *
     * @param context 任意 Context。
     * @param address 发件号码。
     * @param date 短信时间戳，毫秒 UTC。
     * @param body 短信正文。
     */
    fun onSms(context: Context, address: String, date: Long, body: String) {
        val appContext = context.applicationContext
        val draft = engine(appContext).parse(
            source = CaptureSource.SMS,
            origin = address,
            title = null,
            body = body,
            postedAt = date,
            capturedAt = System.currentTimeMillis(),
        )
        ingestAsync(appContext, draft)
    }

    /**
     * 同步入库。仅用于短信回补等已知批量的场景，需要立刻拿到计数。
     *
     * @param context 任意 Context。
     * @param draft 解析结果。
     * @returns 入库结果。
     */
    fun ingestSync(context: Context, draft: Draft): IngestOutcome =
        LedgerRepository.get(context.applicationContext).ingest(draft)

    /**
     * @param context 任意 Context。
     * @returns 解析引擎，首次调用时从磁盘或 assets 加载模板库。
     */
    @Synchronized
    fun engine(context: Context): TemplateEngine {
        engine?.let { return it }
        val appContext = context.applicationContext
        val override = File(appContext.filesDir, OVERRIDE_TEMPLATE_FILE)
        val json = if (override.isFile) {
            override.readText()
        } else {
            appContext.assets.open(OVERRIDE_TEMPLATE_FILE).bufferedReader().use { it.readText() }
        }
        val built = try {
            TemplateEngine(TemplateSet.fromJson(json))
        } catch (e: Exception) {
            // 模板库损坏时必须回退到内置 assets，否则整条采集链路会静默失效。
            Log.e(TAG, "template set invalid, falling back to assets", e)
            val bundled = appContext.assets.open(OVERRIDE_TEMPLATE_FILE).bufferedReader().use { it.readText() }
            TemplateEngine(TemplateSet.fromJson(bundled))
        }
        Log.i(TAG, "template set loaded: version=${built.version}")
        engine = built
        return built
    }

    private fun ingestAsync(context: Context, draft: Draft) {
        executor.execute {
            val outcome = LedgerRepository.get(context).ingest(draft)
            if (outcome == IngestOutcome.INSERTED || outcome == IngestOutcome.UNMATCHED) {
                context.sendBroadcast(
                    Intent(ACTION_LEDGER_UPDATED).setPackage(context.packageName),
                )
                LedgerSync.enqueueFlush(context)
            }
        }
    }
}
