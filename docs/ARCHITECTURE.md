# 架构

本文档说明 ScreenBox 的工程结构与数据流。ScreenBox 是一个 Kotlin + Jetpack Compose 的 Android 应用，采用分层架构。

## 分层

```
presentation/      UI 层：Compose 屏幕、ViewModel、导航
   ↓
domain/            领域层：纯逻辑，无 Android 依赖（可单测）
   ↓
data/              数据层：Repository、Room 数据库、媒体访问、OCR
   ↓
worker/           后台任务：WorkManager、前台回填通道、任务恢复
```

依赖方向自上而下，领域层不依赖 Android 框架，因此可以用纯 JVM 单元测试覆盖核心判定逻辑。

## 模块说明

### presentation（UI 层）

| 目录 | 职责 |
|---|---|
| `home/` | 首页：权限门、扫描、统计卡、任务入口 |
| `gallery/` | 图库：网格浏览、筛选、批量选择 |
| `search/` | 搜索：全文搜索、筛选、保存条件 |
| `browse/` | 浏览：统一筛选浏览页、时间轴 |
| `cleanup/` | 清理：工作台、相似组、对比、删除清单 |
| `detail/` | 详情：图片预览、OCR 文字、分类、收藏/保护 |
| `settings/` | 设置：统计、行级坐标回填、重建操作 |
| `about/` | 关于：品牌、能力、项目链接、许可 |
| `navigation/` | 路由与 NavHost |

每个屏幕配一个 ViewModel，通过 Repository 访问数据，UI 不直接操作数据库。

### domain（领域层）

纯 Kotlin，无 Android 依赖，核心判定逻辑都在此，便于单元测试：

| 文件 | 职责 |
|---|---|
| `rules/ScreenshotClassifierV2.kt` | 内容分类：qrcode / game / order / social / chat，无兜底桶 |
| `rules/SourceDetector.kt` | 来源检测：识别截图来自哪个应用 |
| `rules/FeatureTagEngine.kt` | 特征标签：长截图、二维码、近空白、精确重复等 |
| `rules/LayoutAnalyzer.kt` | 布局几何分析（纯函数，评估用） |
| `rules/ScreenshotClassifier.kt` | 旧版分类器（仅 `detectSensitive` 活跃） |
| `query/SearchQueryParser.kt` | 搜索语法解析 |
| `query/ShotFilter.kt` | 组合筛选模型 |
| `query/SavedFilterCodec.kt` | 筛选条件持久化编码 |
| `extract/EntityExtractor.kt` | 结构化实体提取 |

### data（数据层）

| 目录 | 职责 |
|---|---|
| `db/` | Room 数据库、DAO、实体（DB v8，19 张表） |
| `media/` | MediaStore 扫描、SHA-256 / pHash、图像复杂度 |
| `ocr/` | ML Kit OCR 封装（本地中文模型，行级 + 块级坐标） |
| `repo/` | 各功能 Repository：扫描、OCR、分类、查询、清理、删除、重复、标签 |

关键 Repository：

- `ScanEngine`：全量 / 增量扫描，指纹（SHA-256 + pHash）分阶段执行。
- `OcrRepository`：OCR 队列、并发、重试、设备健康保护，结构化实体与分类写入。
- `ScreenshotQueryRepository`：统一动态 SQL 引擎，供浏览 / 搜索 / 删除共用。
- `CleanupSuggestionRepository`：清理建议生成，每条带原因与证据。
- `DeleteRepository`：删除计划、系统授权、结果诚实记录。
- `DuplicateRepository`：精确重复（SHA-256）与视觉相似（pHash）分组。

### worker（后台任务）

| 文件 | 职责 |
|---|---|
| `Workers.kt` | OCR / 指纹 WorkManager 任务 |
| `LineBackfillWorker.kt` | 行级坐标后台回填 |
| `LineBackfillRunner.kt` | 行级坐标前台快跑通道 |
| `TaskRecovery.kt` | 进程启动时校正任务状态并重新入队 |

## 核心数据流

```
系统媒体库
   │  MediaStore 授权读取
   ▼
ScanEngine ── 扫描截图 ──▶ Room(screenshots)
   │                           │
   │ OCR（ML Kit 本地模型）      │ SHA-256 / pHash
   ▼                           ▼
OcrRepository ──▶ Room(ocr, ocr_fts, structured_entities)
   │
   ├─▶ ScreenshotClassifierV2 ──▶ Room(categories, screenshot_categories)
   ├─▶ SourceDetector ──▶ Room(source_apps, screenshot_sources)
   └─▶ FeatureTagEngine ──▶ Room(tags, screenshot_tags)

用户查询/清理/删除 ──▶ ScreenshotQueryRepository / CleanupSuggestionRepository / DeleteRepository
```

## 关键设计决策

- **本地优先，无网络。** 不申请 `INTERNET` 权限，OCR 模型随包打包。
- **不硬猜。** 分类器无兜底桶，不确定就留空；清理建议必须有依据。
- **删除诚实。** 删除走系统授权，结果如实记录，不假报成功。
- **领域层可测。** 核心判定逻辑抽成纯函数，用单元测试覆盖。
