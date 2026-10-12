package com.example.wordbook.util

import com.example.wordbook.data.Word

/**
 * 云同步的**纯决策逻辑**（无 Android / 网络依赖，便于单测）。
 *
 * 锚点（存在 SharedPreferences，不进 Room）：
 * - localFp：上次同步完成时本机词库的确定性摘要
 * - remoteRev：上次同步完成时远端文件的 rev（本实现里 rev 就是内容摘要，内容寻址）
 * - lastSyncAt：上次同步完成时间
 *
 * 决策表：
 * | 本地变 | 远端变 | 结果 |
 * | --- | --- | --- |
 * | 否 | 否 | NONE（什么都不做） |
 * | 是 | 否 | PUSH |
 * | 否 | 是 | PULL |
 * | 是 | 是 | 冲突：比较 remote.exportedAt 与 lastSyncAt，新者胜（LWW） |
 * 远端没有文件（首次）→ PUSH。
 */
object SyncCore {

    /** 词库确定性摘要：count:ΣreviewCount:Σ(correct+wrong):masteredCount:maxLastReviewedAt:maxCreatedAt */
    data class Fingerprint(
        val count: Int,
        val reviewSum: Long,
        val attemptSum: Long,
        val mastered: Int,
        val maxLastReviewed: Long,
        val maxCreated: Long
    ) {
        fun encode(): String = "$count:$reviewSum:$attemptSum:$mastered:$maxLastReviewed:$maxCreated"

        companion object {
            fun decode(raw: String?): Fingerprint? {
                val parts = raw?.split(':') ?: return null
                if (parts.size != 6) return null
                return try {
                    Fingerprint(
                        count = parts[0].toInt(),
                        reviewSum = parts[1].toLong(),
                        attemptSum = parts[2].toLong(),
                        mastered = parts[3].toInt(),
                        maxLastReviewed = parts[4].toLong(),
                        maxCreated = parts[5].toLong()
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    fun fingerprint(words: List<Word>): Fingerprint = Fingerprint(
        count = words.size,
        reviewSum = words.sumOf { it.reviewCount.toLong() },
        attemptSum = words.sumOf { (it.totalCorrect + it.totalWrong).toLong() },
        mastered = words.count { it.mastered },
        maxLastReviewed = words.maxOfOrNull { it.lastReviewedAt } ?: 0L,
        maxCreated = words.maxOfOrNull { it.createdAt } ?: 0L
    )

    enum class Action {
        /** 远端没有文件：首次上传 */
        PUSH_FIRST,
        /** 仅本地变化：上传覆盖远端 */
        PUSH,
        /** 仅远端变化：拉取覆盖本地 */
        PULL,
        /** 双方都变，远端更新：拉取，本机旧版本要备份 */
        CONFLICT_PULL,
        /** 双方都变，本地更新：上传，远端旧版本要备份 */
        CONFLICT_PUSH,
        /** 双方都没变 */
        NONE
    }

    data class Decision(val action: Action, val reason: String)

    /**
     * @param localFp       当前本机指纹
     * @param lastFp        上次同步时的本机指纹（锚点）
     * @param remoteRev     远端 rev（内容摘要）
     * @param lastRemoteRev 上次同步时的远端 rev（锚点）
     * @param remoteExists  远端文件是否存在
     * @param remoteExportedAt 远端文件的导出时间（LWW 依据）
     * @param lastSyncAt    上次同步完成时间（LWW 依据）
     */
    fun decide(
        localFp: Fingerprint,
        lastFp: Fingerprint?,
        remoteRev: String?,
        lastRemoteRev: String?,
        remoteExists: Boolean,
        remoteExportedAt: Long,
        lastSyncAt: Long
    ): Decision {
        if (!remoteExists) {
            return Decision(Action.PUSH_FIRST, "远端还没有快照，执行首次上传")
        }
        val localChanged = lastFp == null || localFp.encode() != lastFp.encode()
        val remoteChanged = lastRemoteRev == null || remoteRev != lastRemoteRev

        return when {
            !localChanged && !remoteChanged -> Decision(Action.NONE, "两端都没有变化")
            localChanged && !remoteChanged -> Decision(Action.PUSH, "仅本机有改动，上传")
            !localChanged && remoteChanged -> Decision(Action.PULL, "仅远端有改动，拉取")
            // 双方都变：LWW，谁新谁赢
            remoteExportedAt > lastSyncAt ->
                Decision(Action.CONFLICT_PULL, "两端都改了：远端更新（${remoteExportedAt} > $lastSyncAt），拉取远端")
            else ->
                Decision(Action.CONFLICT_PUSH, "两端都改了：本机更新（$lastSyncAt >= $remoteExportedAt），上传本机")
        }
    }

    /** 冲突提示文案（统一口径，避免各处措辞不一致） */
    fun conflictMessage(winner: String, backupFileName: String): String =
        "已按较新一侧解决冲突（$winner 胜出），败落旧侧已备份：$backupFileName"
}
