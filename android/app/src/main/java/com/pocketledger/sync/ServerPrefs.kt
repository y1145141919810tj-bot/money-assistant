package com.pocketledger.sync

import android.content.Context

/**
 * 家里电脑的地址和设备令牌。明文只留在本机偏好里，请求时放在 Bearer 头。
 */
class ServerPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** @returns 去掉末尾斜杠的基址；未填写时为空串。 */
    fun baseUrl(): String = prefs.getString(KEY_URL, "").orEmpty().trim().trimEnd('/')

    /** @returns 设备令牌；未填写时为空串。 */
    fun token(): String = prefs.getString(KEY_TOKEN, "").orEmpty().trim()

    /** @returns 地址是 http(s) 且令牌非空。 */
    fun isConfigured(): Boolean {
        val url = baseUrl()
        val http = url.startsWith("http://") || url.startsWith("https://")
        return http && token().isNotEmpty()
    }

    /**
     * @param baseUrl 电脑地址，例如 `http://192.168.1.20:8787` 或 WireGuard 内网地址。
     * @param token 服务端 `data/device.token` 里的那一行。
     */
    fun save(baseUrl: String, token: String) {
        prefs.edit()
            .putString(KEY_URL, baseUrl.trim().trimEnd('/'))
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }

    private companion object {
        const val FILE = "pocketledger_server"
        const val KEY_URL = "base_url"
        const val KEY_TOKEN = "token"
    }
}
