package com.mylife.planner

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat

object Scheduler {
    private const val F = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    private fun pi(c: Context, id: Int, kind: Int) = PendingIntent.getBroadcast(
        c, id * 2 + kind,
        Intent(c, AlarmReceiver::class.java).setAction("alarm-$id-$kind").putExtra("id", id).putExtra("kind", kind), F
    )
    fun schedule(c: Context, t: Task) {
        cancel(c, t.id)
        if (t.done) return
        val now = System.currentTimeMillis()
        if (t.due > now) set(c, t.id, t.due, 1)
        if (t.remind > 0) {
            val r = t.due - t.remind * 60_000L
            if (r > now) set(c, t.id, r, 0)
        }
    }
    // setAlarmClock: أدق أنواع المنبهات، ويعمل في وضع توفير الطاقة دون إذن خاص
    private fun set(c: Context, id: Int, at: Long, kind: Int) {
        val am = c.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi(c, id, kind))
    }
    fun cancel(c: Context, id: Int) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(pi(c, id, 0)); am.cancel(pi(c, id, 1))
    }
    fun rescheduleAll(c: Context) = Repo.all(c).forEach { schedule(c, it) }
}

object Notifier {
    const val CH = "tasks_v1"
    fun channel(c: Context) {
        val ch = NotificationChannel(CH, "تنبيهات المهام", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "تنبيهات صوتية عند مواعيد المهام"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
        }
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
    fun show(c: Context, t: Task, kind: Int) {
        channel(c)
        val f = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(c, t.id, Intent(c, MainActivity::class.java), f)
        fun act(a: String) = PendingIntent.getBroadcast(
            c, t.id, Intent(c, ActionReceiver::class.java).setAction(a).putExtra("id", t.id), f
        )
        val text = if (kind == 0) "تبدأ بعد ${t.remind} دقيقة" else "حان موعد المهمة الآن"
        val n = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(t.title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (t.desc.isBlank()) text else text + "\n" + t.desc))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(open).setAutoCancel(true)
            .addAction(0, "تم ✓", act("DONE")).addAction(0, "غفوة 10 د", act("SNOOZE"))
            .build()
        try { c.getSystemService(NotificationManager::class.java).notify(t.id, n) } catch (e: SecurityException) {}
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val t = Repo.get(c, i.getIntExtra("id", -1)) ?: return
        if (t.done) return
        val kind = i.getIntExtra("kind", 1)
        Notifier.show(c, t, kind)
        if (kind == 1 && t.repeat != "none") Repo.spawnNext(c, t)
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getIntExtra("id", -1)
        if (i.action == "DONE") Repo.complete(c, id) else Repo.snooze(c, id, 10)
        c.getSystemService(NotificationManager::class.java).cancel(id)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Scheduler.rescheduleAll(c) }
}
