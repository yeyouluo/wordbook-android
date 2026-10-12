package com.example.wordbook

import android.app.Application
import com.example.wordbook.data.SyncRunner
import com.example.wordbook.util.DailyJobs
import com.example.wordbook.util.SyncGate
import com.example.wordbook.util.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WordBookApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 注册每日备份与每日通知（KEEP：已注册则不重复排期）
        DailyJobs.schedule(this)
        // 按设置里的间隔注册定时云同步（间隔 0 或关闭时取消任务）
        SyncScheduler.apply(this)
        // 触发时机之一：App 启动后 2.5 秒静默同步一次（失败只记录，不打扰）
        CoroutineScope(Dispatchers.IO).launch {
            delay(2500)
            val runner = SyncRunner(this@WordBookApplication)
            if (!runner.configured() || !runner.auto()) return@launch
            if (!SyncGate.tryEnter()) return@launch
            try {
                runner.run()
            } finally {
                SyncGate.leave()
            }
        }
    }
}
