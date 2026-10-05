#!/usr/bin/env bash
# 真机页面遍历截图 + 对称性像素检测。
#
# 三个必须知道的坑（都踩过，别再犯）：
#
# 1. 坐标缩放：截图预览在界面上被缩到约 480x1080 显示，但 adb input tap
#    用的是 1080x2412 的真实像素。**肉眼目测的坐标直接拿去 tap 会偏 2.25 倍。**
#    本脚本里所有坐标都是真实像素值，由tools/scan_rows.py 扫像素反推得到。
#
# 2. 截图失败：MIUI 在软键盘弹出时 screencap 会返回 0 字节且不报错。
#    每次截图后检查字节数，为 0 则先按返回键收键盘再重试一次。
#
# 3. 抽屉可滚动：46 个入口放不下，底部 Tab 是固定的。
#    抽屉入口必须「滚到目标再点」，直接 tap 屏幕外坐标点不到。
#
# y 坐标的量法（别再靠目测）：
#   扫描 x=40..700 的深色像素(r<140)，按 y 聚类成文本行，取每行中心。
#   分组标题行高约 21px，入口行高 29-41px —— 行高能区分二者。
set -u
ADB="$HOME/.workbuddy/binaries/android/sdk/platform-tools/adb.exe"
PY="$HOME/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
OUT="${1:-shots/verify}"
PKG=com.bt.waf
mkdir -p "$OUT"

SHOT_FAIL=0
DUP=0
VISITED=""
# set -u 下必须先初始化，否则第一次比较就报 "unbound variable"
PREV_H=""

shot() {
  local name="$1"
  "$ADB" exec-out screencap -p > "$OUT/$name.png" 2>/dev/null
  local sz
  sz=$(stat -c%s "$OUT/$name.png" 2>/dev/null || echo 0)
  if [ "$sz" -lt 10000 ]; then
    # 键盘弹出时 screencap 会返回 0 字节，收起键盘再试
    "$ADB" shell input keyevent 4 >/dev/null 2>&1
    sleep 2
    "$ADB" exec-out screencap -p > "$OUT/$name.png" 2>/dev/null
    sz=$(stat -c%s "$OUT/$name.png" 2>/dev/null || echo 0)
  fi
  if [ "$sz" -ge 10000 ]; then
    local h
    h=$(md5sum "$OUT/$name.png" 2>/dev/null | cut -c1-10)
    # 与上一张完全同哈希 -> 页面没切换，这张没有验证价值
    if [ "$h" = "$PREV_H" ]; then
      DUP=$((DUP + 1))
      echo "  !! $name 与上一张内容相同( md5=$h )—— 点击很可能没生效"
    fi
    PREV_H="$h"
    VISITED="$VISITED $name"
  else
    SHOT_FAIL=$((SHOT_FAIL + 1))
    echo "  !! $name 截图失败(0 字节)"
  fi
  return 0
}

open_drawer() { "$ADB" shell input tap 75 198 >/dev/null 2>&1; sleep 2; }

# 回一级页（概览）。
#
# ⚠️ 只能用返回键，**不能用坐标点Tab 栏**：二级页面（抽屉进去的那些）
# 根本不渲染 bottomBar，此时 y=2245 落在页面内容上，点下去毫无意义。
# 之前三张 Tab 截图就是这么废掉的 —— 拍出来是「IP 白名单」这种二级页。
#
# 概览页再按返回键会退出 App，所以要查当前焦点，退出了就重新拉起。
back_home() {
  "$ADB" shell input keyevent 4 >/dev/null 2>&1
  sleep 2
  local focus
  focus=$("$ADB" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus)
  case "$focus" in
    *"$PKG"*) ;;
    *) "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1; sleep 6 ;;
  esac
}

# 校验截图确实是新页面：内容完全一样的截图说明点击没生效，
# 之前就因为 Tab y 坐标写错，三个 Tab 拍出同一张图还以为测过了。
#

# token 过期弹窗会盖住整页，挡住所有点击。
#
# 检测方式用像素而不是文件大小：AlertDialog 弹出时，屏幕左右边缘会被
# 半透明遮罩压暗。采样x=8 和 x=W-9 两列的平均亮度，明显低于正常页面
# 就说明有弹窗。之前用「截图字节数落在某个区间」来猜，
# 换张页面就失效，非常脆弱。
dialog_shown() {
  # 注意：PY 是 Windows 原生 python.exe，不认 Git Bash 的 /tmp 路径
  # （FileNotFoundError: '/tmp/_dlg.png'）。必须用 Windows 能解析的路径。
  local png
  png="$(cygpath -w "$OUT/_dlg.png" 2>/dev/null || echo "$OUT/_dlg.png")"
  "$ADB" exec-out screencap -p > "$OUT/_dlg.png" 2>/dev/null
  local sz
  sz=$(stat -c%s "$OUT/_dlg.png" 2>/dev/null || echo 0)
  [ "$sz" -lt 10000 ] && return 1
  "$PY" - "$png" <<'PYEOF'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
W, H = im.size
px = im.load()
ys = list(range(400, H - 400, 60))
tot = 0
for x in (8, W - 9):
    for y in ys:
        r, g, b = px[x, y]
        tot += (r + g + b) / 3
print("1" if tot / (2 * len(ys)) < 200 else "0")
PYEOF
}

clear_relogin_dialog() {
  if [ "$(dialog_shown | tail -1)" = "1" ]; then
    echo "    (检测到登录过期弹窗，点去登录)"
    "$ADB" shell input tap 800 1448 >/dev/null 2>&1
    sleep 4
  fi
}

# 抽屉入口 y 坐标（真实像素，1080x2412，2026-10-05 由 scan_rows.py 实测）。
#
# ⚠️ 旧版本的坐标表已作废：「攻击分析」组从 4 项扩到 6 项后，
# 后面所有入口整体下移约 174px。照旧表点会点到隔壁条目
# （曾经把「操作日志」当成了「防护规则总览」）。
#
# 与 MainScreen.kt 的 DRAWER_GROUPS 严格对应。区分方法：
# 分组标题用 labelSmall，行高约 21px；入口行高 29-41px。
# 标题行在338 / 1032 / 2038（攻击分析 / 防护策略 / 黑白名单），不可点。
#
# 第一屏（y < 2412）只放得下 20 个入口，后 26 个需要滚动才能点到。
ENTRIES=(
  # 攻击分析
  "01_report:415"          "02_attacklog:519"      "03_iprange:623"
  "04_ccevent:727"         "05_rulehit:831"        "06_optlog:936"
  # 防护策略（标题在 1032）
  "07_rules:1109"          "08_customize:1213"     "09_region:1317"
  "10_exclusive:1421"      "11_replace:1526"       "12_httpmethod:1629"
  "13_headerlen:1732"      "14_iplimit:1837"       "15_spider:1941"
  # 黑白名单（标题在 2038）
  "16_ipgroup:2115"        "17_ipblack:2219"       "18_ipwhite:2323"
)

echo "== 启动 App =="
"$ADB" shell am force-stop $PKG; sleep 1
"$ADB" shell am start -n $PKG/.MainActivity >/dev/null 2>&1; sleep 7
clear_relogin_dialog
shot 00_launch

echo "== 遍历抽屉入口（第一屏，真实像素坐标）=="
for e in "${ENTRIES[@]}"; do
  name="${e%%:*}"
  y="${e##*:}"
  open_drawer
  "$ADB" shell input tap 300 "$y" >/dev/null 2>&1
  sleep 4
  clear_relogin_dialog
  shot "$name"
  back_home
done

echo "== 底部 Tab =="
# ⚠️ 必须先回一级页（概览）再点 Tab。
# 二级页面（抽屉进去的那些）**根本不渲染 bottomBar**，
# 此时 y=2235 落在页面内容上：会误触 FAB 弹出「新增」对话框，
# 标题也回落成「堡塔云WAF」。之前三张 Tab 截图就是这么废掉的
# —— 拍出来是「IP 白名单」「拦截日志」这种二级页，看着像对了其实全错。
# 现在统一用 back_home()（返回键 + 焦点检测），不要用坐标点。
"$ADB" shell input tap 130 2245 >/dev/null 2>&1; sleep 2   # 返回
for t in "30_protect:430" "31_logs:720" "32_system:1010"; do
  name="${t%%:*}"; x="${t##*:}"
  back_home
  "$ADB" shell input tap "$x" 2235 >/dev/null 2>&1
  sleep 4
  clear_relogin_dialog
  shot "$name"
done

# 「关于作者」在抽屉最后一组，需要滚到底才能看到，不在上面那张坐标表里。
# 单独走一段：回概览 → 开抽屉 → 连滚三次到底 → 点最后一项。
echo "== 关于作者（需滚动到抽屉底部）=="
back_home
open_drawer
for _ in 1 2 3; do
  "$ADB" shell input swipe 400 1900 400 500 250 >/dev/null 2>&1
  sleep 1
done
# 滚到底后「关于作者」实测文本行 y=2096..2124，中心 2110。
# （旧值 2129 落在它下面几 px 的空白里，点击被吃掉，拍出来是概览页）
"$ADB" shell input tap 300 2110 >/dev/null 2>&1
sleep 3
shot 33_about
back_home

echo "== 完成 =="
echo "  截图目录: $OUT"
echo "  成功张数: $(echo $VISITED | wc -w)"
echo "  失败张数: $SHOT_FAIL"
echo "  重复张数: $DUP  (与上一张内容相同 = 点击没生效，这些页没验证到)"
