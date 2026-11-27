package io.github.wlmosv.screenbox.data.repo

import io.github.wlmosv.screenbox.data.db.DuplicateGroupEntity
import io.github.wlmosv.screenbox.data.db.DuplicateGroupType
import io.github.wlmosv.screenbox.data.db.DuplicateMemberEntity
import io.github.wlmosv.screenbox.data.db.ProtectionStatus
import io.github.wlmosv.screenbox.data.db.ScreenBoxDatabase
import io.github.wlmosv.screenbox.data.db.ScreenshotEntity
import io.github.wlmosv.screenbox.data.media.Hashing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 重复检测。
 *
 * 三层，严格分开，结论绝不互相冒充：
 *   EXACT    —— SHA-256 完全相同。这是唯一可以说"完全重复"的。
 *   VISUAL   —— pHash 汉明距离 <= 阈值。只能说"看起来很像"。
 *   CONTENT  —— 留给后续 OCR 文本重合度，当前不产出。
 *
 * 重要：一张图只能落在一个组里，不允许跨组冲突。
 */
class DuplicateRepository(private val db: ScreenBoxDatabase) {

    companion object {
        /** 高度相似：汉明距离 <= 6（64 位，约 90% 相似）。 */
        const val VISUAL_STRICT = 6

        /** 可能相似：<= 10。仅用于"供人工比较"，不进清理建议。 */
        const val VISUAL_LOOSE = 10
    }

    suspend fun rebuild() = withContext(Dispatchers.IO) {
        val dao = db.screenshotDao()
        val dupDao = db.duplicateDao()
        val now = System.currentTimeMillis()

        val shots = dao.allScannable().filter {
            it.protectionStatus != ProtectionStatus.DELETED &&
                it.protectionStatus != ProtectionStatus.MISSING
        }

        dupDao.clearAllGroups()

        val claimed = mutableSetOf<Long>()

        // ── 第一层：完全重复 ──
        val bySha = shots.mapNotNull { s -> s.sha256?.let { sha -> sha to s } }
            .groupBy({ it.first }, { it.second })
            .filter { it.value.size >= 2 }

        for ((_, members) in bySha) {
            val keeper = pickKeeper(members)
            val groupId = dupDao.insertGroup(
                DuplicateGroupEntity(
                    groupType = DuplicateGroupType.EXACT,
                    confidence = 1.0f,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            dupDao.insertMembers(
                members.map { shot ->
                    claimed += shot.id
                    DuplicateMemberEntity(
                        groupId = groupId,
                        screenshotId = shot.id,
                        // 完全重复没有"相似度"之分，1.0 表示字节一致
                        similarityScore = 1.0f,
                    )
                }
            )
            // keeper 信息保留在组内通过 pickKeeper 重新计算，不额外落库
            check(keeper.id != 0L)
        }

        // ── 第二层：视觉近似 ──
        // 注意：pHash 距离为 0 但 SHA-256 不同的图（例如同图不同压缩）仍然只是"近似"，
        // 不能算完全重复。但这类图应当优先归入 VISUAL 而不是被漏掉。
        val candidates = shots
            .filter { it.perceptualHash != null && it.id !in claimed }
            .sortedBy { it.dateAdded }

        // 粗桶：pHash 高 8 bit 相同才互相比，避免 O(n^2)
        val buckets = candidates.groupBy { it.perceptualHash!!.take(2) }

        for ((_, bucket) in buckets) {
            if (bucket.size < 2) continue
            val used = mutableSetOf<Long>()
            for (i in bucket.indices) {
                val a = bucket[i]
                if (a.id in used) continue

                val cluster = mutableListOf<Pair<ScreenshotEntity, Int>>()
                cluster += a to 0

                for (j in i + 1 until bucket.size) {
                    val b = bucket[j]
                    if (b.id in used) continue
                    val dist = Hashing.hammingDistance(a.perceptualHash!!, b.perceptualHash!!)
                    if (dist <= VISUAL_LOOSE) {
                        cluster += b to dist
                    }
                }

                if (cluster.size < 2) continue

                val worst = cluster.maxOf { it.second }
                val groupId = dupDao.insertGroup(
                    DuplicateGroupEntity(
                        groupType = DuplicateGroupType.VISUAL,
                        confidence = (1f - worst / 64f).coerceIn(0f, 1f),
                        createdAt = now,
                        updatedAt = now,
                    )
                )
                dupDao.insertMembers(
                    cluster.map { (shot, dist) ->
                        used += shot.id
                        DuplicateMemberEntity(
                            groupId = groupId,
                            screenshotId = shot.id,
                            similarityScore = (1f - dist / 64f).coerceIn(0f, 1f),
                        )
                    }
                )
            }
        }
    }

    /**
     * 组内该保留哪一张。
     * 排序依据：收藏 > 保护 > 更早创建 > 更大文件。
     * 这个函数只决定"建议保留"，不删除任何东西。
     */
    fun pickKeeper(members: List<ScreenshotEntity>): ScreenshotEntity =
        members.maxWithOrNull(
            compareBy(
                { if (it.isFavorite) 1 else 0 },
                { if (it.protectionStatus == ProtectionStatus.PROTECTED) 1 else 0 },
                { -it.dateAdded },
                { it.fileSize },
            )
        ) ?: members.first()

    suspend fun dismissGroup(groupId: Long) = withContext(Dispatchers.IO) {
        db.duplicateDao().dismiss(groupId, System.currentTimeMillis())
    }
}
