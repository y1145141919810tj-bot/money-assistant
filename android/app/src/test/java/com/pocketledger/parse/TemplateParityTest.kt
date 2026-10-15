package com.pocketledger.parse

import com.pocketledger.core.CaptureSource
import com.pocketledger.core.ParseStatus
import com.pocketledger.core.TxnKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 解析一致性测试：跑 `templates/fixtures.json` 这份共用语料。
 *
 * 云端(Node)有一份等价的解析器。两边跑同一份 case，任何一边的结果不同就会红，
 * 这是防止设备端与云端解析行为漂移的唯一手段。模板库读的是 assets 副本，
 * 因此本测试同时验证了 `syncTemplates` 确实把单一真源同步进了 APK。
 */
class TemplateParityTest {

    private val fixtures: JSONObject by lazy {
        val candidates = listOf(
            File("../../templates/fixtures.json"),
            File("templates/fixtures.json"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("fixtures.json not found; cwd=${File(".").absolutePath}")
        JSONObject(file.readText())
    }

    private val engine: TemplateEngine by lazy {
        val candidates = listOf(
            File("src/main/assets/templates.json"),
            File("app/src/main/assets/templates.json"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("bundled templates.json not found; run :app:syncTemplates")
        TemplateEngine(TemplateSet.fromJson(file.readText()))
    }

    @Test
    fun `shared fixture corpus is present`() {
        val cases = fixtures.getJSONArray("cases")
        assertTrue("fixture corpus must not be empty", cases.length() >= 15)
    }

    @Test
    fun `every shared fixture parses as specified`() {
        val cases = fixtures.getJSONArray("cases")
        val failures = mutableListOf<String>()

        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val id = case.getString("id")
            val source = when (case.getString("source")) {
                "sms" -> CaptureSource.SMS
                "notif" -> CaptureSource.NOTIFICATION
                else -> error("fixture $id has unknown source")
            }
            val postedAt = case.getLong("postedAt")
            val title = if (case.has("title") && !case.isNull("title")) case.getString("title") else null
            val draft = engine.parse(
                source = source,
                origin = case.getString("origin"),
                title = title,
                body = case.getString("text"),
                postedAt = postedAt,
                capturedAt = postedAt,
            )
            val expect = case.getJSONObject("expect")

            check(failures, id, "status", ParseStatus.fromWire(expect.getString("status")), draft.status)
            if (expect.has("kind")) {
                check(failures, id, "kind", TxnKind.fromWire(expect.getString("kind")), draft.kind)
            }
            checkAmount(failures, id, expect, "amountCents", draft.amountCents)
            checkAmount(failures, id, expect, "balanceCents", draft.balanceCents)
            checkText(failures, id, expect, "accountTail", draft.accountTail)
            checkText(failures, id, expect, "channel", draft.channel)
            checkText(failures, id, expect, "memo", draft.memo)
            checkText(failures, id, expect, "templateId", draft.templateId)
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun check(
        failures: MutableList<String>,
        id: String,
        field: String,
        expected: Any?,
        actual: Any?,
    ) {
        if (expected != actual) failures.add("[$id] $field expected=<$expected> actual=<$actual>")
    }

    private fun checkAmount(
        failures: MutableList<String>,
        id: String,
        expect: JSONObject,
        field: String,
        actual: Long?,
    ) {
        if (!expect.has(field)) return
        if (expect.isNull(field)) {
            assertNull("[$id] $field should be null", actual)
            return
        }
        check(failures, id, field, expect.getLong(field), actual)
    }

    private fun checkText(
        failures: MutableList<String>,
        id: String,
        expect: JSONObject,
        field: String,
        actual: String?,
    ) {
        if (!expect.has(field)) return
        if (expect.isNull(field)) {
            assertNull("[$id] $field should be null", actual)
            return
        }
        check(failures, id, field, expect.getString(field), actual)
    }

    @Test
    fun `fixture identifiers are unique`() {
        val cases = fixtures.getJSONArray("cases")
        val seen = mutableSetOf<String>()
        for (index in 0 until cases.length()) {
            val id = cases.getJSONObject(index).getString("id")
            assertTrue("duplicate fixture id: $id", seen.add(id))
        }
        assertEquals(cases.length(), seen.size)
    }
}
