package io.github.wlmosv.screenbox.data.media

import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import io.github.wlmosv.screenbox.data.db.ScreenshotConfidence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context

/**
 * 一条从 MediaStore 读到的候选截图。
 * 这是"外部世界"的快照，不含任何我们自己的判定结果。
 */
data class MediaShot(
    val mediaStoreId: Long,
    val contentUri: String,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val fileSize: Long,
    val width: Int,
    val height: Int,
    val dateAdded: Long,
    val dateModified: Long,
)

/**
 * MediaStore 读取层。只读，不做业务判定。
 *
 * 分页实现的一个坑（已踩）：
 *   sortOrder 里拼接 "LIMIT n" 在部分 ROM / SQLite 版本上会直接抛
 *   "Invalid token LIMIT"，因为 sortOrder 会被原样拼进 SQL 尾部。
 *   正确做法是用 Bundle 查询参数（API 30+ 支持 QUERY_ARG_LIMIT / OFFSET），
 *   API 29 没有该参数，退化为「按 id 游标分批 + 不设上限」，
 *   每批由调用方自己决定何时停。
 */
class MediaStoreSource(private val context: Context) {

    private val collection: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.RELATIVE_PATH,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.DATE_MODIFIED,
    )

    /** 全量枚举。按 id 升序游标分批。 */
    suspend fun queryAllImages(
        pageSize: Int = 500,
        onPage: suspend (List<MediaShot>) -> Unit,
    ) = withContext(Dispatchers.IO) {
        walk(lastId = -1L, sinceSeconds = null, pageSize = pageSize, onPage = onPage)
    }

    /** 增量枚举：只要 dateModified >= since 的。 */
    suspend fun queryChangedSince(
        sinceSeconds: Long,
        pageSize: Int = 500,
        onPage: suspend (List<MediaShot>) -> Unit,
    ) = withContext(Dispatchers.IO) {
        // dateModified 精度到秒，回退 2 秒防边界漏
        walk(lastId = -1L, sinceSeconds = (sinceSeconds - 2).coerceAtLeast(0), pageSize = pageSize, onPage = onPage)
    }

    private suspend fun walk(
        lastId: Long,
        sinceSeconds: Long?,
        pageSize: Int,
        onPage: suspend (List<MediaShot>) -> Unit,
    ) {
        var cursor = lastId
        while (true) {
            val page = queryPage(cursor, sinceSeconds, pageSize)
            if (page.isEmpty()) break
            onPage(page)
            cursor = page.last().mediaStoreId
            if (page.size < pageSize) break
        }
    }

    private fun queryPage(lastId: Long, sinceSeconds: Long?, limit: Int): List<MediaShot> {
        val selection = buildString {
            append(MediaStore.Images.Media._ID).append(" > ?")
            if (sinceSeconds != null) {
                append(" AND ").append(MediaStore.Images.Media.DATE_MODIFIED).append(" >= ?")
            }
        }
        val args = if (sinceSeconds != null) {
            arrayOf(lastId.toString(), sinceSeconds.toString())
        } else {
            arrayOf(lastId.toString())
        }

        // 关键：sortOrder 里绝不能出现 LIMIT
        val sortOrder = "${MediaStore.Images.Media._ID} ASC"

        val queryArgs = Bundle().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
                putString(
                    android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    sortOrder,
                )
                putString(
                    android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                    selection,
                )
                putStringArray(
                    android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    args,
                )
            }
        }

        val out = ArrayList<MediaShot>(limit.coerceAtMost(1024))

        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.contentResolver.query(collection, projection, queryArgs, null)
        } else {
            // API 29：没有 QUERY_ARG_LIMIT，退回传统签名，不带 LIMIT
            context.contentResolver.query(collection, projection, selection, args, sortOrder)
        }

        cursor?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iPath = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val iW = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val iH = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val iMod = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

            var read = 0
            while (c.moveToNext() && read < limit) {
                val id = c.getLong(iId)
                val mime = c.getString(iMime) ?: "image/*"
                if (!mime.startsWith("image/")) continue
                out.add(
                    MediaShot(
                        mediaStoreId = id,
                        contentUri = ContentUris.withAppendedId(collection, id).toString(),
                        displayName = c.getString(iName) ?: "",
                        relativePath = c.getString(iPath) ?: "",
                        mimeType = mime,
                        fileSize = c.getLong(iSize),
                        width = c.getInt(iW),
                        height = c.getInt(iH),
                        dateAdded = c.getLong(iAdded) * 1000L,
                        dateModified = c.getLong(iMod) * 1000L,
                    )
                )
                read++
            }
        }
        return out
    }

    /** 单条按 id 复查，用于删除前确认文件仍存在。 */
    /**
     * 批量确认这些 mediaStoreId 是否还存在。
     *
     * ══ 为什么必须批量（R6 实测的 P0）══
     *
     * 旧实现 buildPlan 对每个 id 调一次 findById。
     * 实测单次 5ms，全选 8136 张 → 40 秒以上，
     * 用户点"清理"后界面转圈近一分钟，看起来就是卡死。
     *
     * 批量查询一次拿回全部存在的 id，复杂度从 O(n) 次跨进程调用
     * 降到 ceil(n/900) 次（SQLite 绑定参数上限 999）。
     */
    suspend fun filterExisting(mediaStoreIds: List<Long>): Set<Long> = withContext(Dispatchers.IO) {
        if (mediaStoreIds.isEmpty()) return@withContext emptySet()
        val out = HashSet<Long>(mediaStoreIds.size)
        mediaStoreIds.chunked(900).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val args = chunk.map { it.toString() }.toTypedArray()
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media._ID} IN ($placeholders)",
                args,
                null,
            )?.use { c ->
                val idx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (c.moveToNext()) out += c.getLong(idx)
            }
        }
        out
    }

    suspend fun findById(mediaStoreId: Long): MediaShot? = withContext(Dispatchers.IO) {
        context.contentResolver.query(
            ContentUris.withAppendedId(collection, mediaStoreId),
            projection,
            null,
            null,
            null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            MediaShot(
                mediaStoreId = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)),
                contentUri = ContentUris.withAppendedId(collection, mediaStoreId).toString(),
                displayName = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: "",
                relativePath = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)) ?: "",
                mimeType = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)) ?: "image/*",
                fileSize = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)),
                width = c.getInt(c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)),
                height = c.getInt(c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)),
                dateAdded = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)) * 1000L,
                dateModified = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)) * 1000L,
            )
        }
    }
}

/**
 * 截图识别分级。
 *
 * 产品的第一道判断题：什么算"截图"。
 * 判定必须保守 —— 宁可漏，不可错杀普通照片。
 */
object ScreenshotDetector {

    private val knownDirs = listOf(
        "pictures/screenshots",
        "dcim/screenshots",
        "pictures/screenshot",
        "dcim/screenshot",
        "pictures/截屏",
        "dcim/截屏",
        "pictures/截图",
        "dcim/截图",
    )

    private val namePrefixes = listOf(
        "screenshot_", "screenshot-", "screenshot ",
        "screen_shot", "screen-shot",
        "screenshot", "截屏", "截图", "屏幕截图",
    )

    fun classify(shot: MediaShot): ScreenshotConfidence {
        val path = shot.relativePath.lowercase().replace('\\', '/')
        val name = shot.displayName.lowercase()

        val inKnownDir = knownDirs.any { path.contains(it) }
        val named = namePrefixes.any { name.startsWith(it) }

        if (inKnownDir && named) return ScreenshotConfidence.HIGH
        if (inKnownDir || named) return ScreenshotConfidence.MEDIUM
        return ScreenshotConfidence.LOW
    }

    /**
     * 是否收录进库。
     * LOW 只在用户手动指定目录时收录，默认只收 HIGH / MEDIUM，
     * 避免把整个相册塞进"截图"。
     */
    fun shouldInclude(confidence: ScreenshotConfidence, userIncludedDirs: Set<String>, path: String): Boolean {
        if (confidence != ScreenshotConfidence.LOW) return true
        return userIncludedDirs.any { path.lowercase().startsWith(it.lowercase()) }
    }
}
