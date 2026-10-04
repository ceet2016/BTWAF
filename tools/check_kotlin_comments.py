#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Kotlin 块注释配平检查（考虑 Kotlin 的「嵌套块注释」语义）。

背景：Kotlin 的 /* */ 支持嵌套，不像 Java 会遇 /* 就找最近的 */。
因此若在注释文本里写了形如 `path/*.go`、`/api/*` 这类片段，
其中的 /* 会被当作新的注释起点，导致后续 */ 无法闭合，
编译报 "Unclosed comment"，并且该文件里所有声明全部解析失败
（表现为几十条 "Unresolved reference" 连锁错误）。

用法：python check_kotlin_comments.py [源码根目录]
退出码 0 = 全部配平；1 = 发现问题。
"""
import io
import os
import sys


def scan(text):
    """返回 (状态, 首个出问题的行号)。状态为 ok / unclosed / extra_close。

    必须跳过字符串字面量：像 "*/*" 这种 MIME 通配符写在代码里时，
    其中的 */ 会被本脚本误判成注释提前闭合（曾因此报假警）。
    """
    depth = 0
    line = 1
    opened_at = 0
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        # 行注释：直接跳到行尾
        if c == '/' and i + 1 < n and text[i + 1] == '/':
            while i < n and text[i] != '\n':
                i += 1
            continue
        # 字符串字面量："..." 与 """..."""
        if c == '"':
            triple = text.startswith('"""', i)
            i += 3 if triple else 1
            while i < n:
                if text[i] == '\\':
                    i += 2
                    continue
                if text[i] == '\n':
                    line += 1
                    if not triple:
                        break  # 单行字符串未闭合，交给 Kotlin 自己报错
                if triple and text.startswith('"""', i):
                    i += 3
                    break
                if not triple and text[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        # 字符字面量 'x'
        if c == "'":
            i += 1
            while i < n:
                if text[i] == '\\':
                    i += 2
                    continue
                if text[i] == "'":
                    i += 1
                    break
                if text[i] == '\n':
                    line += 1
                    break
                i += 1
            continue
        if text.startswith('/*', i):
            depth += 1
            if depth == 1:
                opened_at = line
            i += 2
            continue
        if text.startswith('*/', i):
            depth -= 1
            if depth < 0:
                return 'extra_close', line
            i += 2
            continue
        if c == '\n':
            line += 1
        i += 1
    if depth > 0:
        return 'unclosed', opened_at
    return 'ok', 0


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else '.'
    bad = []
    for dirpath, _dirs, files in os.walk(root):
        for f in files:
            if not f.endswith('.kt'):
                continue
            p = os.path.join(dirpath, f)
            try:
                text = io.open(p, encoding='utf-8').read()
            except UnicodeDecodeError:
                continue
            status, line = scan(text)
            if status != 'ok':
                bad.append((p, status, line))

    if not bad:
        print('OK: 所有 Kotlin 文件的块注释均已配平')
        return 0

    print('发现 %d 个文件的块注释未配平：' % len(bad))
    for p, status, line in bad:
        print('  [%s] %s:%d' % (status, p, line))
    print('\n提示：注释里请勿出现 /* 或 */ 字面量（如 `a/*.go`、`/api/*`），')
    print('Kotlin 的块注释可嵌套，会导致注释提前闭合/不闭合。')
    return 1


if __name__ == '__main__':
    sys.exit(main())
