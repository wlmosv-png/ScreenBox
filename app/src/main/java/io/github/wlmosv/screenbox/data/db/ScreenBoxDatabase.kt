package io.github.wlmosv.screenbox.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Database(
    entities = [
        ScreenshotEntity::class,
        OcrEntity::class,
        OcrFtsEntity::class,
        CategoryEntity::class,
        ScreenshotCategoryEntity::class,
        TagEntity::class,
        ScreenshotTagEntity::class,
        DuplicateGroupEntity::class,
        DuplicateMemberEntity::class,
        CleanupSuggestionEntity::class,
        CleanupOperationEntity::class,
        CleanupOperationItemEntity::class,
        ScanStateEntity::class,
        TaskStateEntity::class,
        SavedFilterEntity::class,
        SearchHistoryEntity::class,
        SourceAppEntity::class,
        ScreenshotSourceEntity::class,
        StructuredEntityEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class ScreenBoxDatabase : RoomDatabase() {

    abstract fun screenshotDao(): ScreenshotDao
    abstract fun ocrDao(): OcrDao
    abstract fun categoryDao(): CategoryDao
    abstract fun duplicateDao(): DuplicateDao
    abstract fun cleanupDao(): CleanupDao
    abstract fun scanStateDao(): ScanStateDao
    abstract fun taskStateDao(): TaskStateDao
    abstract fun savedFilterDao(): SavedFilterDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun sourceAppDao(): SourceAppDao
    abstract fun entityDao(): StructuredEntityDao
    abstract fun tagDao(): TagDao

    companion object {
        private const val NAME = "screenbox.db"

        @Volatile
        private var instance: ScreenBoxDatabase? = null

        /**
         * v1 → v2：OCR 优先级队列。
         * 只加两列，都有默认值，不需要重建表。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE screenshots ADD COLUMN ocrPriority INTEGER NOT NULL DEFAULT 9")
                db.execSQL("ALTER TABLE screenshots ADD COLUMN ocrAttempts INTEGER NOT NULL DEFAULT 0")
                // 已有截图的优先级按日期回填：近 30 天=4，其余=9
                db.execSQL(
                    """
                    UPDATE screenshots SET ocrPriority =
                      CASE WHEN dateModified > (strftime('%s','now') - 30*24*3600) * 1000 THEN 4 ELSE 9 END
                    """.trimIndent()
                )
            }
        }

        /**
         * v2 → v3：任务状态持久化 + 保存筛选 + 搜索历史。
         *
         * 纯新增表，不动 screenshots / ocr / categories / duplicate_* 等任何已有表。
         * 8130 张截图、4375 条 OCR 结果、全部分类与重复分组零风险。
         * 回滚方式：卸载重装会重建库，但用户数据在 MediaStore 里不受影响。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `task_state` (
                      `kind` TEXT NOT NULL,
                      `status` TEXT NOT NULL,
                      `totalCount` INTEGER NOT NULL DEFAULT 0,
                      `doneCount` INTEGER NOT NULL DEFAULT 0,
                      `failedCount` INTEGER NOT NULL DEFAULT 0,
                      `skippedCount` INTEGER NOT NULL DEFAULT 0,
                      `startedAt` INTEGER,
                      `updatedAt` INTEGER NOT NULL DEFAULT 0,
                      `setExpandedAt` INTEGER,
                      `policy` TEXT NOT NULL DEFAULT 'RECENT_FIRST',
                      `lastError` TEXT,
                      PRIMARY KEY(`kind`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_filters` (
                      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      `name` TEXT NOT NULL,
                      `payload` TEXT NOT NULL,
                      `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `search_history` (
                      `keyword` TEXT NOT NULL,
                      `hitCount` INTEGER NOT NULL,
                      `lastUsedAt` INTEGER NOT NULL,
                      PRIMARY KEY(`keyword`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v3 → v4：来源应用维度。
         *
         * 纯新增 2 张表 + 预置来源应用清单。
         * 不动 screenshots / ocr / categories 等已有表。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `source_apps` (
                      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      `key` TEXT NOT NULL,
                      `displayName` TEXT NOT NULL,
                      `isSystem` INTEGER NOT NULL DEFAULT 1,
                      `sortOrder` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_source_apps_key` ON `source_apps` (`key`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `screenshot_sources` (
                      `screenshotId` INTEGER NOT NULL,
                      `sourceAppId` INTEGER NOT NULL,
                      `confidence` REAL NOT NULL,
                      `evidence` TEXT NOT NULL,
                      `isUserConfirmed` INTEGER NOT NULL DEFAULT 0,
                      `updatedAt` INTEGER NOT NULL DEFAULT 0,
                      PRIMARY KEY(`screenshotId`),
                      FOREIGN KEY(`screenshotId`) REFERENCES `screenshots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                      FOREIGN KEY(`sourceAppId`) REFERENCES `source_apps`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_screenshot_sources_screenshotId` ON `screenshot_sources` (`screenshotId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_screenshot_sources_sourceAppId` ON `screenshot_sources` (`sourceAppId`)")

                // 预置来源应用
                val apps = listOf(
                    Triple("wechat", "微信", 10),
                    Triple("qq", "QQ", 20),
                    Triple("xhs", "小红书", 30),
                    Triple("douyin", "抖音", 40),
                    Triple("taobao", "淘宝", 50),
                    Triple("alipay", "支付宝", 60),
                    Triple("weibo", "微博", 70),
                    Triple("bilibili", "哔哩哔哩", 80),
                    Triple("zhihu", "知乎", 90),
                    Triple("dingtalk", "钉钉", 100),
                    Triple("telegram", "Telegram", 110),
                    Triple("browser", "浏览器", 120),
                    Triple("settings", "系统设置", 130),
                    Triple("unknown", "来源不明", 999),
                )
                for ((k, name, order) in apps) {
                    db.execSQL(
                        "INSERT OR IGNORE INTO source_apps (`key`, displayName, isSystem, sortOrder) VALUES (?, ?, 1, ?)",
                        arrayOf(k, name, order),
                    )
                }
            }
        }

        /**
         * v4 → v5：结构化实体表。
         * 纯新增表，不动已有数据。
         * 实体由 OcrRepository 在识别完成时写入；历史数据靠 ClassifyRepository 回填。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `structured_entities` (
                      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      `screenshotId` INTEGER NOT NULL,
                      `kind` TEXT NOT NULL,
                      `raw` TEXT NOT NULL,
                      `normalized` TEXT NOT NULL,
                      `confidence` REAL NOT NULL,
                      `startPos` INTEGER NOT NULL,
                      FOREIGN KEY(`screenshotId`) REFERENCES `screenshots`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_structured_entities_screenshotId` ON `structured_entities` (`screenshotId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_structured_entities_kind_normalized` ON `structured_entities` (`kind`, `normalized`)")
            }
        }

        /**
         * v5 → v6：图像复杂度字段。
         *
         * 用途：区分"近乎空白"（误触截屏）和"内容丰富但无文字"（照片/游戏）。
         * 只加两列，都有默认值 null，不需要重建表。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE screenshots ADD COLUMN imageEntropy REAL")
                db.execSQL("ALTER TABLE screenshots ADD COLUMN colorVariance REAL")
            }
        }

        /**
         * v6 → v7：图片特征标签维度。
         *
         * tags 表原来只有 id + name，是死结构（无 DAO、无引擎、无 UI）。
         * 现在扩展成完整的三维度模型：
         *   内容用途（categories）
         *   来源应用（source_apps）
         *   图片特征（tags，本次）
         *
         * 全部是 ADD COLUMN + 默认值，不重建表、不丢数据。
         * 8137 张截图、8105 条 OCR 结果、全部分类与来源零风险。
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tags ADD COLUMN displayName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tags ADD COLUMN `group` TEXT NOT NULL DEFAULT 'FEATURE'")
                db.execSQL("ALTER TABLE tags ADD COLUMN isSystem INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tags ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                // 回填已有标签（正常情况为空表）
                db.execSQL("UPDATE tags SET displayName = name WHERE displayName = ''")
            }
        }

        /**
         * v7 -> v8: line-level OCR bounds.
         *
         * Block-level bounds are too coarse: one block is often a whole
         * paragraph spanning the full width, which flattens structural
         * signals like chat bubbles alternating left/right.
         *
         * Pure ADD COLUMN, no table rebuild, no existing data touched.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ocr ADD COLUMN lineBounds TEXT")
            }
        }

        fun get(context: Context): ScreenBoxDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): ScreenBoxDatabase =
            Room.databaseBuilder(context, ScreenBoxDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        // 外键约束必须在每次打开时显式开启，Room 不自动保证。
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                })
                .build()

        /**
         * 预置来源应用。幂等，可重复调用。
         * 新增的 key 会在下次启动时自动插入，不需要数据库迁移。
         */
        suspend fun seedSources(db: ScreenBoxDatabase) = withContext(Dispatchers.IO) {
            db.sourceAppDao().insertApps(
                listOf(
                    SourceAppEntity(key = "wechat", displayName = "微信", sortOrder = 10),
                    SourceAppEntity(key = "qq", displayName = "QQ", sortOrder = 20),
                    SourceAppEntity(key = "telegram", displayName = "Telegram", sortOrder = 25),
                    SourceAppEntity(key = "xhs", displayName = "小红书", sortOrder = 30),
                    SourceAppEntity(key = "douyin", displayName = "抖音", sortOrder = 40),
                    SourceAppEntity(key = "taobao", displayName = "淘宝", sortOrder = 50),
                    SourceAppEntity(key = "alipay", displayName = "支付宝", sortOrder = 60),
                    SourceAppEntity(key = "weibo", displayName = "微博", sortOrder = 70),
                    SourceAppEntity(key = "bilibili", displayName = "哔哩哔哩", sortOrder = 80),
                    SourceAppEntity(key = "zhihu", displayName = "知乎", sortOrder = 90),
                    SourceAppEntity(key = "boss", displayName = "BOSS直聘", sortOrder = 95),
                    SourceAppEntity(key = "github", displayName = "GitHub", sortOrder = 100),
                    SourceAppEntity(key = "dingtalk", displayName = "钉钉", sortOrder = 110),
                    SourceAppEntity(key = "office", displayName = "办公文档", sortOrder = 240),
                    SourceAppEntity(key = "dev_tool", displayName = "开发工具", sortOrder = 250),
                    SourceAppEntity(key = "sys_settings", displayName = "系统设置", sortOrder = 200),
                    SourceAppEntity(key = "browser", displayName = "浏览器", sortOrder = 210),
                    SourceAppEntity(key = "chat_generic", displayName = "聊天工具", sortOrder = 220),
                    SourceAppEntity(key = "game", displayName = "游戏应用", sortOrder = 230),
                    SourceAppEntity(key = "office", displayName = "办公文档", sortOrder = 240),
                    SourceAppEntity(key = "unknown", displayName = "来源不明", sortOrder = 999),
                )
            )
        }

        /** 预置图片特征标签。幂等，可重复调用。 */
        suspend fun seedTags(db: ScreenBoxDatabase) = withContext(Dispatchers.IO) {
            db.tagDao().insertAll(
                FeatureTag.entries.mapIndexed { index, tag ->
                    TagEntity(
                        name = tag.key,
                        displayName = tag.displayName,
                        group = TagGroup.FEATURE.name,
                        isSystem = true,
                        sortOrder = index * 10,
                    )
                }
            )
        }

        /** 预置系统分类。幂等，可重复调用。 */
        suspend fun seedCategories(db: ScreenBoxDatabase) = withContext(Dispatchers.IO) {
            db.categoryDao().insertAll(
                listOf(
                    CategoryEntity(key = "chat", displayName = "聊天记录", type = "content", sortOrder = 10),
                    CategoryEntity(key = "order", displayName = "购物订单", type = "content", sortOrder = 20),
                    CategoryEntity(key = "payment", displayName = "支付凭证", type = "content", sortOrder = 30),
                    CategoryEntity(key = "web", displayName = "网页资料", type = "content", sortOrder = 40),
                    CategoryEntity(key = "qrcode", displayName = "二维码", type = "content", sortOrder = 50),
                    CategoryEntity(key = "social", displayName = "社交内容", type = "content", sortOrder = 60),
                    CategoryEntity(key = "work", displayName = "工作资料", type = "content", sortOrder = 70),
                    CategoryEntity(key = "game", displayName = "游戏截图", type = "content", sortOrder = 80),
                    CategoryEntity(key = "receipt", displayName = "票据凭证", type = "content", sortOrder = 90),
                    CategoryEntity(key = "settings", displayName = "系统设置", type = "content", sortOrder = 95),
                    CategoryEntity(key = "notify", displayName = "通知与消息", type = "content", sortOrder = 96),
                    CategoryEntity(key = "account", displayName = "账号与验证码", type = "content", sortOrder = 97),
                    CategoryEntity(key = "map", displayName = "地图与出行", type = "content", sortOrder = 98),
                    CategoryEntity(key = "video", displayName = "娱乐与视频", type = "content", sortOrder = 99),
                    CategoryEntity(key = "study", displayName = "学习与笔记", type = "content", sortOrder = 100),
                    CategoryEntity(key = "code", displayName = "代码与技术", type = "content", sortOrder = 120),
                    CategoryEntity(key = "document", displayName = "文档与文字资料", type = "content", sortOrder = 130),
                    CategoryEntity(key = "app_ui", displayName = "应用界面", type = "content", sortOrder = 500),
                    CategoryEntity(key = "other", displayName = "其他", type = "content", sortOrder = 999),
                )
            )
        }
    }
}
