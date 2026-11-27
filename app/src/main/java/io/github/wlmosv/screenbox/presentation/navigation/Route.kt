package io.github.wlmosv.screenbox.presentation.navigation

/**
 * 全应用路由表。
 *
 * 为什么集中定义：
 * v2 把路由散在 ScreenBoxApp.kt 里，首页三个统计入口全跳 CLEANUP，
 * 用户点"完全重复"和"看起来很像"进的是同一页，没有上下文。
 *
 * 现在每个功能入口有独立路由，各自带参数。
 */
object Route {
    // ── 底部导航（一级）──
    const val HOME = "home"
    const val GALLERY = "gallery"
    const val SEARCH = "search"
    const val CLEANUP = "cleanup"

    // ── 二级页面 ──
    const val SETTINGS = "settings"

    /** 关于页（从设置进入）。 */
    const val ABOUT = "about"

    /** 详情页。 */
    const val DETAIL = "detail/{id}"
    fun detail(id: Long) = "detail/$id"

    // ── 清理子页面 ──
    // 说明：R9 曾有 7 条独立子路由（完全重复/相似/分类/来源/过期/大文件/未识别），
    // 但全应用无人导航到它们 —— 首页入口早已改为统一 browse 与 workbench。
    // R10 删除这些死路由与对应页面，避免"看起来有两个入口、点进去内容一样"。







    /**
     * 删除清单。
     *
     * ══ R6 关键修正 ══
     *
     * 旧版把 id 拼进路由：`delete/plan/1000001,1000002,...`
     * 实测 500 张 = 3999 字符，8000 张 = 63999 字符，超过导航参数上限，
     * 会静默丢参数或抛异常 —— 用户以为删了 8000 张，实际删了几百张。
     *
     * 现在只传一个短 token，真实 id 走 PendingDeleteStore 暂存。
     */
    const val DELETE_PLAN = "delete/plan/{token}"
    fun deletePlan(ids: Collection<Long>, origin: String = ""): String {
        val token = io.github.wlmosv.screenbox.core.PendingDeleteStore.put(ids, origin)
        return "delete/plan/$token"
    }

    /**
     * 统一筛选浏览页。
     * preset 决定初始条件：content:chat / source:wechat / time:older90 / size:2mb
     * / dup:exact / dup:similar / favorite / protected / all
     */
    const val BROWSE = "browse/{preset}"
    fun browse(preset: String) = "browse/$preset"

    /** 时间轴批量清理。 */
    const val TIMELINE = "timeline"

    /** 清理工作台（类型总览 + 分类详情）。 */
    const val WORKBENCH = "workbench"

    /** 相似组列表。 */
    const val SIMILAR_GROUPS = "similar/groups"

    // ── 分组详情 ──
    /** 单个重复/相似组的对比页。 */
    const val GROUP_DETAIL = "group/{groupId}"
    fun group(id: Long) = "group/$id"
}

/** 底部导航项。 */
data class TabSpec(
    val route: String,
    val label: String,
)
