package com.example.wordbook

import android.app.Application
import com.example.wordbook.util.DailyJobs

class WordBookApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 注册每日备份与每日通知（KEEP：已注册则不重复排期）
        DailyJobs.schedule(this)
    }
}
