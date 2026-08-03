#!/usr/bin/env bash
# 全自主眨眼测试闭环:引导前台服务 → 注入脚本化眨眼 → 抓三层日志。
# 用法: bash testloop.sh <pattern> [gap]
#   pattern: fast3 | slow3 | stray1 | hold | mixed
export MSYS2_ARG_CONV_EXCL='*'
adb="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
PATTERN="${1:-fast3}"
GAP="${2:-}"

# 若服务没在跑,先 am start MainActivity --ez autostart true 解冻进程+起前台服务
if ! "$adb" shell dumpsys activity services com.eyecontrol.app 2>/dev/null | grep -q "isForeground=true"; then
  "$adb" logcat -c
  "$adb" shell am start -n com.eyecontrol.app/.MainActivity --ez autostart true >/dev/null
  for i in $(seq 1 20); do
    "$adb" logcat -d -s CameraService:I 2>/dev/null | grep -q "检测就绪" && break
    sleep 1
  done
fi

# 注入
"$adb" logcat -c
if [ -n "$GAP" ]; then
  "$adb" shell am broadcast -a com.eyecontrol.app.DEBUG_BLINK_SHELL --es pattern "$PATTERN" --ei gap "$GAP" -p com.eyecontrol.app >/dev/null
else
  "$adb" shell am broadcast -a com.eyecontrol.app.DEBUG_BLINK_SHELL --es pattern "$PATTERN" -p com.eyecontrol.app >/dev/null
fi
for i in $(seq 1 22); do
  "$adb" logcat -d -s DebugBlink:I 2>/dev/null | grep -q "注入结束" && break
  sleep 1
done

echo "===== INJECTOR (pattern=$PATTERN gap=${GAP:-default}) ====="
"$adb" logcat -d -s DebugBlink:I 2>/dev/null | grep -E "注入开始|预热完毕|▷|注入结束" | sed -E 's/^([0-9-]+ [0-9:.]+).*: /\1 /'
echo "===== THREE LAYERS (signal / display / behavior) ====="
"$adb" logcat -d -s CameraService:I OverlayView:I 2>/dev/null | grep -E "连眨计数|渲染亮点|★命中|发滑动|检测就绪|环境突变" | sed -E 's/^([0-9-]+ [0-9:.]+) [0-9]+ +[0-9]+ I (CameraService|OverlayView): /\1 [\2] /'
