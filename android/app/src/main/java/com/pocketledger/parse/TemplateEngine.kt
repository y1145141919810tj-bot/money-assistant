package com.pocketledger.parse

import com.pocketledger.core.CaptureSource
import com.pocketledger.core.Draft
import com.pocketledger.core.ParseStatus
import com.pocketledger.core.TxnKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条解析模板：某种已知的短信或通知文案结构。
 *
 * 模板必须可热更新。银行改一次文案就重新发版是不可接受的，所以模板集合从
 * `assets/templates.json`（或云端下发的同名文件）加载，而不是编译进代码。
 */
class Template(
    val id: String,
    val note: String,
    val senders: List<String>,
    val packages: List<String>,
    private val regex: Regex,
    val accountTailGroup: Int?,
    val directionGroup: Int,
    val amountGroup: Int,
    val balanceGroup: Int?,
    val memoGroup: Int?,
    val counterpartyGroup: Int?,
    val channel: String,
    val confidence: Double,
) {
    /**
     * @param source 捕获来源。
     * @param origin 通知包名或短信发件号码。
     * @returns 该模板是否适用于此来源。通配发件号码额外要求号码像银行服务号，
     *   否则个人短信会被通用模板误判成流水。
     */
    fun appliesTo(source: CaptureSource, origin: String): Boolean = when (source) {
        CaptureSource.SMS ->
            senders.any { OriginRegistry.senderMatches(origin, it) } &&
                (!senders.contains("*") || OriginRegistry.looksLikeBankSender(origin))
        CaptureSource.NOTIFICATION -> packages.any { OriginRegistry.packageMatches(origin, it) }
        else -> false
    }

    /**
     * @param normalized 已归一化文本。
     * @returns 首个匹配结果，未命中返回 null。
     */
    fun find(normalized: String): MatchResult? = regex.find(normalized)
}

/**
 * 模板集合：模板列表 + 收支动词映射 + 包名到渠道名的映射。
 *
 * @property version 模板库版本号，随云端下发递增。
 */
class TemplateSet(
    val version: Int,
    val templates: List<Template>,
    val directionMap: Map<String, TxnKind>,
    val packageChannels: Map<String, String>,
    val senderChannels: Map<String, String>,
) {
    /**
     * @param packageName 通知包名。
     * @returns 该包名对应的渠道名，未登记返回 null。
     */
    fun channelForPackage(packageName: String): String? =
        packageChannels.entries.firstOrNull { OriginRegistry.packageMatches(packageName, it.key) }?.value

    /**
     * @param address 短信发件号码。
     * @returns 该号码对应的银行名，未登记返回 null。
     */
    fun channelForSender(address: String): String? =
        senderChannels.entries.firstOrNull { OriginRegistry.senderMatches(address, it.key) }?.value

    companion object {
        /**
         * @param json 模板库 JSON 文本。
         * @returns 解析后的模板集合。
         * @throws org.json.JSONException JSON 结构非法时抛出，由调用方决定降级策略。
         */
        fun fromJson(json: String): TemplateSet {
            val root = JSONObject(json)
            val directionMap = mutableMapOf<String, TxnKind>()
            for ((key, value) in root.optJSONObject("directionMap").stringEntries()) {
                directionMap[key] = TxnKind.fromWire(value)
            }
            val packageChannels = mutableMapOf<String, String>()
            for ((key, value) in root.optJSONObject("packageChannels").stringEntries()) {
                packageChannels[key] = value
            }
            val senderChannels = mutableMapOf<String, String>()
            for ((key, value) in root.optJSONObject("senderChannels").stringEntries()) {
                senderChannels[key] = value
            }
            val templates = mutableListOf<Template>()
            val array = root.optJSONArray("templates") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val extract = item.optJSONObject("extract") ?: JSONObject()
                templates.add(
                    Template(
                        id = item.getString("id"),
                        note = item.optString("note"),
                        senders = item.optJSONArray("senders").toStringList(),
                        packages = item.optJSONArray("packages").toStringList(),
                        regex = Regex(item.getString("regex")),
                        accountTailGroup = extract.optGroupIndex("accountTailGroup"),
                        directionGroup = extract.optInt("directionGroup", -1),
                        amountGroup = extract.optInt("amountGroup", -1),
                        balanceGroup = extract.optGroupIndex("balanceGroup"),
                        memoGroup = extract.optGroupIndex("memoGroup"),
                        counterpartyGroup = extract.optGroupIndex("counterpartyGroup"),
                        channel = item.optString("channel"),
                        confidence = item.optDouble("confidence", 0.8),
                    ),
                )
            }
            return TemplateSet(
                version = root.optInt("version", 1),
                templates = templates,
                directionMap = directionMap,
                packageChannels = packageChannels,
                senderChannels = senderChannels,
            )
        }
    }
}

/** @returns 数组的字符串列表；数组缺失时返回空列表。 */
private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { getString(it) }
}

/** @returns 捕获组下标；字段缺失或为负时返回 null。 */
private fun JSONObject.optGroupIndex(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    val value = optInt(key, -1)
    return if (value < 0) null else value
}

/**
 * @returns 对象的全部字符串条目；对象缺失时返回空 Map。
 *
 * 刻意用 `names()` 而不是 `keySet()`：后者的泛型返回签名在本工程的 Kotlin 解析下不可用，
 * 而 `names()` 只返回 JSONArray，按索引取值即可。
 */
private fun JSONObject?.stringEntries(): Map<String, String> {
    if (this == null) return emptyMap()
    val entries = mutableMapOf<String, String>()
    val names = names() ?: return entries
    for (index in 0 until names.length()) {
        val key = names.getString(index)
        entries[key] = getString(key)
    }
    return entries
}

/**
 * 解析引擎：把通知/短信文本变成一条候选账目。
 *
 * 顺序是刻意的——隐私闸门 → 预筛 → 模板。任何未命中模板的条目都不会被静默丢弃：
 * 像流水的会以 `unmatched` 落库，用来补齐模板库。
 */
class TemplateEngine(private val set: TemplateSet) {

    /** @returns 模板库版本号。 */
    val version: Int get() = set.version

    /**
     * @param source 捕获来源。
     * @param origin 通知包名或短信发件号码。
     * @param title 通知标题，短信传 null。
     * @param body 通知正文或短信正文。
     * @param postedAt 事件发生时间，毫秒 UTC。
     * @param capturedAt 本机收到时间，毫秒 UTC。
     * @returns 候选账目；`status` 为 [ParseStatus.NOT_FLOW] 或 [ParseStatus.REJECTED] 时调用方不得落库。
     */
    fun parse(
        source: CaptureSource,
        origin: String,
        title: String?,
        body: String,
        postedAt: Long,
        capturedAt: Long,
    ): Draft {
        val joined = listOfNotNull(title?.takeIf { it.isNotBlank() }, body.takeIf { it.isNotBlank() })
            .joinToString(" ")
        val text = TextNormalizer.normalize(joined)
        val sourceKey = SourceKey.of(source, origin, postedAt, text)

        if (PiiFilter.isSensitive(text)) {
            return blank(source, sourceKey, origin, title, text, postedAt, capturedAt, ParseStatus.REJECTED)
        }

        for (template in set.templates) {
            if (!template.appliesTo(source, origin)) continue
            val match = template.find(text) ?: continue
            val amount = AmountParser.toCents(match.groupOrNull(template.amountGroup)) ?: continue
            val directionWord = match.groupOrNull(template.directionGroup).orEmpty()
            val kind = set.directionMap[directionWord] ?: TxnKind.UNKNOWN
            val channel = resolveChannel(template.channel, source, origin)
            return Draft(
                source = source,
                sourceKey = sourceKey,
                origin = origin,
                title = title,
                text = text,
                postedAt = postedAt,
                capturedAt = capturedAt,
                kind = kind,
                amountCents = amount,
                balanceCents = AmountParser.toCents(match.groupOrNull(template.balanceGroup)),
                accountTail = match.groupOrNull(template.accountTailGroup),
                channel = channel,
                counterparty = match.groupOrNull(template.counterpartyGroup),
                memo = match.groupOrNull(template.memoGroup),
                templateId = template.id,
                confidence = template.confidence,
                status = ParseStatus.PARSED,
            )
        }

        val status = when {
            !Prefilter.looksLikeFlow(text) -> ParseStatus.NOT_FLOW
            source == CaptureSource.SMS && OriginRegistry.looksLikeBankSender(origin) -> ParseStatus.UNMATCHED
            source == CaptureSource.NOTIFICATION && OriginRegistry.isPaymentPackage(origin) -> ParseStatus.UNMATCHED
            else -> ParseStatus.NOT_FLOW
        }
        return blank(source, sourceKey, origin, title, text, postedAt, capturedAt, status)
    }

    /**
     * @param templateChannel 模板登记的渠道名，可能为空。
     * @param source 捕获来源。
     * @param origin 通知包名或短信发件号码。
     * @returns 渠道名。短信在模板与发件号码都未登记时回退为「银行」，避免账目失去归类依据。
     */
    private fun resolveChannel(templateChannel: String, source: CaptureSource, origin: String): String? =
        templateChannel.ifBlank {
            set.channelForSender(origin)
                ?: set.channelForPackage(origin)
                ?: if (source == CaptureSource.SMS) "银行" else null
        }

    private fun blank(
        source: CaptureSource,
        sourceKey: String,
        origin: String,
        title: String?,
        text: String,
        postedAt: Long,
        capturedAt: Long,
        status: ParseStatus,
    ) = Draft(
        source = source,
        sourceKey = sourceKey,
        origin = origin,
        title = title,
        text = text,
        postedAt = postedAt,
        capturedAt = capturedAt,
        kind = TxnKind.UNKNOWN,
        amountCents = null,
        balanceCents = null,
        accountTail = null,
        channel = resolveChannel("", source, origin),
        counterparty = null,
        memo = null,
        templateId = null,
        confidence = 0.0,
        status = status,
    )
}

/** @returns 指定捕获组的值；下标非法或捕获组为空时返回 null。 */
private fun MatchResult.groupOrNull(index: Int?): String? {
    if (index == null || index < 0) return null
    return groupValues.getOrNull(index)?.takeIf { it.isNotBlank() }
}
