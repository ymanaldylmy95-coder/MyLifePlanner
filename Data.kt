package com.mylife.planner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

data class Task(
    val id: Int, val title: String, val desc: String, val due: Long,
    val priority: Int, val remind: Int, val repeat: String,
    val done: Boolean = false, val doneAt: Long = 0, val series: Int = 0
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("desc", desc); put("due", due); put("p", priority)
        put("rem", remind); put("rep", repeat); put("done", done); put("doneAt", doneAt); put("series", series)
    }
    companion object {
        fun from(o: JSONObject) = Task(
            o.getInt("id"), o.getString("title"), o.optString("desc"), o.getLong("due"),
            o.optInt("p", 2), o.optInt("rem", 0), o.optString("rep", "none"),
            o.optBoolean("done"), o.optLong("doneAt"), o.optInt("series")
        )
    }
}

object Repo {
    var listener: (() -> Unit)? = null
    private fun sp(c: Context) = c.getSharedPreferences("planner", Context.MODE_PRIVATE)
    fun newId() = (System.currentTimeMillis() % 1_000_000_000L).toInt()

    fun all(c: Context): List<Task> = try {
        val a = JSONArray(sp(c).getString("tasks", "[]"))
        (0 until a.length()).map { Task.from(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    private fun saveAll(c: Context, l: List<Task>) {
        sp(c).edit().putString("tasks", JSONArray(l.map { it.toJson() }).toString()).commit()
        listener?.invoke()
    }
    fun get(c: Context, id: Int) = all(c).firstOrNull { it.id == id }

    fun upsert(c: Context, t: Task) {
        val l = all(c).toMutableList()
        val i = l.indexOfFirst { it.id == t.id }
        if (i >= 0) l[i] = t else l.add(t)
        saveAll(c, l)
        Scheduler.schedule(c, t)
    }
    fun delete(c: Context, id: Int) {
        Scheduler.cancel(c, id)
        saveAll(c, all(c).filter { it.id != id })
    }
    fun complete(c: Context, id: Int) {
        val t = get(c, id) ?: return
        upsert(c, t.copy(done = true, doneAt = System.currentTimeMillis()))
        if (t.repeat != "none") spawnNext(c, t)
    }
    fun uncheck(c: Context, id: Int) {
        val t = get(c, id) ?: return
        upsert(c, t.copy(done = false, doneAt = 0))
    }
    fun spawnNext(c: Context, t: Task) {
        val z = ZoneId.systemDefault()
        val next = LocalDateTime.ofInstant(Instant.ofEpochMilli(t.due), z)
            .plusDays(if (t.repeat == "daily") 1 else 7).atZone(z).toInstant().toEpochMilli()
        val sid = if (t.series == 0) t.id else t.series
        if (all(c).any { it.series == sid && it.due == next }) return
        upsert(c, t.copy(id = newId(), due = next, done = false, doneAt = 0, series = sid))
    }
    fun snooze(c: Context, id: Int, minutes: Int) {
        val t = get(c, id) ?: return
        upsert(c, t.copy(due = maxOf(System.currentTimeMillis(), t.due) + minutes * 60_000L, done = false))
    }
    fun toTomorrow(c: Context, id: Int) {
        val t = get(c, id) ?: return
        val z = ZoneId.systemDefault()
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(t.due), z)
        val base = maxOf(dt.toLocalDate(), LocalDate.now())
        upsert(c, t.copy(due = LocalDateTime.of(base.plusDays(1), dt.toLocalTime()).atZone(z).toInstant().toEpochMilli(), done = false))
    }
    fun theme(c: Context) = sp(c).getInt("theme", -1)
    fun setTheme(c: Context, v: Int) = sp(c).edit().putInt("theme", v).apply()
}
