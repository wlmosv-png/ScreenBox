package io.github.wlmosv.screenbox.core

import java.util.concurrent.atomic.AtomicLong

/**
 * 跨页面传递"待删除 id 集合"的临时暂存区。
 *
 * ══ 为什么必须这样做（R6 实测的 P0）══
 *
 * 旧实现把 id 拼进导航路由：`delete/plan/1000001,1000002,...`
 * 实测：500 张 id 拼出来 3999 字符，8000 张是 63999 字符。
 * Android 导航参数存在实际长度上限（通常几千字符），
 * 超过后要么抛异常、要么静默丢参数 —— 用户点了"删除"却什么都没发生，
 * 或者只删掉前几百张。这是最危险的一类 bug：**静默的错误删除范围**。
 *
 * ══ 方案 ══
 *
 * 用一个进程内的暂存区，导航只传一个短 token。
 * 页面拿到 token 后从这里取真实 id 列表，取完即清（单次消费）。
 *
 * 为什么不用 ViewModel 共享 / SavedStateHandle：
 * - 跨 NavHost 共享需要把 ViewModel 提到 Activity 作用域，
 *   会让删除清单的生命周期与整个应用耦合，返回后残留脏数据。
 * - 暂存区是显式的、一次性的，用完即清，语义最清晰。
 *
 * 进程被杀的情况：token 失效，页面会显示"选择已失效，请重新选择"。
 * 这比静默删除错误范围要好得多。
 */
object PendingDeleteStore {

    /** 一次暂存的内容。 */
    data class Entry(
        val ids: List<Long>,
        val createdAt: Long,
        /** 来源描述，用于界面提示"来自：搜索'订单'"之类。 */
        val origin: String = "",
    )

    private val counter = AtomicLong(0)
    private val store = object : LinkedHashMap<String, Entry>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MAX_ENTRIES
    }

    /** 超过这个数量的暂存条目就淘汰最旧的，防止泄漏。 */
    private const val MAX_ENTRIES = 8

    /** 超过这个时间未消费的暂存视为失效（10 分钟）。 */
    private const val TTL_MS = 10 * 60 * 1000L

    /** 暂存一批 id，返回可直接放进路由的短 token。 */
    @Synchronized
    fun put(ids: Collection<Long>, origin: String = ""): String {
        val token = "sel${counter.incrementAndGet()}"
        store[token] = Entry(ids.toList(), System.currentTimeMillis(), origin)
        return token
    }

    /**
     * 取出并消费。
     *
     * @return null 表示 token 不存在或已过期 —— 调用方必须处理这个情况，
     *         不能当成空列表继续走删除流程。
     */
    @Synchronized
    fun take(token: String): Entry? {
        val e = store.remove(token) ?: return null
        if (System.currentTimeMillis() - e.createdAt > TTL_MS) return null
        return e
    }

    /** 只查看不消费（页面重组时重复读取用）。 */
    @Synchronized
    fun peek(token: String): Entry? {
        val e = store[token] ?: return null
        if (System.currentTimeMillis() - e.createdAt > TTL_MS) return null
        return e
    }

    @Synchronized
    fun clear() = store.clear()
}