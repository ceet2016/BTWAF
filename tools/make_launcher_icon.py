#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 App 图标（全套 mipmap + adaptive icon）。

设计：盾牌 + 数据流入 + 拒止横杠，「WAF 挡攻击」的直白隐喻。
配色沿用 App 主题的 BrandBlue #2563EB，保证桌面图标与 App 内界面同色系。

产出：
  mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png        栅格图标（带圆角底）
  mipmap-{...}/ic_launcher_round.png                            圆形图标
  mipmap-{...}/ic_launcher_foreground.png                       自适应图标前景（透明底）
  mipmap-anydpi-v26/ic_launcher.xml + ic_launcher_round.xml      自适应图标声明
  values/ic_launcher_background.xml自适应图标底色

为什么自己画而不用 ImageGen：
  自适应图标对**安全区**有硬要求 —— 前景内容必须落在中心 66% 内，
  否则会被各家Launcher 的圆形/水滴形遮罩裁掉。生成模型控不住这个。
  矢量化的盾形+横杠也远比位图清晰，各密度放大不糊。
"""
import math
from pathlib import Path
from PIL import Image, ImageDraw

# ── 主题色（与 ui/theme/Theme.kt 的 BrandBlue 一致）──────────────────
BRAND = (37, 99, 235)        # #2563EB 主蓝
BRAND_DARK = (30, 78, 189)   # 底渐变深端
BRAND_LIGHT = (96, 165, 250) # #60A5FA 亮蓝
WHITE = (255, 255, 255)
ACCENT = (249, 115, 22)      # #F97316 橙，用于「拒止」横杠

RES = Path("app/src/main/res")

# 各密度对应的图标边长（Android 标准）
DENSITIES = {
    "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192,
}
# 自适应图标前景是 108dp（比栅格大 1.125 倍，内容要缩在中心 66%）
FG_DENSITIES = {
    "mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432,
}

SS = 8  # 超采样倍数，画完再缩，边缘才平滑


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def shield_points(cx, cy, w, h, waist=0.42):
    """盾形轮廓点集：上宽下尖，底部收成 V 字。

    ⚠️ 顶点顺序必须沿轮廓**单向走一圈**，否则多边形自交，
    PIL的 polygon() 填充出来是乱七八糟的斜切色块（看着像盾牌，
    其实不是）。走向固定为：
        左上圆角 → 顶边 → 右上圆角 → 右侧下行 → 贝塞尔收尖到底尖
        → 左侧镜像收回 → 闭合。

    坐标注意：PIL 的 y 轴向下，所以「上方」是 y 小。
    圆心角用弧度时要按 y 向下的约定算（左上角是 180°→270°）。
    """
    top = cy - h / 2
    bot = cy + h / 2
    left = cx - w / 2
    right = cx + w / 2
    r = w * 0.20# 肩部圆角半径
    pts = []

    # 1) 左上圆角：圆心 (left+r, top+r)，从 180°(最左) 走到 270°(最上)
    for i in range(10):
        t = i / 9
        ang = math.pi + t * (math.pi / 2)
        pts.append((left + r + r * math.cos(ang), top + r + r * math.sin(ang)))

    # 2) 顶边：走到右上圆角起点
    pts.append((right - r, top))

    # 3) 右上圆角：圆心 (right-r, top+r)，从 270°(最上) 走到 360°(最右)
    for i in range(10):
        t = i / 9
        ang = math.pi * 1.5 + t * (math.pi / 2)
        pts.append((right - r + r * math.cos(ang), top + r + r * math.sin(ang)))

    # 4) 右侧竖边下到腰线
    pts.append((right, top + h * waist))

    # 5) 右侧贝塞尔收尖：腰线 → 底尖。控制点压在右下，形成饱满的弧
    x0, y0 = right, top + h * waist
    cx1, cy1 = right, top + h * 0.82
    x1, y1 = cx, bot
    for i in range(1, 16):
        t = i / 15
        pts.append((
            (1 - t) ** 2 * x0 + 2 * (1 - t) * t * cx1 + t ** 2 * x1,
            (1 - t) ** 2 * y0 + 2 * (1 - t) * t * cy1 + t ** 2 * y1,
        ))

    # 6) 左侧镜像：从底尖收回左侧腰线
    x0, y0 = left, top + h * waist
    cx1, cy1 = left, top + h * 0.82
    x1, y1 = cx, bot
    for i in range(1, 16):
        t = 1 - i / 15
        pts.append((
            (1 - t) ** 2 * x0 + 2 * (1 - t) * t * cx1 + t ** 2 * x1,
            (1 - t) ** 2 * y0 + 2 * (1 - t) * t * cy1 + t ** 2 * y1,
        ))

    return pts


def draw_shield(d, cx, cy, w, h, fill, outline=None, width=0):
    """画一个盾形：上宽下尖，底部收成 V 字。"""
    pts = shield_points(cx, cy, w, h)
    if outline and width > 0:
        d.polygon(pts, fill=fill, outline=outline, width=width)
    else:
        d.polygon(pts, fill=fill)
    return pts


def shield_mask(S, cx, cy, w, h):
    """盾形 L 通道遮罩，用作内部元素的裁剪区。"""
    m = Image.new("L", (S, S), 0)
    ImageDraw.Draw(m).polygon(shield_points(cx, cy, w, h), fill=255)
    return m


def draw_inner(d, cx, cy, w, h, S, bar_fill, dash_fill):
    """画盾形内部的元素：拒止横杠 + 上下数据流虚线。

    虚线**必须**画在盾内（调用方按盾形遮罩裁剪），而且排布要避开
    盾形下半的收窄段：盾形腰部以下宽度快速收缩，虚线离横杠越远、
    离中轴越远，就越容易被边缘削掉半截，看起来像 bug 而不像设计。
    所以这里只用「上二下一」三排，并把横向间距收窄。
    """
    bar_w = w * 0.44
    bar_h = max(2, int(S * 0.055))
    bar_y = cy - h * 0.02
    d.rounded_rectangle(
        [cx - bar_w / 2, bar_y - bar_h / 2, cx + bar_w / 2, bar_y + bar_h / 2],
        radius=bar_h / 2, fill=bar_fill
    )

    # 上二下一：横杠把数据流拦腰截断，视觉上就是「攻不进来」
    dash_w = w * 0.15
    gap = bar_h * 1.9
    for i, dy in enumerate((-2.4, -1.6, 1.6)):
        y = bar_y + gap * dy
        col = dash_fill(150 if i % 2 == 0 else 105)
        for sx in (-1, 1):
            cxr = cx + sx * (bar_w / 2 + dash_w * 0.5)
            d.rounded_rectangle(
                [cxr - dash_w / 2, y - bar_h * 0.34, cxr + dash_w / 2, y + bar_h * 0.34],
                radius=bar_h * 0.34, fill=col
            )


def make_foreground(size, with_bg=False, circle=False):
    """画图标主体。size 是最终边长（内部按 SS 倍超采样）。"""
    S = size * SS
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    if with_bg:
        # 圆角方/圆形底 + 竖向渐变
        for y in range(S):
            t = y / max(1, S - 1)
            d.line([(0, y), (S, y)], fill=lerp(BRAND_LIGHT, BRAND_DARK, t) + (255,))
        if circle:
            mask = Image.new("L", (S, S), 0)
            ImageDraw.Draw(mask).ellipse([0, 0, S - 1, S - 1], fill=255)
        else:
            mask = Image.new("L", (S, S), 0)
            ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1],
                                                   radius=int(S * 0.22), fill=255)
        img.putalpha(mask)
        # 底上再叠一层极淡的对角高光，避免大色块显得平
        gloss = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        gd = ImageDraw.Draw(gloss)
        gd.polygon([(0, 0), (S * 0.55, 0), (0, S * 0.55)], fill=(255, 255, 255, 26))
        img = Image.alpha_composite(img, gloss)
        d = ImageDraw.Draw(img)

    # 内容缩放：自适应图标的内容必须落在中心 66% 安全区内
    k = 0.52 if with_bg else 0.60
    cx = cy = S / 2
    w = S * k
    h = S * k * 1.18# 盾形略高

    # 盾体：白色实心
    shield = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw_shield(ImageDraw.Draw(shield), cx, cy, w, h, WHITE)
    smask = shield_mask(S, cx, cy, w, h)

    # 内部元素画在独立图层，再按盾形遮罩裁剪，压到盾体上。
    # 直接往盾上画的话，下方两条虚线会溢出到盾形收窄的边缘外面，
    # 看起来像两条悬空的杂线。
    inner = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw_inner(
        ImageDraw.Draw(inner), cx, cy, w, h, S,
        bar_fill=ACCENT,
        dash_fill=(lambda a: lerp(WHITE, BRAND, 0.25) + (a,)) if with_bg
                  else (lambda a: BRAND + (a,)),
    )
    inner.putalpha(Image.composite(inner.split()[3], Image.new("L", (S, S), 0), smask))
    shield = Image.alpha_composite(shield, inner)

    img = Image.alpha_composite(img, shield)
    return img.resize((size, size), Image.LANCZOS)


def make_monochrome(size):
    """生成 monochrome 层：纯剪影，只有 alpha 通道有意义。

    **不能直接复用彩色前景图。**
    Android 13+ 的主题图标（themed icon）会丢掉颜色，只取 drawable 的
    alpha 通道当遮罩，再按系统给的色相反向着色。直接拿彩色前景去当
    monochrome，结果是「实心盾牌」——盾是实心的（alpha=255），
    橙色横杠虽然也是不透明，但它压在实心盾上、alpha 一样是 255，
    遮罩里根本分辨不出来，于是横杠和虚线全部消失，图标退化成一个
    实心盾牌 + 一个色块。

    正确做法：自己构造 alpha 遮罩，盾=不透明，横杠与虚线=**挖空**
    （alpha=0）。系统着色后就是「实心盾 + 镂空横杠 + 镂空虚线」，
    跟彩色版读起来是同一枚图标。

    返回 RGBA（黑色 + 上述 alpha）。黑色本身无意义，交给系统着色。
    """
    S = size * SS
    k = 0.60
    cx = cy = S / 2
    w = S * k
    h = S * k * 1.18

    # 遮罩起点= 盾体不透明
    mask = shield_mask(S, cx, cy, w, h)

    # 在透明副本上画内部元素（白），再用「盾体 - 内部元素」当遮罩，
    # 这样横杠与虚线自然被挖空，形状与彩色版逐像素对得上。
    inner = Image.new("L", (S, S), 0)
    draw_inner(
        ImageDraw.Draw(inner), cx, cy, w, h, S,
        bar_fill=255, dash_fill=lambda a: 255,
    )
    inner = Image.composite(inner, Image.new("L", (S, S), 0), mask)

    from PIL import ImageChops
    mask = ImageChops.subtract(mask, inner)

    # L 遮罩 -> RGBA（RGB 全黑，只有 alpha 有效，系统会按主题色反相着色）
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    img.putalpha(mask)
    return img.resize((size, size), Image.LANCZOS)


def main():
    total = 0

    for dens, px in DENSITIES.items():
        d = RES / f"mipmap-{dens}"
        d.mkdir(parents=True, exist_ok=True)

        # 栅格图标（圆角方）
        make_foreground(px, with_bg=True, circle=False).save(d / "ic_launcher.png")
        # 圆形图标
        make_foreground(px, with_bg=True, circle=True).save(d / "ic_launcher_round.png")
        total += 2

        # 自适应前景
        fg = FG_DENSITIES[dens]
        make_foreground(fg, with_bg=False).save(d / "ic_launcher_foreground.png")
        total += 1
        # monochrome 层：与前景同尺寸，单独一份剪影
        make_monochrome(fg).save(d / "ic_launcher_monochrome.png")
        total += 1
        print(f"  mipmap-{dens}: {px}px 栅格 + {fg}px 前景/monochrome")

    # 自适应图标声明
    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    xml = '''<?xml version="1.0" encoding="utf-8"?>
    <!-- 自适应图标：前景是盾牌+拒止横杠，底色纯品牌蓝。
         内容必须落在中心 66% 安全区内，否则会被各家 Launcher
         的圆形/水滴形遮罩裁掉。
         monochrome 是单独画的剪影（横杠与虚线挖空成透明），
         Android 13+ 主题图标只取alpha 通道着色，直接复用彩色前景会退化成实心盾。 -->
    <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
        <background android:drawable="@color/ic_launcher_background" />
        <foreground android:drawable="@mipmap/ic_launcher_foreground" />
        <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
    </adaptive-icon>
'''
    (anydpi / "ic_launcher.xml").write_text(xml, encoding="utf-8")
    (anydpi / "ic_launcher_round.xml").write_text(xml, encoding="utf-8")
    total += 2

    # 底色
    vals = RES / "values"
    vals.mkdir(parents=True, exist_ok=True)
    (vals / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<resources>\n'
        '    <color name="ic_launcher_background">#1E4EBD</color>\n'
        '</resources>\n',
        encoding="utf-8"
    )
    total += 1

    print(f"\n共生成 {total} 个文件")


if __name__ == "__main__":
    main()
