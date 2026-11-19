package com.ahbe.novi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Standalone Novi data; never reads or modifies the legacy checklist preferences. */
object NoviStore {
    private const val PREFS = "novi_conversations_v1"
    private const val KEY = "chats"

    data class Message(
        val id: String = UUID.randomUUID().toString(),
        var kind: String,
        var text: String,
        var fileId: String = "",
        var durationMs: Long = 0L,
        val createdAt: Long = System.currentTimeMillis()
    )

    data class Chat(
        val id: String = UUID.randomUUID().toString(),
        var title: String,
        val messages: MutableList<Message> = mutableListOf(),
        val createdAt: Long = System.currentTimeMillis()
    )

    fun load(context: Context): MutableList<Chat> = runCatching {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val array = JSONArray(saved)
        (0 until array.length()).mapNotNull { n ->
            val json = array.optJSONObject(n) ?: return@mapNotNull null
            val title = json.optString("title")
            val id = json.optString("id")
            if (id.isBlank() || title.isBlank()) return@mapNotNull null
            val messages = mutableListOf<Message>()
            val items = json.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until items.length()) {
                val entry = items.optJSONObject(i) ?: continue
                val kind = entry.optString("kind")
                if (kind !in listOf("text", "audio", "zip")) continue
                messages.add(Message(
                    id = entry.optString("id").ifBlank { UUID.randomUUID().toString() },
                    kind = kind,
                    text = entry.optString("text"),
                    fileId = entry.optString("fileId"),
                    durationMs = entry.optLong("durationMs"),
                    createdAt = entry.optLong("createdAt")
                ))
            }
            Chat(id, title, messages, json.optLong("createdAt"))
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    fun save(context: Context, chats: List<Chat>) {
        val rows = JSONArray()
        chats.forEach { chat ->
            val entry = JSONObject().apply {
                put("id", chat.id)
                put("title", chat.title)
                put("createdAt", chat.createdAt)
                put("messages", JSONArray().apply {
                    chat.messages.forEach { msg ->
                        put(JSONObject().apply {
                            put("id", msg.id)
                            put("kind", msg.kind)
                            put("text", msg.text)
                            put("fileId", msg.fileId)
                            put("durationMs", msg.durationMs)
                            put("createdAt", msg.createdAt)
                        })
                    }
                })
            }
            rows.put(entry)
        }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, rows.toString()).commit()) { "Unable to save conversations" }
    }
}
