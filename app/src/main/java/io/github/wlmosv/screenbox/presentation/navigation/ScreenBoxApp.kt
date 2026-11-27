package io.github.wlmosv.screenbox.presentation.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.wlmosv.screenbox.presentation.about.AboutScreen
import io.github.wlmosv.screenbox.presentation.cleanup.WorkbenchScreen
import io.github.wlmosv.screenbox.presentation.cleanup.SimilarGroupsScreen
import io.github.wlmosv.screenbox.presentation.browse.TimelineScreen
import io.github.wlmosv.screenbox.presentation.browse.BrowseScreen
import io.github.wlmosv.screenbox.domain.query.ShotFilter
import io.github.wlmosv.screenbox.domain.query.TimeWindow
import io.github.wlmosv.screenbox.presentation.cleanup.CompareScreen
import io.github.wlmosv.screenbox.presentation.cleanup.DeletePlanScreen
import io.github.wlmosv.screenbox.presentation.cleanup.GroupDetailScreen
import io.github.wlmosv.screenbox.presentation.detail.DetailScreen
import io.github.wlmosv.screenbox.presentation.gallery.GalleryScreen
import io.github.wlmosv.screenbox.presentation.home.HomeScreen
import io.github.wlmosv.screenbox.presentation.search.SearchScreen
import io.github.wlmosv.screenbox.presentation.settings.SettingsScreen

private data class TabItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val tabs = listOf(
    TabItem(Route.HOME, "首页", Icons.Outlined.Home),
    TabItem(Route.GALLERY, "截图", Icons.Outlined.PhotoLibrary),
    TabItem(Route.SEARCH, "搜索", Icons.Outlined.Search),
    TabItem(Route.CLEANUP, "清理", Icons.Outlined.CleaningServices),
)

/**
 * 应用导航。
 *
 * v2 的三个 bug 已修：
 * 1. 首页所有统计入口都跳 CLEANUP → 现在每个入口有独立路由
 * 2. navigate() 不带 launchSingleTop → 重复点击会重复压栈，需要多按返回
 * 3. 详情页压在 tab 栈里 → 现在二级页面统一从当前 tab 压栈，返回行为可预期
 */
@Composable
fun ScreenBoxApp(
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val showBottomBar = currentRoute in tabs.map { it.route }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.background,
                    tonalElevation = 0.dp,
                ) {
                    tabs.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Route.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Route.HOME) {
                HomeScreen(
                    onOpenGallery = { navController.push(Route.GALLERY) },
                    onOpenSearch = { navController.push(Route.SEARCH) },
                    onOpenDetail = { navController.push(Route.detail(it)) },
                    onOpenSettings = { navController.push(Route.SETTINGS) },
                    onRequestPermission = onRequestPermission,
                    onOpenAppSettings = onOpenAppSettings,
                    // 全部走统一筛选页，只是初始条件不同
                    onOpenExactDuplicates = { navController.push(Route.browse("dup:exact")) },
                    onOpenSimilar = { navController.push(Route.SIMILAR_GROUPS) },
                    onOpenSuggestions = { navController.push(Route.CLEANUP) },
                    onOpenBrowse = { navController.push(Route.browse("all")) },
                    onOpenTimeline = { navController.push(Route.TIMELINE) },
                    onOpenExpired = { navController.push(Route.browse("expired")) },
                    onOpenLarge = { navController.push(Route.browse("size:2mb")) },
                    onOpenUnrecognized = { navController.push(Route.browse("unrecognized")) },
                )
            }
            composable(Route.GALLERY) {
                GalleryScreen(
                    onOpenDetail = { navController.push(Route.detail(it)) },
                    onDelete = { ids -> navController.push(Route.deletePlan(ids)) },
                )
            }
            composable(Route.SEARCH) {
                SearchScreen(onOpenDetail = { navController.push(Route.detail(it)) })
            }
            composable(Route.CLEANUP) {
                WorkbenchScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { navController.push(Route.detail(it)) },
                    onOpenSimilarGroups = { navController.push(Route.SIMILAR_GROUPS) },
                    onRequestDelete = { ids -> navController.push(Route.deletePlan(ids)) },
                )
            }
            composable(Route.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAbout = { navController.push(Route.ABOUT) },
                )
            }
            composable(Route.ABOUT) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
            composable(Route.DETAIL) { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
                DetailScreen(
                    screenshotId = id,
                    onBack = { navController.popBackStack() },
                    onDelete = { single -> navController.push(Route.deletePlan(setOf(single))) },
                )
            }

            // ── 清理子页面（各自独立，带上下文）──
            composable(Route.TIMELINE) {
                TimelineScreen(
                    onBack = { navController.popBackStack() },
                    onRequestDelete = { ids -> navController.push(Route.deletePlan(ids)) },
                )
            }
            composable(Route.WORKBENCH) {
                WorkbenchScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { navController.push(Route.detail(it)) },
                    onOpenSimilarGroups = { navController.push(Route.SIMILAR_GROUPS) },
                    onRequestDelete = { ids -> navController.push(Route.deletePlan(ids)) },
                )
            }
            composable(Route.SIMILAR_GROUPS) {
                SimilarGroupsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenGroup = { navController.push(Route.group(it)) },
                )
            }
            composable(Route.BROWSE) { entry ->
                val preset = entry.arguments?.getString("preset").orEmpty()
                val filter = presetToFilter(preset)
                BrowseScreen(
                    initialFilter = filter,
                    title = presetTitle(preset),
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { navController.push(Route.detail(it)) },
                    onRequestDelete = { ids -> navController.push(Route.deletePlan(ids)) },
                )
            }
            composable(Route.GROUP_DETAIL) { entry ->
                val gid = entry.arguments?.getString("groupId")?.toLongOrNull() ?: return@composable
                CompareScreen(
                    groupId = gid,
                    onBack = { navController.popBackStack() },
                    onRequestDelete = { ids -> navController.push(Route.deletePlan(ids)) },
                )
            }
            composable(Route.DELETE_PLAN) { entry ->
                val token = entry.arguments?.getString("token").orEmpty()
                DeletePlanScreen(
                    token = token,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/**
 * 统一压栈策略。
 *
 * launchSingleTop 防止重复压入同一目的地 —— 这是 v2"需要多按返回"的根因。
 */
private fun NavHostController.push(route: String) {
    navigate(route) {
        launchSingleTop = true
    }
}

/**
 * 底部导航切换。
 *
 * 用 popUpTo(start) + saveState/restoreState 保持各 tab 的滚动位置，
 * 同时避免栈无限增长。
 */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}


/**
 * 把 preset 字符串转成筛选条件。
 *
 * 统一入口：首页的每个"整理"入口都跳这里，只是初始条件不同。
 * 用户在页面里可以继续叠加条件（比如从"微信"再筛"90天前"）。
 */
private fun presetToFilter(preset: String): ShotFilter {
    val now = System.currentTimeMillis()
    return when {
        preset.startsWith("content:") ->
            ShotFilter(contentCategories = setOf(preset.removePrefix("content:")))

        preset.startsWith("source:") ->
            ShotFilter(sourceApps = setOf(preset.removePrefix("source:")))

        preset == "time:older90" -> ShotFilter(timeWindow = TimeWindow.olderThan(90, now))
        preset == "time:older365" -> ShotFilter(timeWindow = TimeWindow.olderThan(365, now))

        preset == "size:2mb" -> ShotFilter(minBytes = 2L * 1024 * 1024)
        preset == "size:5mb" -> ShotFilter(minBytes = 5L * 1024 * 1024)

        preset == "dup:exact" -> ShotFilter(exactDuplicateOnly = true)
        preset == "dup:similar" -> ShotFilter(similarOnly = true)
        preset == "dup:redundant" -> ShotFilter(redundantOnly = true)

        preset == "expired" -> ShotFilter(suggestionType = "POSSIBLY_EXPIRED")
        preset == "blank" -> ShotFilter(suggestionType = "NEAR_BLANK")
        preset == "onetime" -> ShotFilter(suggestionType = "ONE_TIME_CODE")

        preset == "favorite" -> ShotFilter(favorite = true)
        preset == "protected" -> ShotFilter(protected = true)

        preset == "unrecognized" -> ShotFilter(
            ocrStatuses = setOf(
                io.github.wlmosv.screenbox.data.db.OcrStatus.NOT_STARTED,
                io.github.wlmosv.screenbox.data.db.OcrStatus.QUEUED,
                io.github.wlmosv.screenbox.data.db.OcrStatus.FAILED,
            )
        )

        else -> ShotFilter()
    }
}

private fun presetTitle(preset: String): String = when {
    preset.startsWith("content:") -> "按内容浏览"
    preset.startsWith("source:") -> "按来源浏览"
    preset == "time:older90" -> "90 天以前的截图"
    preset == "time:older365" -> "一年以前的截图"
    preset == "size:2mb" -> "大于 2 MB"
    preset == "size:5mb" -> "大于 5 MB"
    preset == "dup:exact" -> "完全重复"
    preset == "dup:similar" -> "画面相似"
    preset == "dup:redundant" -> "冗余图片"
    preset == "favorite" -> "收藏"
    preset == "protected" -> "已保护"
    preset == "unrecognized" -> "未识别"
    else -> "全部截图"
}
