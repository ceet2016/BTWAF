#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 App 图标（全套 mipmap + adaptive icon）。

## 设计：深蓝渐变底 + 双层盾 + 橙色「拒止闪电」

上一版是「蓝底 + 白盾 + 橙横杠 + 数据流虚线」，看着像**任何**一个安全类
App：盾牌这个隐喻已经被用烂了，桌面上十几屏分不出谁是谁。
这一版换思路，靠三个特征拿辨识度：

1. **盾形从纯白改为「白色主体 + 蓝色内芯」的双层结构** ——
   单色盾太单薄，双层套色在小尺寸下也能看出层次。
2. **内芯是「BT」的 B 骨架** —— 字母是天然的辨识锚点，
   比抽象图形更容易被记住，也让「WAF 客户端」这层身份落到实处。
3. **橙色的闪电而不是横杠** —— 横杠是「禁止」，闪电是「拦截正在发生」，
   动态感更强，斜向造型在小尺寸下也更有张力。

配色沿用 App 主题的 BrandBlue #2563EB，保证桌面图标与 App 内界面同色系。

## 产出

  mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png        栅格图标（带圆角底）
  mipmap-{...}/ic_launcher_round.png                            圆形图标
  mipmap-{...}/ic_launcher_foreground.png                       自适应图标前景（透明底）
  mipmap-{...}/ic_launcher_monochrome.png                       主题图标剪影层
  mipmap-anydpi-v26/ic_launcher.xml + ic_launcher_round.xml      自适应图标声明
  values/ic_launcher_background.xml                              自适应图标底色

## 为什么自己画而不用 ImageGen

自适应图标对**安全区**有硬要求 —— 前景内容必须落在中心 66% 内，
否则会被各家 Launcher 的圆形/水滴形遮罩裁掉。生成模型控不住这个，
出图必然要人工试错裁切。矢量化的盾形 + 字母骨架也远比位图清晰，
各密度放大不糊。**这一点是硬约束，不是风格偏好。**
"""
import math
from pathlib import Path
from PIL import Image, ImageDraw

# ── 主题色（与 ui/theme/Theme.kt 的 BrandBlue 一致）──────────────────
BRAND = (37, 99, 235)        # #2563EB 主蓝
BRAND_DARK = (16, 42, 110)   # 底渐变深端：比上一版更深，衬托白盾更跳
BRAND_LIGHT = (59, 130, 246) # 顶渐变亮端
WHITE = (255, 255, 255)
ACCENT = (249, 115, 22)      # #F97316 橙，用于「拦截」闪电

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


def draw_inner(d, cx, cy, w, h, S, bar_fill, ink, carve):
    """画盾形内部元素：品牌蓝「B」骨架 + 橙色拦截闪电。

    ## 为什么要用字母而不是抽象图形

    上一版是「横杠 + 虚线」，在 48px 的 mdpi 上就是**一个蓝底方块里两三条线**，
    缩到桌面图标实际尺寸后完全看不出是什么。
    字母 B 的识别度在小尺寸下远高于抽象符号 —— 它有直边、有两个确定的
    圆弧轮廓，即使只剩十几个像素也能认出「是个 B」，
    用户由此记住「BTWAF 的图标是个 B」。

    ## 参数为什么是三个颜色而不是两个

    之前 ink 与挖空色共用一个 `dash_fill`，结果两个值**完全相同** ——
    B 是蓝的，碗里填的也是蓝的，碗等于没挖：B 直接退化成一个蓝色实心块。
    所以 ink（字母）与 carve（碗内）必须独立传入：
      - 彩色版：ink=品牌蓝，carve=白（露出盾体）
      - monochrome：ink=白，carve=黑（alpha 挖空，见 make_monochrome）
    """
    bw = w * 0.50
    bh = h * 0.42
    bcy = cy - h * 0.10
    left = cx - bw / 2
    # 主竖干粗细按比例算，不用固定像素 —— 否则小尺寸下会糊成一片
    stem = bw * 0.27

    # 竖干
    d.rounded_rectangle(
        [left, bcy - bh / 2, left + stem, bcy + bh / 2],
        radius=stem * 0.30, fill=ink
    )

    # 两个碗：上碗略小、下碗略大，符合 B 的视觉重心。
    # 先画实心碗（蓝），再用 carve 色画内腔把它挖空——
    # 两步法比直接拼环形更可控，边缘不会出现多边形自交造成的斜切。
    #
    # 半径系数经过实测调整：bowl 0.34/0.38 + r_in 0.52 那一版，
    # 下碗的外沿几乎顶到盾形边缘，盾的白色只剩一圈，字母显得又肥又挤。
    # 收小到 0.34/0.38 之后，盾内留出了明确的白色呼吸区。
    for frac, cyr in ((0.34, bcy - bh * 0.25), (0.38, bcy + bh * 0.25)):
        r_out = bw * frac
        r_in = r_out * 0.46
        d.ellipse([left + stem - r_out * 0.30, cyr - r_out,
                   left + stem + r_out * 1.85, cyr + r_out], fill=ink)
        d.ellipse([left + stem + r_out * 0.26, cyr - r_in,
                   left + stem + r_out * 0.26 + r_in * 2.0, cyr + r_in], fill=carve)

    # 橙色闪电：斜跨右下角。
    # ⚠️ 位置必须**避开盾形的收窄段**：盾牌腰部以下宽度快速收缩，
    # 闪电放在 zy1 = cy + h*0.34 时，末梢会正好压在盾形边缘上，
    # 被裁成一条贴着轮廓的粗线（第一版就是这么废掉的）。
    # 往上收到 0.24、并缩小 zw，看起来才是「浮在盾里」而不是「卡在边上」。
    zx = cx + w * 0.30
    zy0 = cy + h * 0.04
    zy1 = cy + h * 0.24
    zw = w * 0.11
    d.polygon([
        (zx + zw, zy0),
        (zx - zw * 0.15, zy0 + (zy1 - zy0) * 0.50),
        (zx + zw * 0.60, zy0 + (zy1 - zy0) * 0.50),
        (zx - zw * 0.70, zy1),
        (zx + zw * 0.15, zy0 + (zy1 - zy0) * 0.60),
        (zx - zw * 0.80, zy0 + (zy1 - zy0) * 0.60),
    ], fill=bar_fill)


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
        # 彩色版：B 是品牌蓝（压在白盾上），碗里填白露出盾体。
        ink=BRAND + (255,),
        carve=WHITE + (255,),
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

    正确做法：自己构造 alpha 遮罩，盾=不透明，字母与闪电 =**挖空**
    （alpha=0）。系统着色后就是「实心盾 + 镂空 B + 镂空闪电」，
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

    # ⚠️ monochrome 里的 ink/carve 与彩色版**语义不同**，别照抄：
    #   彩色版：ink=蓝（画字母）、carve=白（填碗）
    #   mono  ：ink=白（挖掉字母与闪电）、carve=黑（保留碗的实心环）
    #
    # 最终效果 = 实心盾 − 字母 − 闪电 + 碗的环。
    # 碗不能整块挖空：字母的内腔本来就是「露出盾的白」，
    # 在剪影里如果连内腔也一起挖，B 就成了两条分离的横线加一个竖干，
    # 完全读不出是B。所以碗的内腔用 carve=0 填回，保留一个实心块。
    inner = Image.new("L", (S, S), 0)
    draw_inner(
        ImageDraw.Draw(inner), cx, cy, w, h, S,
        bar_fill=255,
        ink=255,          # 字母 + 闪电 = 要挖空的部分
        carve=0,          #碗内腔 = 保留
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
    <!-- 自适应图标：前景是「白盾 + 蓝B 骨架 + 橙闪电」，底色深蓝。
         内容必须落在中心 66% 安全区内，否则会被各家 Launcher
         的圆形/水滴形遮罩裁掉。
         monochrome 是单独画的剪影（B 与闪电镂空成透明、碗内腔保留实心），
         Android 13+ 主题图标只取 alpha 通道着色，直接复用彩色前景会退化成实心盾。 -->
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
        # 底色取 BRAND_DARK 附近（#102A6E）。自适应图标的背景层是**纯色铺满**，
        # 各家 Launcher 会在它上面再套圆形/水滴遮罩，所以底色必须与前景
        # 有足够对比 —— 上一版用 #1E4EBD偏亮，白盾压在上面的边界不够利落。
        '    <color name="ic_launcher_background">#102A6E</color>\n'
        '</resources>\n',
        encoding="utf-8"
    )
    total += 1

    print(f"\n共生成 {total} 个文件")


if __name__ == "__main__":
    main()
