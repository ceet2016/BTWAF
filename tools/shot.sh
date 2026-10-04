#!/usr/bin/env bash
# 真机 UI 验证辅助脚本
#   ./tools/shot.sh <输出名>          截图
#   ./tools/shot.sh tap <x> <y>       点击（物理像素）
#   ./tools/shot.sh text "..."        输入文本
#   ./tools/shot.sh swipe x1 y1 x2 y2 [ms]
#   ./tools/shot.sh back              返回键
# 所有截图统一保存到 test/shots/ 并回显绝对路径。
set -uo pipefail

export ANDROID_HOME="$HOME/.workbuddy/binaries/android/sdk"
A="$ANDROID_HOME/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1
SHOTS="C:/Users/Administrator/Desktop/test/shots"
mkdir -p "$SHOTS"

wake() {
  "$A" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  local st
  st=$("$A" shell dumpsys power 2>/dev/null | grep -o 'mWakefulness=[A-Za-z]*' | head -1)
  if [ "$st" != "mWakefulness=Awake" ]; then
    "$A" shell input keyevent 224 >/dev/null 2>&1
    sleep 1
  fi
}

shot() {
  wake
  local f="$SHOTS/$1.png"
  "$A" exec-out screencap -p > "$f" 2>/dev/null
  # 息屏时 screencap 会返回 0 字节，重试一次
  if [ ! -s "$f" ]; then
    "$A" shell input keyevent 224 >/dev/null 2>&1
    sleep 2
    "$A" exec-out screencap -p > "$f" 2>/dev/null
  fi
  if [ -s "$f" ]; then
    echo "OK $f ($(stat -c%s "$f") bytes)"
  else
    echo "FAIL $f (0 bytes)"
  fi
}

case "${1:-shot}" in
  shot)  shot "${2:-shot}" ;;
  tap)   wake; "$A" shell input tap "$2" "$3" >/dev/null 2>&1; sleep "${4:-1}" ;;
  text)  wake; "$A" shell input text "$2" >/dev/null 2>&1; sleep 1 ;;
  swipe) wake; "$A" shell input swipe "$2" "$3" "$4" "$5" "${6:-300}" >/dev/null 2>&1; sleep 1 ;;
  back)  wake; "$A" shell input keyevent 4 >/dev/null 2>&1; sleep "${2:-1}" ;;
  key)   wake; "$A" shell input keyevent "$2" >/dev/null 2>&1; sleep 1 ;;
  *)     echo "usage: $0 {shot <name>|tap <x> <y>|text <s>|swipe x1 y1 x2 y2|back|key <code>}"; exit 1 ;;
esac
