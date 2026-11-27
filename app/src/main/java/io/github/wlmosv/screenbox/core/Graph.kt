package io.github.wlmosv.screenbox.core

import android.content.Context
import io.github.wlmosv.screenbox.data.db.ScreenBoxDatabase
import io.github.wlmosv.screenbox.data.media.MediaStoreSource
import io.github.wlmosv.screenbox.data.repo.AnalysisRepository
import io.github.wlmosv.screenbox.data.repo.ClassifyRepository
import io.github.wlmosv.screenbox.data.repo.CleanupSuggestionRepository
import io.github.wlmosv.screenbox.data.repo.DeleteRepository
import io.github.wlmosv.screenbox.data.repo.DuplicateRepository
import io.github.wlmosv.screenbox.data.repo.OcrRepository
import io.github.wlmosv.screenbox.data.repo.ScanEngine
import io.github.wlmosv.screenbox.data.repo.ScreenshotQueryRepository
import io.github.wlmosv.screenbox.data.repo.SearchRepository
import io.github.wlmosv.screenbox.data.repo.TagRepository

/**
 * 手写依赖容器。
 *
 * 不上 Hilt —— 这个项目依赖图很简单，一个 object 就够。
 * 引入 DI 框架只会增加构建时间和心智负担。
 */
object Graph {

    @Volatile
    private var initialized = false

    lateinit var db: ScreenBoxDatabase
        private set
    lateinit var media: MediaStoreSource
        private set
    lateinit var scanEngine: ScanEngine
        private set
    lateinit var ocrRepo: OcrRepository
        private set
    lateinit var searchRepo: SearchRepository
        private set
    lateinit var duplicateRepo: DuplicateRepository
        private set
    lateinit var suggestionRepo: CleanupSuggestionRepository
        private set
    lateinit var deleteRepo: DeleteRepository
        private set
    lateinit var classifyRepo: ClassifyRepository
        private set
    lateinit var queryRepo: ScreenshotQueryRepository
        private set
    lateinit var analysisRepo: AnalysisRepository
        private set
    lateinit var tagRepo: TagRepository
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            db = ScreenBoxDatabase.get(app)
            media = MediaStoreSource(app)
            scanEngine = ScanEngine(app, db, media)
            ocrRepo = OcrRepository(app, db)
            searchRepo = SearchRepository(db)
            duplicateRepo = DuplicateRepository(db)
            suggestionRepo = CleanupSuggestionRepository(db)
            deleteRepo = DeleteRepository(app, db)
            classifyRepo = ClassifyRepository(db)
            queryRepo = ScreenshotQueryRepository(db)
            analysisRepo = AnalysisRepository(app, db)
            tagRepo = TagRepository(db)
            initialized = true
        }
    }
}
