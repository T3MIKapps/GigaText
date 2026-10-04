package com.example.textimprover

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {

    // Кэш токена доступа GigaChat (живёт около 30 минут)
    private var token: String? = null
    private var tokenExpires = 0L
    private var tokenForKey = ""

    private val styles = linkedMapOf(
        "Короче" to "Сделай текст короче, сохранив смысл.",
        "Вежливее" to "Перепиши текст вежливее и мягче.",
        "Смешнее" to "Перепиши текст с лёгким юмором.",
        "Деловой стиль" to "Перепиши текст в деловом стиле.",
        "Исправить ошибки" to "Исправь орфографию и пунктуацию, ничего больше не меняя."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val keyField = EditText(this).apply {
            hint = "Ключ авторизации GigaChat"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        val input = EditText(this).apply {
            hint = "Вставь свой текст"
            minLines = 4
            gravity = Gravity.TOP
        }
        val status = TextView(this).apply { textSize = 14f }
        val result = TextView(this).apply {
            textSize = 17f
            setTextIsSelectable(true)
            setPadding(0, pad, 0, pad)
        }

        root.addView(keyField)
        root.addView(input)

        for ((name, instruction) in styles) {
            root.addView(Button(this).apply {
                text = name
                setOnClickListener {
                    val key = keyField.text.toString().trim()
                    val text = input.text.toString().trim()
                    if (key.isEmpty() || text.isEmpty()) {
                        Toast.makeText(context, "Нужны ключ и текст", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    prefs.edit().putString("key", key).apply()
                    status.text = "Думаю..."
                    result.text = ""
                    thread {
                        try {
                            val out = improve(key, instruction, text)
                            runOnUiThread { status.text = ""; result.text = out }
                        } catch (e: Exception) {
                            runOnUiThread { status.text = "Ошибка: ${e.message}" }
                        }
                    }
                }
            })
        }

        root.addView(status)
        root.addView(result)
        root.addView(Button(this).apply {
            text = "Копировать результат"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("text", result.text))
                Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    // Шаг 1: меняем ключ авторизации на временный токен доступа
    private fun getToken(authKey: String): String {
        val now = System.currentTimeMillis()
        val cached = token
        if (cached != null && tokenForKey == authKey && now < tokenExpires - 60_000) return cached

        val conn = URL("https://ngw.devices.sberbank.ru:9443/api/v2/oauth")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("RqUID", UUID.randomUUID().toString())
        conn.setRequestProperty("Authorization", "Basic $authKey")
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        conn.doOutput = true
        conn.outputStream.use { it.write("scope=GIGACHAT_API_PERS".toByteArray()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream.bufferedReader().readText()
        if (code !in 200..299) throw Exception("авторизация, код $code: $resp")

        val json = JSONObject(resp)
        token = json.getString("access_token")
        tokenExpires = json.optLong("expires_at", now + 25 * 60_000)
        tokenForKey = authKey
        return token!!
    }

    // Шаг 2: отправляем текст в GigaChat
    private fun improve(authKey: String, instruction: String, text: String): String {
        val accessToken = getToken(authKey)

        val messages = JSONArray()
            .put(
                JSONObject().put("role", "system").put(
                    "content",
                    "Ты редактор текстов. $instruction " +
                        "Верни только готовый текст, без пояснений. Язык текста не меняй."
                )
            )
            .put(JSONObject().put("role", "user").put("content", text))

        val body = JSONObject()
            .put("model", "GigaChat")
            .put("messages", messages)
            .put("max_tokens", 1000)

        val conn = URL("https://gigachat.devices.sberbank.ru/api/v1/chat/completions")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $accessToken")
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream.bufferedReader().readText()
        if (code !in 200..299) throw Exception("код $code: $resp")

        return JSONObject(resp)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }
}
