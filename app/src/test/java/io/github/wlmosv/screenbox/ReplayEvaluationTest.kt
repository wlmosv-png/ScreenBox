package io.github.wlmosv.screenbox

import io.github.wlmosv.screenbox.domain.rules.FeatureTagEngine
import io.github.wlmosv.screenbox.domain.rules.ScreenshotClassifier
import io.github.wlmosv.screenbox.domain.rules.SourceDetector
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 真实数据回放评测。
 *
 * 把设备上导出的 OCR 文本（.r5/replay.tsv）重新喂给分类器与来源识别，
 * 统计覆盖率与来源分布，验证 R5 的规则修改是否真的有效。
 *
 * 这不是单元测试的断言集合 —— 它是**度量工具**。
 * 文件不存在时自动跳过，不会污染常规测试。
 *
 * 格式：id \t w \t h \t size \t entropy \t variance \t ocrStatus \t text
 */
class ReplayEvaluationTest {

    private val replay = File("/workspace/screenbox/.r5/replay.tsv")

    private data class Row(
        val id: Long,
        val w: Int,
        val h: Int,
        val size: Long,
        val entropy: Float?,
        val variance: Float?,
        val ocrStatus: String,
        val text: String,
    )

    private fun load(): List<Row> {
        if (!replay.exists()) return emptyList()
        return replay.readLines(Charsets.UTF_8).mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 8) return@mapNotNull null
            Row(
                id = parts[0].toLongOrNull() ?: return@mapNotNull null,
                w = parts[1].toIntOrNull() ?: 0,
                h = parts[2].toIntOrNull() ?: 0,
                size = parts[3].toLongOrNull() ?: 0,
                entropy = parts[4].toFloatOrNull(),
                variance = parts[5].toFloatOrNull(),
                ocrStatus = parts[6],
                text = parts[7].replace("\\n", "\n"),
            )
        }
    }

    @Test
    fun `真实数据覆盖率与来源分布`() {
        val rows = load()
        assumeTrue("无回放数据，跳过", rows.isNotEmpty())

        var categorized = 0
        var sourceIdentified = 0
        val catDist = mutableMapOf<String, Int>()
        val srcDist = mutableMapOf<String, Int>()
        val tagDist = mutableMapOf<String, Int>()

        for (r in rows) {
            val hits = ScreenshotClassifier.classify(r.text, r.w, r.h)
            if (hits.isNotEmpty()) categorized++
            for (h in hits) {
                catDist.merge(h.categoryKey, 1, Int::plus)
            }

            val src = SourceDetector.detect(r.text)
            if (src != null) sourceIdentified++
            srcDist.merge(src?.appKey ?: "unknown", 1, Int::plus)

            val tags = FeatureTagEngine.compute(
                FeatureTagEngine.Input(
                    width = r.w,
                    height = r.h,
                    fileSize = r.size,
                    imageEntropy = r.entropy,
                    colorVariance = r.variance,
                    ocrStatus = r.ocrStatus,
                    ocrAttempts = 0,
                    recognizedText = r.text,
                    duplicateGroupType = null,
                )
            )
            for (t in tags) tagDist.merge(t.tag.key, 1, Int::plus)
        }

        val n = rows.size
        val report = buildString {
            appendLine("=== R5 规则回放评测（真实数据 n=$n）===")
            appendLine("分类覆盖: $categorized / $n = %.1f%%".format(categorized * 100.0 / n))
            appendLine("来源识别: $sourceIdentified / $n = %.1f%%".format(sourceIdentified * 100.0 / n))
            appendLine("来源不明: ${n - sourceIdentified} = %.1f%%".format((n - sourceIdentified) * 100.0 / n))
            appendLine()
            appendLine("-- 内容分类分布 --")
            catDist.entries.sortedByDescending { it.value }.forEach {
                appendLine("  ${it.key}: ${it.value}")
            }
            appendLine()
            appendLine("-- 来源应用分布 --")
            srcDist.entries.sortedByDescending { it.value }.forEach {
                appendLine("  ${it.key}: ${it.value}")
            }
            appendLine()
            appendLine("-- 特征标签分布 --")
            tagDist.entries.sortedByDescending { it.value }.forEach {
                appendLine("  ${it.key}: ${it.value}")
            }
        }

        File("/workspace/screenbox/.r5/replay_report.txt").writeText(report, Charsets.UTF_8)
        println(report)

        // 覆盖率必须实质提升（R4 实测 21%）
        assertTrue("分类覆盖率应显著高于 R4 的 21%，实际 ${categorized * 100.0 / n}%",
            categorized * 100.0 / n > 60.0)
    }
}