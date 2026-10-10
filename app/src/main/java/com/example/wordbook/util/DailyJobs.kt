package com.example.wordbook.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.wordbook.R
import com.example.wordbook.data.AppDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** App 启动时注册两个每日周期任务：自动备份 CSV + 每日一词通知 */
object DailyJobs {

    const val BACKUP_WORK = "wordbook_daily_backup"
    const val NOTIFY_WORK = "wordbook_daily_notify"

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            BACKUP_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequest.Builder(BackupWorker::class.java, java.time.Duration.ofDays(1)).build()
        )
        wm.enqueueUniquePeriodicWork(
            NOTIFY_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequest.Builder(DailyNotifyWorker::class.java, java.time.Duration.ofHours(24)).build()
        )
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}

/** 每日自动备份：把全部单词 CSV 写到应用目录 Documents/ 下，保留最近 7 份 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val context = applicationContext
            val db = AppDatabase.get(context)
            val csv = CsvUtil.buildCsv(db.wordDao().getAllOnce())
            if (csv.isNotBlank()) {
                val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Documents")
                dir.mkdirs()
                val stamp = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
                File(dir, "wordbook-backup-$stamp.csv").writeText(csv)
                dir.listFiles { f -> f.name.startsWith("wordbook-backup-") }
                    ?.sortedByDescending { it.name }
                    ?.drop(7)
                    ?.forEach { it.delete() }
            }
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}

/** 每日一词默认通知：优先提醒今天该复习的词，没有待复习就随机推一个还没掌握的词 */
class DailyNotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!DailyJobs.hasNotificationPermission(applicationContext)) return Result.success()

        val dao = AppDatabase.get(context).wordDao()
        val unmastered = dao.getUnmasteredOnce()
        // 与首页统计、桌面小组件共用同一套调度规则（util/ReviewSchedule）
        val dueTip = ReviewSchedule.dueCount(unmastered)

        val dailyWord = unmastered.randomOrNull()
        val message = when {
            dailyWord == null -> "词库已经全部掌握，快去添加新单词吧！"
            dueTip > 0 -> "今天有 $dueTip 个单词该复习了，抽几分钟刷一遍吧"
            else -> "每日一词：${dailyWord.spelling} — 去看看它的释义和例句"
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            "wordbook_daily",
            "每日单词提醒",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)

        // 点击通知 → 直达今日复习弹窗
        val openIntent = Intent(context, com.example.wordbook.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(com.example.wordbook.MainActivity.EXTRA_OPEN_REVIEW, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 1001, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, "wordbook_daily")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("单词本")
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(1001, notification)
        return Result.success()
    }
}
