package io.github.wlmosv.screenbox.presentation.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.wlmosv.screenbox.core.Graph
import io.github.wlmosv.screenbox.data.db.CategoryEntity
import io.github.wlmosv.screenbox.data.db.OcrEntity
import io.github.wlmosv.screenbox.data.db.ProtectionStatus
import io.github.wlmosv.screenbox.data.db.ScreenshotEntity
import io.github.wlmosv.screenbox.data.db.ScreenshotCategoryEntity
import io.github.wlmosv.screenbox.domain.rules.ScreenshotClassifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DetailUiState(
    val shot: ScreenshotEntity? = null,
    val ocr: OcrEntity? = null,
    val categories: List<Pair<CategoryEntity, ScreenshotCategoryEntity>> = emptyList(),
    val sensitive: List<ScreenshotClassifier.SensitiveHit> = emptyList(),
    val allCategories: List<CategoryEntity> = emptyList(),
    val loading: Boolean = true,
    val reprocessing: Boolean = false,
)

class DetailViewModel(app: Application) : AndroidViewModel(app) {

    private val db = Graph.db
    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private var currentId: Long = -1

    fun load(id: Long) {
        currentId = id
        viewModelScope.launch {
            val shot = db.screenshotDao().byId(id)
            if (shot == null) {
                _uiState.value = _uiState.value.copy(loading = false)
                return@launch
            }
            db.screenshotDao().touchViewed(id, System.currentTimeMillis())
            val ocr = db.ocrDao().byScreenshot(id)
            val allCats = db.categoryDao().allNow()
            val rels = db.categoryDao().relationsFor(id)
            val catPairs = rels.mapNotNull { rel ->
                allCats.firstOrNull { it.id == rel.categoryId }?.let { it to rel }
            }
            val sensitive = ocr?.let { ScreenshotClassifier.detectSensitive(it.recognizedText) } ?: emptyList()

            _uiState.value = DetailUiState(
                shot = shot,
                ocr = ocr,
                categories = catPairs,
                sensitive = sensitive,
                allCategories = allCats,
                loading = false,
            )
        }
    }

    fun toggleFavorite() {
        val shot = _uiState.value.shot ?: return
        viewModelScope.launch {
            if (!shot.isFavorite) {
                Graph.suggestionRepo.favoriteAndClear(shot.id)
            } else {
                db.screenshotDao().setFavorite(shot.id, false, System.currentTimeMillis())
            }
            load(currentId)
        }
    }

    fun toggleProtect() {
        val shot = _uiState.value.shot ?: return
        viewModelScope.launch {
            if (shot.protectionStatus == ProtectionStatus.PROTECTED) {
                Graph.suggestionRepo.unprotect(shot.id)
            } else {
                // 保护必须连带清掉该图的 OPEN 建议，否则统计会虚高
                Graph.suggestionRepo.protectAndClear(shot.id)
            }
            load(currentId)
        }
    }

    fun setUserCategory(categoryId: Long) {
        viewModelScope.launch {
            db.screenshotDao().setUserCategory(currentId, categoryId, System.currentTimeMillis())
            // 同时写入关联表并标记为用户确认，自动任务不会覆盖
            runCatching {
                db.categoryDao().upsertRelation(
                    io.github.wlmosv.screenbox.data.db.ScreenshotCategoryEntity(
                        screenshotId = currentId,
                        categoryId = categoryId,
                        confidence = 1f,
                        source = "user",
                        isUserConfirmed = true,
                    )
                )
            }
            load(currentId)
        }
    }

    /** 用户手动指定来源应用。自动识别永不再覆盖。 */
    fun setUserSource(appId: Long) {
        viewModelScope.launch {
            db.sourceAppDao().setUserSource(currentId, appId, System.currentTimeMillis())
            load(currentId)
        }
    }

    fun reprocessOcr() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(reprocessing = true)
            runCatching { Graph.ocrRepo.reprocess(currentId) }
            runCatching { Graph.ocrRepo.run(maxCount = 1) }
            _uiState.value = _uiState.value.copy(reprocessing = false)
            load(currentId)
        }
    }
}
