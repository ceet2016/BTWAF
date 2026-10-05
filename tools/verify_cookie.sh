#!/usr/bin/env bash
# 退出登录 → 重新登录 → 抓 Cookie 请求头，验证 aawaf SID Cookie 是否带上。
#
# 手动敲 adb 命令序列容易互相干扰（keyevent 4 在抽屉打开时会关抽屉、
# 再往下点就点到桌面了），所以固化成一个脚本跑。
set -u
export PATH="$HOME/.workbuddy/binaries/android/sdk/platform-tools:$PATH"
ADB="$HOME/.workbuddy/binaries/android/sdk/platform-tools/adb.exe"
cd "$(dirname "$0")/.."
OUT="shots"
mkdir -p "$OUT"

# ⚠️ 密码从环境变量读，**不要在这里写明文**。
# 这份脚本是要进git 的，任何写死的口令都会跟着仓库一起公开。
# 用法：BTWAF_PW='你的密码' bash tools/verify_cookie.sh
: "${BTWAF_PW:?请先设置环境变量 BTWAF_PW 再运行本脚本}"
PW="$BTWAF_PW"

"$ADB" shell am force-stop com.bt.waf
sleep 1
"$ADB" shell am start -n com.bt.waf/.MainActivity >/dev/null
sleep 4

# 若已在主界面，先走抽屉退出登录
state=$("$ADB" shell dumpsys activity activities 2>/dev/null | grep -c "com.bt.waf/.MainActivity")
"$ADB" exec-out screencap -p > "$OUT/_pre.png"

# 点汉堡
"$ADB" shell input tap 65 175
sleep 2
# 滚到底
for _ in 1 2 3; do
  "$ADB" shell input swipe 400 1900 400 600 250
  sleep 1
done
"$ADB" exec-out screencap -p > "$OUT/_drawer.png"

# 「退出登录」在抽屉最底部：截图显示 y≈1011(480宽) → 物理 1011*2.25≈2275
"$ADB" shell input tap 300 2270
sleep 4
"$ADB" exec-out screencap -p > "$OUT/_after_logout.png"

# 现在应在登录页
"$ADB" shell input tap 400 1178
sleep 1
"$ADB" shell input text "$PW"
sleep 1
"$ADB" logcat -c
"$ADB" shell input keyevent 66
sleep 9
"$ADB" exec-out screencap -p > "$OUT/_after_login.png"

echo "=== 登录相关请求 ==="
# 只按 action 名过滤，**不要 grep 具体主机或入口路径** ——
# 日志里那些东西正是这个脚本要证明「确实带上Cookie」的证据，
# 而把它们写进 grep 模式就等于把真实部署信息抄进了要入库的文件。
"$ADB" logcat -d 2>/dev/null | grep -E "okhttp" \
  | grep -E "user/login" | head -8
echo
echo "=== Cookie 请求头（/api 请求里有没有 f49ac9b4...）==="
"$ADB" logcat -d 2>/dev/null | grep -E "okhttp" \
  | grep -iE "cookie|host:|--> POST|<--" | head -20
echo
echo "=== 返回码统计 ==="
"$ADB" logcat -d 2>/dev/null | grep -oE "<-- [0-9]{3}" | sort | uniq -c