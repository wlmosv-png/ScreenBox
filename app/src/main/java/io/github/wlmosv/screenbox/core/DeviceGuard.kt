package io.github.wlmosv.screenbox.core

import java.io.File

/**
 * 设备健康守卫。
 *
 * 为什么需要它：
 * 实测 3 并发跑 OCR，温度从 70.5°C 升到 89.2°C，RSS 到 505MB。
 * 这是"用户手机"，不是服务器。烫手和耗电都是真实伤害。
 *
 * 策略：
 * - 温度 >= 高阈值：暂停任务，等降到低阈值再继续（迟滞，避免抖动）
 * - RSS >= 高阈值：强制 GC + 暂停一轮
 * - 所有阈值都留了余量，不是拍脑袋
 */
object DeviceGuard {

    /** 超过此温度暂停。手机 SoC 一般在 85°C 附近开始降频。 */
    private const val TEMP_PAUSE_C = 80.0

    /** 降到此温度以下才恢复。 */
    private const val TEMP_RESUME_C = 72.0

    /** 超过此 RSS 触发 GC。 */
    private const val RSS_GC_MB = 420

    /** 超过此 RSS 暂停一轮。 */
    private const val RSS_PAUSE_MB = 520

    data class Health(
        val tempC: Double,
        val rssMb: Int,
        val shouldPause: Boolean,
        val reason: String?,
    )

    fun read(): Health {
        val t = readTemp()
        val rss = readRss()
        val pause = when {
            t >= TEMP_PAUSE_C -> true
            rss >= RSS_PAUSE_MB -> true
            else -> false
        }
        val reason = when {
            t >= TEMP_PAUSE_C -> "设备温度 %.1f°C 偏高，暂停散热".format(t)
            rss >= RSS_PAUSE_MB -> "内存占用 ${rss}MB 偏高，暂停释放".format(rss)
            else -> null
        }
        return Health(t, rss, pause, reason)
    }

    /** 是否可以恢复。温度必须降到恢复阈值以下（迟滞）。 */
    fun canResume(): Boolean {
        val t = readTemp()
        val rss = readRss()
        return t < TEMP_RESUME_C && rss < RSS_GC_MB
    }

    fun readTemp(): Double {
        var max = 0.0
        runCatching {
            File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }?.forEach { z ->
                val t = runCatching { File(z, "temp").readText().trim().toDouble() / 1000.0 }.getOrDefault(0.0)
                if (t > 0 && t < 120 && t > max) max = t
            }
        }
        return max
    }

    fun readRss(): Int = runCatching {
        File("/proc/self/status").readLines()
            .first { it.startsWith("VmRSS") }
            .split(Regex("\\s+"))[1].toInt() / 1024
    }.getOrDefault(0)

    /** 主动释放一轮内存。 */
    fun reclaimMemory() {
        runCatching {
            System.gc()
            Runtime.getRuntime().run { freeMemory() }
        }
    }
}
