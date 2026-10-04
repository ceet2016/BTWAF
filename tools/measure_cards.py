#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
截图对称性像素检测（卡片横向带）。

比肉眼精确：直接从 PNG 量出同一水平带上各卡片的像素宽度与间距。
只做一件事 —— 找「同一行里的多个纯白卡片」，然后验证它们等宽、
等距、与屏幕左右对称。这三件事正是用户肉眼看到的「不对称」。

卡片色 = 纯白 (255,255,255)，页面背景 = (246,247,251)，
两者差异明显，阈值可以卡死在中间。

用法：python measure_cards.py <png> [<png> ...]
退出码：有不等宽/不等距/边距不对称则返回 1。
"""
import sys
from pathlib import Path
from PIL import Image

CARD_MIN = 252# 纯白
MIN_W = 80# 一张卡片至少这么宽
TOL = 2        # 允许的像素级误差（2px 以内视为对齐）
# 页面标准右边距：1080 宽屏上实测整宽卡片右边缘约 1036，即 44px。
# 抽成常量是因为「并排卡片对」的最右段右边缘必须落在这个位置（见过滤规则 5）。
STD_RMARGIN = 44


def runs_at(px, W, y):
    """该行里所有 >= MIN_W 的纯白连续段。"""
    out = []
    start = None
    for x in range(W):
        r, g, b = px[x, y]
        card = r >= CARD_MIN and g >= CARD_MIN and b >= CARD_MIN
        if card and start is None:
            start = x
        elif not card and start is not None:
            if x - start >= MIN_W:
                out.append((start, x - start))
            start = None
    if start is not None and W - start >= MIN_W:
        out.append((start, W - start))
    return out


def analyze(path):
    path = Path(path)
    im = Image.open(path).convert("RGB")
    W, H = im.size
    px = im.load()

    # 逐行找出有 2+ 卡片的行，记录这些行
    band_rows = {}
    for y in range(0, H, 4):
        rs = runs_at(px, W, y)
        if len(rs) >= 2:
            band_rows[y] = rs

    if not band_rows:
        print(f"{path.name}: 未检出并排卡片")
        return False

    # 把连续的行聚成带
    ys = sorted(band_rows)
    bands = []
    for y in ys:
        if bands and y - bands[-1][-1] <= 8:
            bands[-1].append(y)
        else:
            bands.append([y])

    issues = []
    print("=" * 68)
    print(f"{path.name}  {W}x{H}")
    print("=" * 68)

    for band in bands:
        # 代表行的选取很关键，不能用「出现次数最多的签名」。
        #
        # 实测踩过的坑：概览页 y≈1608 那条带里，跨文字行的像素分段会变，
        # 众数行落在文字行上，于是报出「宽度差 3px」的假缺陷；
        # 而这一行真实像素是 (44,411) 和 (626,410) —— 只差 1px，完全对称。
        #
        # 正确做法：取带内「最宽的卡片宽度和最大」的那行，那才是真正的卡片行
        #（文字行切出来的段又窄又碎，合计宽度远小于卡片行）。
        best = max(band, key=lambda yy: sum(w for _, w in band_rows[yy]) if band_rows[yy] else 0)
        segs = list(band_rows[best])
        y0 = band[0]

        ws = [w for _, w in segs]
        gaps = [segs[i + 1][0] - (segs[i][0] + segs[i][1]) for i in range(len(segs) - 1)]

        # 噪声带过滤 1：宽度差超过卡片自身宽度一半 —— 这是文字/图标行
        #（例如输入框左侧的标签和右侧的输入区），不是并排卡片。
        if max(ws) - min(ws) > min(ws) * 0.5:
            continue

        # 噪声带过滤 2：间距比卡片本身还宽 —— 说明中间夹着大段非卡片区域，
        # 是「文字 + 文字 + 文字」被卡片化的分段，不是并排栅格。
        # 实测概览页 y≈1652就是这样：(44,281)(456,46)(503,49)(745,291)，
        # 间距 131/1/193px，而最窄的段只有 46px。
        if gaps and max(gaps) > min(ws):
            continue

        # 噪声带过滤 3：间距几乎为 0 —— 这是「图标底板 + 右侧文字区」
        # 被切成了两段，不是并排卡片。
        # 实测「单项防护级别」行：(149,388) 和 (538,443)，间距 1px。
        if gaps and min(gaps) <= 2:
            continue

        # 噪声带过滤 4：两张宽度差超过 15% —— 并排栅格卡片是按同一份
        # 宽度权重算出来的，差值只会有 1~2px 的渲染舍入。
        # 差得多说明这行不是「两张卡片」，而是「一张卡片里的竖条/图标 + 主体」。
        # 实测「攻击类型分布」卡片：(44,448) 和 (574,462)，差 14px = 3.4%，
        # 15% 阈值拦不住，所以再加一条判据 5。
        if len(ws) == 2 and abs(ws[0] - ws[1]) > max(ws) * 0.15:
            continue

        # 噪声带过滤 5（实测有效的几何判据）：
        # 并排栅格卡片的**左端有额外缩进**（KPI 卡左边距实测 69px，
        # 因为外层还有一层 GridCell padding）；而「一张整宽卡被内容切开」
        # 时左端顶的是标准卡片左边距（44px）。
        #
        # 试过「最右段到屏幕右边的距离」这条，方向是反的：被切开的整宽卡
        # 右边距恰好就是标准 44，反而最"标准"，判不出来。
        # 左边距才是可靠信号。
        if len(segs) == 2 and segs[0][0] <= STD_RMARGIN + TOL:
            continue

        wspread = max(ws) - min(ws)
        gspread = max(gaps) - min(gaps) if gaps else 0
        lm = segs[0][0]
        rm = W - 1 - (segs[-1][0] + segs[-1][1])

        ok_w = wspread <= TOL
        ok_g = gspread <= TOL if gaps else True
        ok_m = abs(lm - rm) <= TOL

        flag = "OK" if (ok_w and ok_g and ok_m) else "!!"
        print(f"  {flag} y≈{y0:4d}  {len(segs)}张  左边距={lm}右边距={rm}"
              f"  宽度={ws}  间距={gaps}")
        if not ok_w:
            print(f"        !! 卡片宽度不等，最大差 {wspread}px")
        if not ok_g:
            print(f"        !! 卡片间距不均，最大差 {gspread}px")
        if not ok_m:
            print(f"        !! 左右边距不对称，差 {abs(lm - rm)}px（左 {lm} 右 {rm}）")
        if not (ok_w and ok_g and ok_m):
            issues.append((y0, segs, lm, rm, ws, gaps))

    print(f"\n  检出 {len(bands)} 个带，{len(issues)} 个存在不对称")
    return bool(issues)


def main():
    bad = False
    for p in sys.argv[1:]:
        if not Path(p).exists():
            print(f"[!] 不存在: {p}")
            continue
        if analyze(p):
            bad = True
        print()
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()