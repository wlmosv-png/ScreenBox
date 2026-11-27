#!/usr/bin/env python3
"""
用真机数据库验证「布局特征能否区分 chat」。

背景：
  R8 建了 LayoutAnalyzer，但从未接入分类器 —— 它至今是死代码。
  R10 必须先回答一个问题：行级/块级坐标到底有没有区分度？
  如果没有，接进分类器只是增加复杂度和误判。

方法：
  从真机 db 取 chat 与其他类的 blockBounds，
  在 Python 里复刻 LayoutAnalyzer 的几何计算（同一套公式），
  比较分布，看是否存在可用阈值。

结论用数据说话，不用直觉。
"""
import json
import sqlite3
import statistics
import sys
from collections import defaultdict

DB = sys.argv[1] if len(sys.argv) > 1 else "z.db"


def load(conn):
    """取每张图的类标签、尺寸、块坐标。"""
    q = """
    SELECT s.id, s.width, s.height, o.blockBounds,
           (SELECT c.`key` FROM screenshot_categories sc
              JOIN categories c ON c.id = sc.categoryId
             WHERE sc.screenshotId = s.id LIMIT 1) AS cat
    FROM screenshots s
    JOIN ocr o ON o.screenshotId = s.id
    WHERE o.blockBounds IS NOT NULL AND o.blockBounds != ''
      AND s.protectionStatus NOT IN ('DELETED','MISSING')
    """
    rows = []
    for sid, w, h, bb, cat in conn.execute(q):
        if not w or not h:
            continue
        try:
            boxes = json.loads(bb)
        except Exception:
            continue
        if len(boxes) < 3:
            continue
        rows.append((sid, w, h, boxes, cat))
    return rows


def features(boxes, w, h):
    """复刻 LayoutAnalyzer 的几何特征（归一化 0..1）。"""
    bs = []
    for b in boxes:
        l, t, r, bo = b.get("l"), b.get("t"), b.get("r"), b.get("b")
        if None in (l, t, r, bo):
            continue
        bs.append((l / w, t / h, r / w, bo / h))
    if len(bs) < 3:
        return None

    widths = [max(0.0, r - l) for l, t, r, bo in bs]
    heights = [max(0.0, bo - t) for l, t, r, bo in bs]
    centers = [(l + r) / 2 for l, t, r, bo in bs]

    def cv(xs):
        m = statistics.fmean(xs) if xs else 0.0
        if m <= 0:
            return 0.0
        return statistics.pstdev(xs) / m

    # 左右交错：中心偏离 0.5 的平均绝对距离，两侧都有块才算交错
    left = [c for c in centers if c < 0.42]
    right = [c for c in centers if c > 0.58]
    alternation = 0.0
    if left and right:
        alternation = min(len(left), len(right)) / len(centers)

    area = sum(widths[i] * heights[i] for i in range(len(bs)))

    top = sum(1 for l, t, r, bo in bs if bo < 0.15)
    bottom = sum(1 for l, t, r, bo in bs if t > 0.85)
    middle = len(bs) - top - bottom

    # 左对齐率：左边缘落在最常见的 5% 区间内
    buckets = defaultdict(int)
    for l, t, r, bo in bs:
        buckets[round(l * 20)] += 1
    left_align = max(buckets.values()) / len(bs)

    # 中央空白：中心 40%-60% 竖带内没有块中心
    center_band = [c for c in centers if 0.4 <= c <= 0.6]
    center_blank = 1.0 - len(center_band) / len(bs)

    # 多列：同一纵向 10% 带里出现 >=2 个块
    rows = defaultdict(int)
    for l, t, r, bo in bs:
        rows[round(((t + bo) / 2) * 10)] += 1
    multi = sum(1 for v in rows.values() if v >= 2) / max(1, len(rows))

    return {
        "n": len(bs),
        "width_cv": cv(widths),
        "height_cv": cv(heights),
        "alt": alternation,
        "top": top / len(bs),
        "bottom": bottom / len(bs),
        "left_align": left_align,
        "center_blank": center_blank,
        "multi": multi,
        "area": area,
        "avg_h": statistics.fmean(heights),
        "span": max(bo for l, t, r, bo in bs) - min(t for l, t, r, bo in bs),
    }


def summarize(name, vals):
    if not vals:
        print(f"{name:<14} 样本 0")
        return
    def q(xs, p):
        xs = sorted(xs)
        return xs[min(len(xs) - 1, int(len(xs) * p))]
    print(
        f"{name:<14} n={len(vals):<5} "
        f"alt中位={q(vals,'alt' if False else 0.5):.3f} "
    )


def main():
    conn = sqlite3.connect(DB)
    rows = load(conn)
    print(f"可用样本 {len(rows)} 张（有块坐标且 >=3 块）\n")

    by_cat = defaultdict(list)
    for sid, w, h, boxes, cat in rows:
        f = features(boxes, w, h)
        if f is None:
            continue
        by_cat[cat or "(未分类)"].append(f)

    keys = ["alt", "width_cv", "height_cv", "top", "bottom",
            "left_align", "center_blank", "multi", "avg_h", "span"]

    def q(xs, p):
        xs = sorted(xs)
        return xs[min(len(xs) - 1, max(0, int(len(xs) * p)))]

    header = f"{'类':<10} {'n':>5} " + " ".join(f"{k:>11}" for k in keys)
    print(header)
    print("-" * len(header))
    for cat in sorted(by_cat, key=lambda c: -len(by_cat[c])):
        fs = by_cat[cat]
        if len(fs) < 20:
            continue
        cells = []
        for k in keys:
            vals = [f[k] for f in fs]
            cells.append(f"{q(vals, 0.5):.3f}")
        print(f"{cat:<10} {len(fs):>5} " + " ".join(f"{c:>11}" for c in cells))

    # 关键问题：chat 能否用某条规则从其他类里分出来？
    chat = by_cat.get("chat", [])
    others = [f for c, fs in by_cat.items() if c != "chat" for f in fs]
    if len(chat) < 20 or len(others) < 20:
        print("\n样本不足，无法评估。")
        return

    print("\n=== 单特征判别力（chat vs 其他）===")
    print(f"{'特征':<14} {'chat中位':>9} {'其他中位':>9} {'阈值':>7} "
          f"{'召回':>7} {'精度':>7} {'F1':>7}")
    best = []
    for k in keys:
        cvals = [f[k] for f in chat]
        ovals = [f[k] for f in others]
        cm, om = q(cvals, 0.5), q(ovals, 0.5)
        if abs(cm - om) < 1e-6:
            continue
        # 方向：chat 更大就 >= 阈值，否则 <=
        higher = cm > om
        # 扫阈值找最佳 F1
        cands = sorted(set(cvals + ovals))
        best_f1, best_thr, best_tp = 0, None, 0
        for thr in cands:
            if higher:
                tp = sum(1 for v in cvals if v >= thr)
                fp = sum(1 for v in ovals if v >= thr)
            else:
                tp = sum(1 for v in cvals if v <= thr)
                fp = sum(1 for v in ovals if v <= thr)
            if tp == 0:
                continue
            prec = tp / (tp + fp) if tp + fp else 0
            rec = tp / len(cvals)
            f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0
            if f1 > best_f1:
                best_f1, best_thr, best_tp = f1, thr, tp
        if best_thr is None:
            continue
        tp = best_tp
        fp = (sum(1 for v in ovals if v >= best_thr) if higher
              else sum(1 for v in ovals if v <= best_thr))
        prec = tp / (tp + fp) if tp + fp else 0
        rec = tp / len(cvals)
        print(f"{k:<14} {cm:>9.3f} {om:>9.3f} {best_thr:>7.3f} "
              f"{rec:>7.3f} {prec:>7.3f} {best_f1:>7.3f}")
        best.append((best_f1, k, best_thr, higher, prec, rec))

    best.sort(reverse=True)
    print("\n=== 结论 ===")
    if not best:
        print("没有任何单特征具备判别力。")
        return
    f1, k, thr, higher, prec, rec = best[0]
    print(f"最佳单特征：{k} {'>=' if higher else '<='} {thr:.3f}")
    print(f"  F1={f1:.3f}  精度={prec:.3f}  召回={rec:.3f}")
    if f1 < 0.5:
        print("  → 不足 0.5，不值得接入分类器。")
    else:
        print("  → 有区分度，可以接入（仍需按 Tier 标注置信度）。")


if __name__ == "__main__":
    main()
