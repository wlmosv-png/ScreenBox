package io.github.wlmosv.screenbox.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.wlmosv.screenbox.core.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * 删除安全自检（debug only）。
 *
 * adb shell am broadcast -a io.github.wlmosv.screenbox.SAFETY_CHECK \
 *   -n io.github.wlmosv.screenbox/.bench.SafetyCheckReceiver
 *
 * 验证内容（不真删任何文件）：
 * 1. buildPlan 是否正确排除 PROTECTED / FAVORITE
 * 2. buildPlan 是否正确跳过已不存在的文件
 * 3. 完全重复组里 keeper 是否被识别
 * 4. 清理建议是否排除了保护项
 */
class SafetyCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context.applicationContext)
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val sb = StringBuilder()
            sb.appendLine("=== 删除安全自检 ===")
            sb.appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
            sb.appendLine()

            val db = Graph.db
            val dao = db.screenshotDao()

            // ── 场景 1：保护项必须被排除 ──
            val protectedShots = dao.protectedAll()
            sb.appendLine("--- 场景 1：保护项拦截 ---")
            sb.appendLine("库中受保护/收藏的图: ${protectedShots.size} 张")
            if (protectedShots.isNotEmpty()) {
                val plan = Graph.deleteRepo.buildPlan(protectedShots.map { it.id })
                val leaked = plan.deletable.size
                sb.appendLine("提交 ${protectedShots.size} 张给 buildPlan")
                sb.appendLine("  可删除: ${plan.deletable.size}  （必须为 0）")
                sb.appendLine("  被拦截: ${plan.blockedProtected.size}")
                sb.appendLine(if (leaked == 0) "  ✅ 通过：保护项零泄漏" else "  ❌ 失败：有 $leaked 张保护项进入删除计划")
            } else {
                sb.appendLine("  （库中没有保护项，跳过）")
            }
            sb.appendLine()

            // ── 场景 2：不存在的文件必须被跳过 ──
            sb.appendLine("--- 场景 2：文件已不存在 ---")
            val fakeId = dao.count().toLong() + 999999
            val fakePlan = Graph.deleteRepo.buildPlan(listOf(fakeId))
            sb.appendLine("提交一个不存在的 id=$fakeId")
            sb.appendLine("  可删除: ${fakePlan.deletable.size}  （必须为 0）")
            sb.appendLine(if (fakePlan.deletable.isEmpty()) "  ✅ 通过：不存在的 id 被跳过" else "  ❌ 失败")
            sb.appendLine()

            // ── 场景 3：完全重复组 keeper 识别 ──
            sb.appendLine("--- 场景 3：完全重复组 keeper ---")
            val groups = db.duplicateDao().activeGroups()
                .filter { it.groupType == io.github.wlmosv.screenbox.data.db.DuplicateGroupType.EXACT }
            sb.appendLine("完全重复组: ${groups.size}")
            for (g in groups.take(3)) {
                val members = db.duplicateDao().membersOf(g.id)
                val shots = dao.byIds(members.map { it.screenshotId })
                if (shots.size < 2) continue
                val keeper = io.github.wlmosv.screenbox.data.repo.DuplicateRepository(db).pickKeeper(shots)
                sb.appendLine("  组 #${g.id}: ${shots.size} 张, keeper=${keeper.displayName.take(40)}")
                // keeper 必须在组内
                sb.appendLine(if (shots.any { it.id == keeper.id }) "    ✅ keeper 属于本组" else "    ❌ keeper 不在组内")
            }
            sb.appendLine()

            // ── 场景 4：清理建议是否排除保护项 ──
            sb.appendLine("--- 场景 4：清理建议排除保护项 ---")
            // 先执行一次保护项清理（模拟应用启动时的自愈），再检查
            val healed = db.cleanupDao().resolveOpenForProtected()
            sb.appendLine("启动自愈清理了 $healed 条保护项建议")
            val suggestions = db.cleanupDao().openSuggestionsFlow()
            // 直接查表更可靠
            val openSug = db.cleanupDao().allOpen()
            sb.appendLine("OPEN 建议: ${openSug.size}")
            if (openSug.isNotEmpty()) {
                val ids = openSug.map { it.screenshotId }
                val shots = dao.byIds(ids)
                val bad = shots.count {
                    it.protectionStatus == io.github.wlmosv.screenbox.data.db.ProtectionStatus.PROTECTED ||
                        it.protectionStatus == io.github.wlmosv.screenbox.data.db.ProtectionStatus.FAVORITE ||
                        it.isFavorite
                }
                sb.appendLine("  涉及图片: ${shots.size} 张")
                sb.appendLine("  其中受保护: $bad 张 （必须为 0）")
                sb.appendLine(if (bad == 0) "  ✅ 通过：建议里没有保护项" else "  ❌ 失败：$bad 张保护项出现在建议里")
            }
            sb.appendLine()

            // ── 场景 5：统计口径一致性 ──
            sb.appendLine("--- 场景 5：统计口径一致性 ---")
            val total = dao.count()
            val recognized = dao.recognizedCount()
            val pending = dao.pendingOcrCount()
            val failed = dao.failedOcrCount()
            sb.appendLine("总数: $total")
            sb.appendLine("已识别: $recognized")
            sb.appendLine("待识别: $pending")
            sb.appendLine("失败: $failed")
            val sum = recognized + pending
            sb.appendLine("已识别 + 待识别 = $sum （应接近总数 $total）")
            sb.appendLine(if (kotlin.math.abs(sum - total) <= failed + 5) "  ✅ 通过：口径基本一致" else "  ❌ 失败：差值 ${total - sum}")

            val out = sb.toString()
            File(app.getExternalFilesDir(null), "safety_check.txt").writeText(out)
            android.util.Log.i("SCREENBOX_SAFETY", out)
        }
    }
}
