#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
子页面顶部空白检测。

**为什么需要单独一个脚本**：对称性检测（measure_cards.py）只看卡片之间
的宽度/间距/边距对不对，看不见「整页从顶部栏往下空一大段」这种问题 ——
因为空的地方压根没有卡片。而这恰恰是用户最容易察觉的布局缺陷。

**判据**：量出「顶部栏底边→ 第一块内容顶边」的垂直距离。
参照物是同一台设备上**已知正常**的系统管理首页：

  - 顶部栏底 273 → 首张卡片 314   = 41px   ✅ 正常
  - 通知设置（修前）273 → 566     = 293px  ❌ 差6 倍

成因：MainScreen 的 Scaffold 已带TopAppBar，子页面在 NavHost 里
**又套一层 Scaffold**，M3 的 Scaffold 默认消费 windowInsets，
内层把状态栏 inset 又算了一遍，叠加列表自身的 contentPadding，
顶部于是空出一大块。修法见 Widgets.kt 的 SubPageScaffold。

参考值（1080x2412 实测）：
  - 系统管理首页（无内层 Scaffold，首项是卡片）  44px   ✅
  - 通知设置（**修前**）296px / 修后 60px以内
  - 任何页面超过 90px 都意味着顶部被内层 windowInsets 坑了。

判据是「与背景色 (246,247,251) 有差异的像素占比」，所以文字、图标、
Tab 栏、分割线统统算内容 —— 这样量出来的才是真实留白，
而不是「第一张卡片离顶部多远」。

用法：python measure_top_gap.py <png> [<png> ...]
退出码：有页面顶部空白超标则返回 1。
"""
import sys
from pathlib import Path
from PIL import Image

# 顶部栏（TopAppBar）在 1080x2412 上的底边实测 y=273
TOPBAR_BOTTOM = 273
# 往下先跳过这段，避免把顶部栏自己的阴影/分隔线当成内容
SCAN_FROM = 280
SCAN_TO = 900
# 页面背景是 (246,247,251)，只有**非背景**的像素才算真内容。
#
#⚠️ 早先版本用「近白(>=250)像素占比」当判据，结果只能量到*卡片*：
#像报表页首项是 Tab 栏、通知页首项是 SectionHeader 文字行，
#检测器会一路扫到第二张卡片，量出 250px 却误报成缺陷。
# 改成「与背景色有差异就算内容」后，文字/图标/分割线都能被量到，
# 才真正量得出「顶部空了多少」。
BG = (246, 247, 251)
BG_TOL = 6# 允许的抗锯齿/压缩噪声
THRESHOLD = 90
# 每行抽样点数（1080 宽，step 6 => 160 点）
STEP = 6
# 一行里非背景像素占比超过这个数才算「这一行有内容」
COVER = 0.04


def first_content_y(path):
    """返回顶部栏下方第一行有内容的 y；找不到返回 None。"""
    im = Image.open(path).convert("RGB")
    W, H = im.size
    px = im.load()
    need = max(3, int((W - 120) / STEP * COVER))
    for y in range(SCAN_FROM, min(SCAN_TO, H)):
        cnt = 0
        for x in range(60, W - 60, STEP):
            r, g, b = px[x, y]
            if (abs(r - BG[0]) + abs(g - BG[1]) + abs(b - BG[2])) > BG_TOL:
                cnt += 1
                if cnt >= need:
                    return y
    return None


def main():
    bad = []
    print("=" * 66)
    print("子页面顶部空白检测（阈值 %dpx）" % THRESHOLD)
    print("=" * 66)
    for p in sys.argv[1:]:
        path = Path(p)
        if not path.exists():
            print("[!] 不存在: %s" % p)
            continue
        y = first_content_y(path)
        if y is None:
            print("  ??  %-24s  %dpx 内未检出内容（空页面或首屏是文字）" % (path.name, SCAN_TO))
            continue
        gap = y - TOPBAR_BOTTOM
        flag = "OK" if gap <= THRESHOLD else "!!"
        print("  %s  %-24s 顶部空白 %3dpx（内容顶边 y=%d）" % (flag, path.name, gap, y))
        if gap > THRESHOLD:
            bad.append((path.name, gap))
    print()
    if bad:
        print("  !! %d 个页面顶部空白超标：" % len(bad))
        for name, gap in bad:
            print("       %-24s %dpx" % (name, gap))
        return 1
    print("  全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
