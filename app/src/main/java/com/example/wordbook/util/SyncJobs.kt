package com.example.wordbook.util

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.wordbook.data.SyncRunner
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 云同步的定时任务与调度（P0 触发时机之三：定时）。
 * 另外两个时机（App 启动后 2.5s、回到前台）由 WordBookApplication / MainActivity 触发。
 *
 * 三个时机都走同一个 SyncRunner，且**同一时刻只允许一个同步任务**（进程内闸门 + WorkManager 唯一任务名）。
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!SyncGate.tryEnter()) return Result.success()
        return try {
            val runner = SyncRunner(applicationContext)
            if (!runner.configured() || !runner.auto()) return Result.success()
            // 静默同步：失败只写进 sync_last_error，设置页可见，不打扰用户
            runner.run()
            Result.success()
        } catch (e: Exception) {
            Result.success()
        } finally {
            SyncGate.leave()
        }
    }
}

/** 进程内同步闸门：防止前台触发与定时任务同时跑 */
object SyncGate {
    private val running = AtomicBoolean(false)
    fun tryEnter(): Boolean = running.compareAndSet(false, true)
    fun leave() {
        running.set(false)
    }
    val isRunning: Boolean get() = running.get()
}

/** 定时同步的调度：间隔来自设置（0 = 关闭） */
object SyncScheduler {

    const val WORK_NAME = "wordbook_webdav_sync"

    /** 可选间隔（分钟）；0 表示关闭定时同步 */
    val INTERVAL_OPTIONS = listOf(0, 15, 30, 60, 120)

    fun apply(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val auto = prefs.getBoolean(SyncRunner.K_AUTO, true)
        val minutes = prefs.getInt(SyncRunner.K_INTERVAL, 30)
        val wm = WorkManager.getInstance(context)
        if (!auto || minutes <= 0) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequest.Builder(
            SyncWorker::class.java,
            Duration.ofMinutes(minutes.toLong())
        ).build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
