#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Compose UI 对称性静态审查。

背景：用户反馈「页面有的地方不对称，比如防护」「页面很丑」。
对称性不是一个靠肉眼在 20 个页面里盯出来的问题，而是若干个可枚举的
写法缺陷叠加。这里把缺陷模式写成规则，逐文件扫描并输出可疑行。

规则来源（每条都对应一种真实的视觉错位）：
  R1  同一 Composable 内出现多个互不相等的 padding 值
      -> 同类内容左右/上下留白不一致
  R2  用 Spacer(weight) 在两段文字之间撑开做「居中」
      -> 中间标签并不在几何中心，左边文字越长越偏
  R3  栅格行里出现裸 Box/Column(weight(1f)) 而不是 GridRow/GridCell
      -> 列宽靠手调，容易左宽右窄或间距不等
  R4  手写 Row{ } + 手调 Spacer(width) 做等宽分栏
      -> 与 R3 同源，GridRow 已封装
  R5  写死 dp 的固定宽高做自适应布局（超过 120.dp 视为可疑）
      -> 窄屏溢出 / 宽屏留白
  R6  Icon 尺寸未走 IconBadge（散落的裸 Icon(..., Modifier.size(x.dp))）
      -> 图标大小不统一，视觉上「东一块西一块」
  R7  直接写 Card( / Surface( 圆角而未走 WafCard
      -> 圆角与边框跟全站不一致
  R8  TextStyle 写死 fontSize 而不是 MaterialTheme.typography
      -> 字号体系被打乱

只做报告，不改代码。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "app/src/main/java/com/bt/waf/ui"

RULES = [
    ("R1-padding", "同一函数内 padding 值不一致"),
    ("R2-spacer-weight", "用 Spacer(weight) 撑开做居中"),
    ("R3-bare-weight-box", "裸 weight(1f) 栅格，未走 GridRow/GridCell"),
    ("R4-manual-columns", "手写 Row + 手调 Spacer 做等宽分栏"),
    ("R5-hard-size", "写死 dp 做自适应布局"),
    ("R6-bare-icon", "裸 Icon 尺寸，未走 IconBadge"),
    ("R7-bare-card", "裸 Card/Surface 圆角，未走 WafCard"),
    ("R8-hard-fontsize", "写死 fontSize，未走 typography"),
    ("R9-token-split", "同类元素跨文件取值分裂，未走设计系统常量"),
    ("R10-list-padding", "列表容器内边距 bottom 值分裂（末项与 FAB 间距不一致）"),
]

# R9：属性名 -> 允许出现的取值集合。设计系统里已有标准常量时，
# 页面就该引用常量而不是自己写字面量。
TOKEN_ALLOW = {
    "contentPadding": {
        "cardPadding",        # 信息卡内边距（Widgets.kt 定义）
        "rowCardPadding",     # 列表行卡内边距（Widgets.kt 定义）
        "rvTabPadding()",     # 规则页列表容器，无 FAB，底边留白更大
        # 设置页 SettingsDialog 自绘的确认按钮，与 FormDialog 用同一个值
        "PaddingValues(horizontal = 20.dp)",
    },
    "horizontalArrangement": {"Arrangement.spacedBy(Space.sm)",
                              "Arrangement.spacedBy(Space.xs)",
                              "Arrangement.spacedBy(Space.md)",
                              "Arrangement.spacedBy(gap)",
                              "Arrangement.SpaceEvenly",
                              "Arrangement.SpaceBetween",
                              "Arrangement.Center",
                              "Arrangement.End",
                              "Arrangement.Start"},
}


def audit_tokens(files):
    """
    跨文件统计同类属性的取值分布。

    要点：contentPadding 的写法有两种形态 ——
      单行：contentPadding = PaddingValues(Space.lg)
      跨行：contentPadding = PaddingValues(
                start = Space.lg, end = Space.lg,
                top = Space.md, bottom = 88.dp
            )
    所以要先做一次「把跨行调用折叠成一行」的预处理，否则跨行的那些
    一行都匹配不到，规则会假装全部通过（假阴性）。

    两类合法豁免：
      1. 文件是 Widgets.kt —— 那是设计系统本身，组件自身的 contentPadding
         就是要给 Button/TextField 定尺寸，不能跟页面卡片标准混为一谈。
      2. 取值形如 start/end/top/bottom = ... —— 那是 LazyColumn 容器内边距，
         语义是「给 FAB 让位的距离」，由 R10 单独盯。
    """
    dist = {}
    for f in files:
        text = fold_calls(f.read_text(encoding="utf-8"))
        for idx, raw in enumerate(text.split("\n"), start=1):
            clean = "".join(strip_line(raw))
            for key in TOKEN_ALLOW:
                # 取值要能跨过一层括号：spacedBy(Space.sm) 必须整体取到。
                m = re.search(re.escape(key) + r"\s*=\s*((?:[A-Za-z0-9_.*]*\([^()]*\))|[A-Za-z0-9_.*]+)", clean)
                if not m:
                    continue
                v = m.group(1).strip()
                if f.name == "Widgets.kt":
                    continue
                # 跨行折叠后仍带具名参数 => 列表容器，交给 R10
                if key == "contentPadding" and re.search(r"\b(start|end|top|bottom)\s*=", v):
                    continue
                dist.setdefault(key, {}).setdefault(v, []).append(f"{f.name}:{idx}")
    findings = []
    for key, vals in dist.items():
        standard = TOKEN_ALLOW[key]
        odd = {v: locs for v, locs in vals.items() if v not in standard}
        if odd:
            detail = "; ".join(f"[{v}] x{len(l)} @ {l[0]}" for v, l in sorted(odd.items()))
            findings.append(("R9-token-split", key, 0, detail))
        total = sum(len(l) for l in vals.values())
        print(f"  [{key}] 共 {total} 处，{len(vals)} 种取值：")
        for v, locs in sorted(vals.items(), key=lambda x: -len(x[1])):
            mark = "OK " if v in standard else "!! "
            print(f"     {mark}{v:46s} x{len(locs):3d}  {locs[0]}")
    return findings


def fold_calls(text):
    """把跨行的调用折叠成一行：foo(\n  a,\n  b\n) -> foo(a, b)"""
    out = []
    buf = ""
    depth = 0
    for raw in text.split("\n"):
        line = raw.strip()
        if not buf:
            # 没在折叠中：只看有没有未闭合的调用/括号
            clean = "".join(strip_line(raw))
            opens = clean.count("(") - clean.count(")")
            if opens > 0:
                buf = clean.strip()
                depth = opens
            else:
                out.append(raw)
        else:
            clean = "".join(strip_line(raw)).strip()
            buf += " " + clean
            depth += clean.count("(") - clean.count(")")
            if depth <= 0:
                out.append(re.sub(r"\s+", " ", buf))
                buf = ""
                depth = 0
    if buf:
        out.append(re.sub(r"\s+", " ", buf))
    return "\n".join(out)


def audit_list_padding(files):
    """
    R10：LazyColumn 容器内边距的 bottom 值必须唯一。

    这条独立于R9，因为它的语义不同：卡片的 bottom 走 rowCardPadding 统一，
    但列表容器的 bottom 是「给 FAB 让位的距离」，之前全站同时存在 88.dp
    和 92.dp 两个值，导致有的页面末项离 FAB 更近。
    """
    vals = {}
    pat = re.compile(
        r"contentPadding = PaddingValues\(\s*"
        r"(?:start\s*=\s*Space\.\w+\s*,\s*end\s*=\s*Space\.\w+\s*,\s*)?"
        r"(?:top\s*=\s*Space\.\w+\s*,\s*)?"
        r"bottom\s*=\s*([0-9.]+\.dp|Space\.\w+)")
    for f in files:
        text = fold_calls(f.read_text(encoding="utf-8"))
        for idx, raw in enumerate(text.split("\n"), start=1):
            clean = "".join(strip_line(raw))
            m = pat.search(clean)
            if m:
                vals.setdefault(m.group(1), []).append(f"{f.name}:{idx}")
    total = sum(len(l) for l in vals.values())
    print(f"  [列表容器 bottom] 共 {total} 处，{len(vals)} 种取值：")
    for v, locs in sorted(vals.items(), key=lambda x: -len(x[1])):
        print(f"     {'OK ' if len(vals) == 1 else '!! '}{v:12s} x{len(locs):3d}  {locs[0]}")
    if len(vals) <= 1:
        return []
    return [("R10-list-padding", "bottom",
             0, "; ".join(f"[{v}] x{len(l)} @ {l[0]}" for v, l in sorted(vals.items())))]


def split_functions(text):
    """按顶层函数切块。返回 [(name, start_line, body)]。"""
    out = []
    lines = text.split("\n")
    i = 0
    n = len(lines)
    while i < n:
        m = re.match(r"^(?:@\w+\s+)*(?:private\s+|internal\s+|public\s+)?"
                     r"(?:suspend\s+)?fun\s+([A-Za-z_]\w*)", lines[i])
        if m:
            name = m.group(1)
            start = i + 1
            # 花括号配平（跳过字符串与注释里的括号）
            depth = 0
            seen = False
            j = i
            while j < n:
                for ch in strip_line(lines[j]):
                    if ch == "{":
                        depth += 1
                        seen = True
                    elif ch == "}":
                        depth -= 1
                if seen and depth <= 0:
                    break
                j += 1
            out.append((name, start, "\n".join(lines[start - 1:j + 1])))
            i = j + 1
        else:
            i += 1
    return out


def strip_line(line):
    """去掉注释与字符串字面量，避免噪声匹配。"""
    out = []
    i = 0
    n = len(line)
    while i < n:
        c = line[i]
        if c == '"':
            triple = line.startswith('"""', i)
            i += 3 if triple else 1
            while i < n:
                if line[i] == "\\":
                    i += 2
                    continue
                if triple and line.startswith('"""', i):
                    i += 3
                    break
                if not triple and line[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if c == "/" and i + 1 < n and line[i + 1] == "/":
            break
        if c == "/" and i + 1 < n and line[i + 1] == "*":
            i += 2
            while i + 1 < n and not (line[i] == "*" and line[i + 1] == "/"):
                i += 1
            i += 2
            continue
        out.append(c)
        i += 1
    return out


def find_all(text, pattern):
    """返回 [(line_no, matched_text)]，line_no 为 1 基。"""
    res = []
    for idx, line in enumerate(text.split("\n"), start=1):
        clean = "".join(strip_line(line))
        for m in re.finditer(pattern, clean):
            res.append((idx, m.group(0)))
    return res


def scan(path):
    text = path.read_text(encoding="utf-8")
    findings = []

    for name, start, body in split_functions(text):
        lines = body.split("\n")
        base = start - 1

        # R1 padding 不一致
        pads = {}
        for idx, line in enumerate(lines):
            clean = "".join(strip_line(line))
            for m in re.finditer(r"\.padding\(([^)]*)\)", clean):
                key = m.group(1).strip()
                pads.setdefault(key, []).append(base + idx + 1)
        if len(pads) >= 3:
            detail = "; ".join(f"[{k}] x{len(v)}@{v[0]}" for k, v in sorted(pads.items()))
            findings.append(("R1-padding", name, start, detail))

        # R2 Spacer(weight) 撑开
        for idx, line in enumerate(lines):
            clean = "".join(strip_line(line))
            if re.search(r"Spacer\(\s*Modifier\.weight\(", clean):
                findings.append(("R2-spacer-weight", name, base + idx + 1, clean.strip()[:90]))

        # R3 裸 weight 栅格
        bare = 0
        for idx, line in enumerate(lines):
            clean = "".join(strip_line(line))
            if re.search(r"\b(weight\(1f\)|weight\(0\.5f\))", clean):
                bare += 1
        if bare >= 3:
            findings.append(("R3-bare-weight-box", name, start, f"函数内出现 {bare} 处 weight 分栏"))

        # R4 手写等宽分栏：Row 内连续 Spacer(width) >= 2
        sp = len(re.findall(r"Spacer\(\s*Modifier\.width\(", body))
        if sp >= 3:
            findings.append(("R4-manual-columns", name, start, f"Spacer(width) x{sp}"))

        # R5 写死大尺寸
        big = find_all(body, r"\.size\(\s*(\d{3,})\s*\.dp")
        big = [b for b in big if int(re.search(r"(\d{3,})", b[1]).group(1)) >= 120]
        for ln, txt in big:
            findings.append(("R5-hard-size", name, base + ln - 1, txt))

        # R6 裸 Icon 尺寸
        for idx, line in enumerate(lines):
            clean = "".join(strip_line(line))
            if re.search(r"\bIcon\(", clean) and re.search(r"Modifier\.size\(\s*\d+", clean):
                findings.append(("R6-bare-icon", name, base + idx + 1, clean.strip()[:90]))

        # R7 裸 Card / Surface 圆角
        for idx, line in enumerate(lines):
            clean = "".join(strip_line(line))
            if re.match(r"\s*(Card|Surface)\(", clean):
                findings.append(("R7-bare-card", name, base + idx + 1, clean.strip()[:90]))

        # R8 写死字号
        for idx, line in enumerate(lines):
            clean = "".join(strip_line(line))
            if re.search(r"fontSize\s*=\s*\d+", clean):
                findings.append(("R8-hard-fontsize", name, base + idx + 1, clean.strip()[:90]))

    return findings


def main():
    if not ROOT.exists():
        print(f"[!] 找不到目录: {ROOT}")
        sys.exit(1)
    files = sorted(list(ROOT.rglob("*.kt")))
    total = 0
    per_rule = {}
    per_file = {}
    for f in files:
        if f.name in ("Theme.kt",):
            continue
        fs = scan(f)
        if fs:
            per_file[f.name] = fs
            total += len(fs)
            for r, _, _, _ in fs:
                per_rule[r] = per_rule.get(r, 0) + 1

    print("=" * 72)
    print("Compose 对称性静态审查")
    print("=" * 72)
    print(f"扫描 {len(files)} 个文件，命中 {total} 处\n")

    print("【设计系统常量使用情况（R9 / R10）】")
    screen_files = [f for f in files if f.name != "Theme.kt"]
    tok = audit_tokens(screen_files)
    print()
    lp = audit_list_padding(screen_files)
    print()
    for f in tok + lp:
        total += 1
        per_rule[f[0]] = per_rule.get(f[0], 0) + 1

    print("【按规则汇总】")
    desc = dict(RULES)
    for r, c in sorted(per_rule.items(), key=lambda x: -x[1]):
        print(f"  {r:22s} {c:4d}   {desc.get(r, '')}")
    print()

    print("【按文件汇总】")
    for fname, fs in sorted(per_file.items(), key=lambda x: -len(x[1])):
        byrule = {}
        for r, _, _, _ in fs:
            byrule.setdefault(r, 0)
            byrule[r] += 1
        top = ", ".join(f"{k}x{v}" for k, v in sorted(byrule.items(), key=lambda x: -x[1]))
        print(f"  {fname:26s} {len(fs):4d}   {top}")
    print()

    print("【R1 padding 不一致明细（最常见的「不对称」来源）】")
    r1 = [(fn, f) for fn, fs in per_file.items() for f in fs if f[0] == "R1-padding"]
    if not r1:
        print("  （无）")
    for fn, f in r1:
        print(f"  {fn} :: fun {f[1]}  L{f[2]}  {f[3]}")

    print()
    print("【R2 Spacer(weight) 撑开明细（中间标签偏斜的直接原因）】")
    r2 = [(fn, f) for fn, fs in per_file.items() for f in fs if f[0] == "R2-spacer-weight"]
    if not r2:
        print("  （无）")
    for fn, f in r2:
        print(f"  {fn}:{f[2]}  {f[3]}  (in fun {f[1]})")

    print()
    print("=" * 72)
    return 0


if __name__ == "__main__":
    sys.exit(main())