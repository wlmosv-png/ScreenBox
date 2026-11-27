package io.github.wlmosv.screenbox.data.repo

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import io.github.wlmosv.screenbox.data.db.CleanupOperationEntity
import io.github.wlmosv.screenbox.data.db.CleanupOperationItemEntity
import io.github.wlmosv.screenbox.data.db.OperationStatus
import io.github.wlmosv.screenbox.data.db.OperationType
import io.github.wlmosv.screenbox.data.db.ProtectionStatus
import io.github.wlmosv.screenbox.data.db.ScreenBoxDatabase
import io.github.wlmosv.screenbox.data.db.ScreenshotEntity
import io.github.wlmosv.screenbox.data.media.MediaStoreSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 删除请求的规划结果。
 * 真正调系统弹窗之前，必须先生成这个 —— UI 拿它展示清单给用户确认。
 */
data class DeletePlan(
    val requested: List<ScreenshotEntity>,
    /** 因保护状态被拦下的，不进入删除。 */
    val blockedProtected: List<ScreenshotEntity>,
    /** 因文件已不存在被跳过的。 */
    val missing: List<ScreenshotEntity>,
    val totalBytes: Long,
) {
    val deletable: List<ScreenshotEntity> get() = requested
    val canProceed: Boolean get() = requested.isNotEmpty()
}

/**
 * 删除流水线。
 *
 * 顺序不可颠倒（这是整个产品最容易出人命的地方）：
 *   1. 过滤保护状态
 *   2. 复查文件是否还在
 *   3. 落库建操作单（PENDING）
 *   4. 交给系统弹窗请求授权
 *   5. 按系统真实返回更新每一条结果
 *   6. 部分成功必须如实记录
 */
class DeleteRepository(
    private val context: Context,
    private val db: ScreenBoxDatabase,
) {

    private val media = MediaStoreSource(context)

    /**
     * 第一步：生成删除计划。
     * 只读，不产生任何副作用。
     */
    suspend fun buildPlan(ids: List<Long>): DeletePlan = withContext(Dispatchers.IO) {
        val dao = db.screenshotDao()
        val shots = dao.byIds(ids)

        val protected = mutableListOf<ScreenshotEntity>()
        val missing = mutableListOf<ScreenshotEntity>()
        val candidates = mutableListOf<ScreenshotEntity>()

        for (shot in shots) {
            when (shot.protectionStatus) {
                ProtectionStatus.PROTECTED,
                ProtectionStatus.FAVORITE,
                ProtectionStatus.DELETED,
                ProtectionStatus.MISSING,
                -> protected += shot
                else -> candidates += shot
            }
        }

        // 复查：MediaStore 里还在不在。
        // 必须批量 —— 逐张查 8000 次要 40 秒以上，用户会以为卡死。
        val liveIds = media.filterExisting(candidates.map { it.mediaStoreId })
        val verified = mutableListOf<ScreenshotEntity>()
        for (shot in candidates) {
            if (shot.mediaStoreId in liveIds) {
                verified += shot
            } else {
                missing += shot
            }
        }

        DeletePlan(
            requested = verified,
            blockedProtected = protected,
            missing = missing,
            totalBytes = verified.sumOf { it.fileSize },
        )
    }

    /**
     * 第二步：创建待删除操作单。
     * 注意：这一步不调用系统删除，只落库。UI 随后用返回的 intentSender 拉起系统弹窗。
     */
    suspend fun createOperation(plan: DeletePlan): Pair<Long, android.content.IntentSender?> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val opId = db.cleanupDao().insertOperation(
            CleanupOperationEntity(
                operationType = OperationType.DELETE,
                status = OperationStatus.PENDING,
                createdAt = now,
                totalCount = plan.requested.size,
            )
        )
        db.cleanupDao().insertOperationItems(
            plan.requested.map {
                CleanupOperationItemEntity(
                    operationId = opId,
                    screenshotId = it.id,
                    displayName = it.displayName,
                    fileSize = it.fileSize,
                )
            }
        )

        // 标记为 PENDING_DELETE，但不改 PROTECTED
        for (shot in plan.requested) {
            db.screenshotDao().setProtection(shot.id, ProtectionStatus.PENDING_DELETE, now)
        }

        val uris = plan.requested.map { Uri.parse(it.contentUri) }
        opId to buildDeleteRequest(uris)
    }

    /**
     * Android 11+ 用 MediaStore.createDeleteRequest，系统弹一次窗，用户确认后统一删。
     * Android 10 及以下没有这个 API，只能逐条 delete，且需要 WRITE_EXTERNAL_STORAGE。
     */
    private fun buildDeleteRequest(uris: List<Uri>): android.content.IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (uris.isEmpty()) return null
        return runCatching {
            MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
        }.getOrNull()
    }

    /**
     * 第三步：系统弹窗返回后，按结果落账。
     *
     * @param confirmed true = 用户在系统弹窗点了允许；false = 取消或拒绝
     */
    suspend fun finalizeOperation(opId: Long, confirmed: Boolean) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val items = db.cleanupDao().itemsOf(opId)
        val dao = db.screenshotDao()

        if (!confirmed) {
            // 用户取消：所有项回滚为 NORMAL，状态记为 CANCELLED
            for (item in items) {
                dao.setProtection(item.screenshotId, ProtectionStatus.NORMAL, now)
                db.cleanupDao().setItemResult(opId, item.screenshotId, "user_cancelled", "用户在系统弹窗中取消")
            }
            db.cleanupDao().finishOperation(opId, OperationStatus.CANCELLED, now, 0, 0, 0)
            return@withContext
        }

        var ok = 0
        var fail = 0
        var freed = 0L

        // 批量确认删除结果，避免逐张跨进程查询
        val shots = dao.byIds(items.map { it.screenshotId })
        val shotById = shots.associateBy { it.id }
        val stillAlive = media.filterExisting(shots.map { it.mediaStoreId })

        for (item in items) {
            val shot = shotById[item.screenshotId]
            if (shot == null) {
                db.cleanupDao().setItemResult(opId, item.screenshotId, "failed", "记录不存在")
                fail++
                continue
            }
            if (shot.mediaStoreId !in stillAlive) {
                dao.setProtection(shot.id, ProtectionStatus.DELETED, now)
                db.cleanupDao().setItemResult(opId, shot.id, "success", null)
                ok++
                freed += shot.fileSize
            } else {
                // 系统说删了但文件还在 —— 不许报成功
                dao.setProtection(shot.id, ProtectionStatus.NORMAL, now)
                db.cleanupDao().setItemResult(opId, shot.id, "failed", "系统返回成功但文件仍存在")
                fail++
            }
        }

        val status = when {
            fail == 0 && ok > 0 -> OperationStatus.COMPLETED
            ok > 0 -> OperationStatus.PARTIAL
            else -> OperationStatus.FAILED
        }
        db.cleanupDao().finishOperation(opId, status, now, ok, fail, freed)
    }

    /** 单张删除（详情页用），走同一条流水线。 */
    suspend fun deleteSingle(id: Long): Pair<Long, android.content.IntentSender?> {
        val plan = buildPlan(listOf(id))
        if (!plan.canProceed) {
            // 保护状态或文件不存在 —— 返回 -1 表示不发起
            return -1L to null
        }
        return createOperation(plan)
    }
}
