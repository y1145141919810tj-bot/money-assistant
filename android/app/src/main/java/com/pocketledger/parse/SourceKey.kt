package com.pocketledger.parse

import com.pocketledger.core.CaptureSource
import java.security.MessageDigest

/**
 * 幂等键构造。
 *
 * 断网重传、App 重装、云端重放都必须只产生一条账目，全部依赖这里的键稳定且唯一。
 * 键一旦变更，历史数据会被重复记账，所以格式变更需要配套的数据迁移。
 */
object SourceKey {

    /**
     * @param source 捕获来源。
     * @param origin 通知包名或短信发件号码。
     * @param postedAt 事件发生时间，毫秒 UTC。
     * @param normalized 已归一化的文本（用原文会因标点差异导致同一笔产生两个键）。
     * @returns 形如 `notif:com.tencent.mm:1730000000000:<sha1>` 的幂等键。
     */
    fun of(source: CaptureSource, origin: String, postedAt: Long, normalized: String): String =
        "${source.wire}:$origin:$postedAt:${sha1(normalized)}"

    /**
     * @param text 任意文本。
     * @returns 小写十六进制 SHA-1 摘要。
     */
    fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
