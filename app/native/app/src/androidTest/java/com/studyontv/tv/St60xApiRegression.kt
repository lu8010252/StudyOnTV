package com.studyontv.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ST-601 ~ ST-604 真机端到端 HTTP API 冒烟回归（仅在 debug 包 instrumentation 下运行，结束后完全恢复原配置与清理探针题）。
 */
object St60xApiRegression {
    private data class HttpRes(val code: Int, val body: String)

    private fun http(port: Int, method: String, path: String, token: String?, body: String? = null): HttpRes {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..399) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val out = ByteArrayOutputStream()
        stream?.use { it.copyTo(out) }
        conn.disconnect()
        return HttpRes(code, out.toString("UTF-8"))
    }

    fun run(context: Context): String {
        DeviceRuntime.ensure(context)
        val port = Prefs.localPort(context)
        val store = DeviceRuntime.store
        val origConfig = JSONObject(store.config().toString())
        val token = AdminAuth.issueSession(remember = false, context = context)
        val qid = "st60x-e2e-probe-q1"
        val log = StringBuilder()
        try {
            // 1. 插入探针错题（仅用于本次冒烟，finally 会彻底删除）
            store.upsertQuestion(JSONObject()
                .put("id", qid)
                .put("q", "ST60X_E2E_PROBE 1+1=?")
                .put("type", "single")
                .put("subject", "数学")
                .put("level", "一年级")
                .put("bank", "ST60X探针")
                .put("a", JSONArray(listOf("1", "2", "3", "4")))
                .put("k", 1)
                .put("enabled", true))
            store.answered(qid, correct = false)

            // 2. ST-601: GET /api/wrong-questions
            val rList = http(port, "GET", "/api/wrong-questions?q=ST60X_E2E_PROBE", token)
            check(rList.code == 200) { "GET /api/wrong-questions 失败: ${rList.code} ${rList.body}" }
            val jList = JSONObject(rList.body)
            val items = jList.getJSONArray("items")
            check(items.length() >= 1 && items.getJSONObject(0).getString("id") == qid) {
                "GET /api/wrong-questions 未返回探针错题: ${rList.body}"
            }
            log.append("GET /api/wrong-questions=200 (total=${jList.getInt("total")})\n")

            // 3. ST-601: POST /api/wrong-questions/extra-pool
            val rAdd = http(port, "POST", "/api/wrong-questions/extra-pool", token,
                JSONObject().put("ids", JSONArray(listOf(qid))).toString())
            check(rAdd.code == 200) { "POST /api/wrong-questions/extra-pool 失败: ${rAdd.code} ${rAdd.body}" }
            val jAdd = JSONObject(rAdd.body)
            check(jAdd.optBoolean("ok") && jAdd.optInt("extraPool") >= 1) {
                "POST /api/wrong-questions/extra-pool 返回异常: ${rAdd.body}"
            }
            log.append("POST /api/wrong-questions/extra-pool=200 (added=${jAdd.optInt("added")}, extraPool=${jAdd.optInt("extraPool")})\n")

            // 4. ST-602: POST /api/extra-pool/clear
            val rClear = http(port, "POST", "/api/extra-pool/clear", token, "{}")
            check(rClear.code == 200) { "POST /api/extra-pool/clear 失败: ${rClear.code} ${rClear.body}" }
            val jClear = JSONObject(rClear.body)
            check(jClear.optBoolean("ok") && jClear.optInt("extraPool", -1) == 0) {
                "POST /api/extra-pool/clear 未清零: ${rClear.body}"
            }
            log.append("POST /api/extra-pool/clear=200 (extraPool=${jClear.optInt("extraPool")})\n")

            // 5. ST-601: POST /api/wrong-questions/master
            val rMaster = http(port, "POST", "/api/wrong-questions/master", token,
                JSONObject().put("ids", JSONArray(listOf(qid))).toString())
            check(rMaster.code == 200) { "POST /api/wrong-questions/master 失败: ${rMaster.code} ${rMaster.body}" }
            val jMaster = JSONObject(rMaster.body)
            check(jMaster.optBoolean("ok") && jMaster.optInt("updated") == 1) {
                "POST /api/wrong-questions/master 返回异常: ${rMaster.body}"
            }
            val rListAfter = http(port, "GET", "/api/wrong-questions?q=ST60X_E2E_PROBE", token)
            check(rListAfter.code == 200 && JSONObject(rListAfter.body).getInt("total") == 0) {
                "标记已掌握后错题列表未移出: ${rListAfter.body}"
            }
            log.append("POST /api/wrong-questions/master=200 (updated=1, remaining=0)\n")

            // 6. ST-604: PUT /api/config 保存并读回 gradeSubjects
            val gs = JSONObject()
                .put("一年级", JSONArray(listOf("数学")))
                .put("二年级", JSONArray(listOf("语文", "英语")))
            val rCfg = http(port, "PUT", "/api/config", token,
                JSONObject().put("gradeSubjects", gs).toString())
            check(rCfg.code == 200) { "PUT /api/config(gradeSubjects) 失败: ${rCfg.code} ${rCfg.body}" }
            val rState = http(port, "GET", "/api/state", token)
            check(rState.code == 200) { "GET /api/state 失败: ${rState.code}" }
            val readGs = JSONObject(rState.body).getJSONObject("config").getJSONObject("gradeSubjects")
            check(readGs.getJSONArray("一年级").length() == 1 && readGs.getJSONArray("二年级").length() == 2) {
                "gradeSubjects 读回不匹配: $readGs"
            }
            log.append("PUT /api/config(gradeSubjects)=200 & readback=${readGs}\n")

            // 7. ST-603: GET /api/update & POST /api/update/install
            val rUpd = http(port, "GET", "/api/update", token)
            check(rUpd.code == 200) { "GET /api/update 失败: ${rUpd.code} ${rUpd.body}" }
            val jUpd = JSONObject(rUpd.body)
            check(jUpd.has("installState") && jUpd.has("installProgress") && jUpd.has("installMessage")) {
                "GET /api/update 缺少安装状态字段: ${rUpd.body}"
            }
            val rInst = http(port, "POST", "/api/update/install", token, "{}")
            check(rInst.code in setOf(200, 202, 400, 403, 409)) {
                "POST /api/update/install 响应码异常: ${rInst.code} ${rInst.body}"
            }
            log.append("GET /api/update=200 (installState=${jUpd.optString("installState")}, progress=${jUpd.optInt("installProgress")})\n")
            log.append("POST /api/update/install=${rInst.code} (${rInst.body})\n")

            // 8. /admin/ 静态资源
            val rAdmin = http(port, "GET", "/admin/", null)
            check(rAdmin.code == 200 && rAdmin.body.contains("<!doctype html>", ignoreCase = true)) {
                "GET /admin/ 异常: ${rAdmin.code}"
            }
            log.append("GET /admin/=200 (${rAdmin.body.length} chars)\n")
            log.append("PASS: ST-601~ST-604 真机 HTTP 全链路冒烟全部通过")
            return log.toString()
        } finally {
            try { store.deleteQuestion(qid) } catch (_: Throwable) { }
            try { DeviceRuntime.learning.saveConfig(origConfig) } catch (_: Throwable) { }
            try { AdminAuth.forget(context, token) } catch (_: Throwable) { }
        }
    }
}
