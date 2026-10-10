package com.example.wordbook

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.wordbook.data.AppDatabase
import com.example.wordbook.util.ReviewSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 桌面小组件：不打开 APP 就能看到「今日待复习 N」与掌握进度；点一下直达复习。
 * - 用经典 RemoteViews（零新依赖），查询走 IO 协程，不阻塞主线程；
 * - 调度判断复用 util/ReviewSchedule，与首页、每日通知保持同一套规则。
 */
class WordBookWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val views = buildViews(context)
                appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {

        /** 数据变化后（复习完、加词、导入）主动刷新，让小组件立刻跟上 */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(context, WordBookWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            CoroutineScope(Dispatchers.IO).launch {
                val views = buildViews(context)
                ids.forEach { manager.updateAppWidget(it, views) }
            }
        }

        /** 读库 → 渲染 RemoteViews（唯一构建点） */
        private suspend fun buildViews(context: Context): RemoteViews {
            val all = AppDatabase.get(context).wordDao().getAllOnce()
            val due = ReviewSchedule.dueCount(all)
            val mastered = all.count { it.mastered }

            val views = RemoteViews(context.packageName, R.layout.widget_wordbook)
            views.setTextViewText(
                R.id.widget_due,
                if (due == 0) context.getString(R.string.widget_due_none)
                else context.getString(R.string.widget_due_count, due)
            )
            views.setTextViewText(
                R.id.widget_progress,
                context.getString(R.string.widget_progress, mastered, all.size)
            )

            // 点击 → 打开 APP 并直接弹出「今日复习」
            val openIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_OPEN_REVIEW, true)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                2001,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            return views
        }
    }
}
