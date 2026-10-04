#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
把各页面手写的「图标底板」统一替换成设计系统的 IconBadge。

背景
----
改造前每个页面都自己手写一遍图标底板：

    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(it.tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(it.icon, null, tint = it.tint, modifier = Modifier.size(18.dp))
    }

全项目一共出现 30/32/34/36/38/40 六种尺寸、8/9/10/11 四种圆角，
底色还有 primary.copy(alpha) 与 xxxContainer 两种写法 ——
这就是用户说的「很丑」「东一块西一块」的直接来源。

实现要点
--------
**逐行状态机扫描，不用跨行大正则。**
之前尝试用单条正则匹配整个 Box 块，失败原因有两个：
1. Kotlin 的 dp 字面量是 `36.dp`，数字后还有一个点，`(\d+)dp` 匹配不到；
2. background 表达式里含嵌套括号，正则的 `[^)]+` 会提前截断。
逐行匹配每一步都只有一种可能，稳得多。

脚本幂等：已替换成 IconBadge 的地方不再符合模式，重跑不会重复处理。
"""
import re
import glob
import os
import sys

RE_BOX_OPEN = re.compile(r'^(\s*)Box\(\s*$')
RE_MODIFIER = re.compile(r'^\s*Modifier\s*$')
RE_SIZE = re.compile(r'^\s*\.size\((\d+)\.dp\)\s*$')
RE_CLIP = re.compile(
    r'^\s*\.clip\((?:androidx\.compose\.foundation\.shape\.)?RoundedCornerShape\((\d+)\.dp\)\)\s*$'
)
RE_BG_START = re.compile(r'^\s*\.background\((.*)$')
RE_ALIGN = re.compile(r'^\s*contentAlignment = Alignment\.Center\s*$')
RE_BOX_BODY_OPEN = re.compile(r'^\s*\)\s*\{\s*$')
RE_ICON_OPEN = re.compile(r'^\s*Icon\(\s*$')
# 单行形式：Icon(it.icon, null, tint = it.tint, modifier = Modifier.size(18.dp))
RE_ICON_ONELINE = re.compile(
    r'^\s*Icon\((?P<icon>.+?),\s*null,\s*tint = (?P<tint>.+?),\s*'
    r'modifier = Modifier\.size\(\d+\.dp\)\)\s*$'
)
# 多行形式的关键三行
RE_ICON_ARG = re.compile(r'^\s*(?P<arg>[A-Za-z_][A-Za-z0-9_.]*)\s*,\s*null,\s*$')
RE_ICON_TINT = re.compile(r'^\s*tint = (?P<tint>.+?),\s*$')
RE_ICON_MODSIZE = re.compile(r'^\s*modifier = Modifier\.size\(\d+\.dp\)\s*$')
RE_PAREN_CLOSE = re.compile(r'^\s*\)\s*$')
RE_BRACE_CLOSE = re.compile(r'^\s*\}\s*$')


def read_icon(lines, j):
    """lines[j] 起始的 Icon(...) 调用，返回 (icon, tint, 结束下标) 或 None。"""
    m = RE_ICON_ONELINE.match(lines[j])
    if m:
        return m.group("icon").strip(), m.group("tint").strip(), j + 1
    if not RE_ICON_OPEN.match(lines[j]):
        return None
    k = j + 1
    m = RE_ICON_ARG.match(lines[k]) if k < len(lines) else None
    if not m:
        return None
    icon = m.group("arg")
    k += 1
    m = RE_ICON_TINT.match(lines[k]) if k < len(lines) else None
    if not m:
        return None
    tint = m.group("tint").strip()
    k += 1
    if not (k < len(lines) and RE_ICON_MODSIZE.match(lines[k])):
        return None
    k += 1
    if not (k < len(lines) and RE_PAREN_CLOSE.match(lines[k])):
        return None
    k += 1
    return icon, tint, k


def try_convert(lines, i):
    """lines[i] 若是「手写图标底板」的 Box( 起始行，返回 (替换行, 结束下标)；否则 None。"""
    m = RE_BOX_OPEN.match(lines[i])
    if not m:
        return None
    indent = m.group(1) + "    "
    j = i + 1

    def at(pat):
        return pat.match(lines[j]) if j < len(lines) else None

    if not at(RE_MODIFIER):
        return None
    j += 1

    m = at(RE_SIZE)
    if not m:
        return None
    size = int(m.group(1))
    j += 1

    m = at(RE_CLIP)
    if not m:
        return None
    corner = int(m.group(1))
    j += 1

    m = at(RE_BG_START)
    if not m:
        return None
    # background 表达式可能跨行，按括号配平吃掉
    depth = m.group(1).count("(") - m.group(1).count(")")
    j += 1
    while depth > 0:
        if j >= len(lines):
            return None
        depth += lines[j].count("(") - lines[j].count(")")
        j += 1

    if not at(RE_ALIGN):
        return None
    j += 1

    if not at(RE_BOX_BODY_OPEN):
        return None
    j += 1

    m = at(RE_ICON_OPEN) or at(RE_ICON_ONELINE)
    got = read_icon(lines, j)
    if not got:
        return None
    icon, tint, j = got

    if not at(RE_BRACE_CLOSE):
        return None
    j += 1

    repl = [
        indent + "IconBadge(",
        indent + "    icon = %s," % icon,
        indent + "    tint = %s," % tint,
        indent + "    size = %d.dp," % size,
        indent + "    corner = %d.dp" % corner,
        indent + ")",
    ]
    return repl, j


def convert(path, debug=False):
    lines = open(path, encoding="utf-8").read().split("\n")
    out, i, hits = [], 0, 0
    while i < len(lines):
        r = try_convert(lines, i)
        if r:
            repl, j = r
            out.extend(repl)
            i = j
            hits += 1
        else:
            out.append(lines[i])
            i += 1
    if hits:
        open(path, "w", encoding="utf-8").write("\n".join(out))
    return hits


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    debug = "--debug" in sys.argv
    root = args[0] if args else "app/src/main/java/com/bt/waf/ui/screens"
    total = 0
    for f in sorted(glob.glob(os.path.join(root, "*.kt"))):
        n = convert(f, debug=debug)
        if n:
            print("%-26s -> %d" % (os.path.basename(f), n))
            total += n
    print("合计替换：%d" % total)


if __name__ == "__main__":
    main()
