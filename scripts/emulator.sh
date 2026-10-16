#!/usr/bin/env bash
# AVD 生命周期管理：为本项目提供可复跑的「真机行为」验证环境。
#
#   bash scripts/emulator.sh create [phone|tablet|auto|all]
#   bash scripts/emulator.sh start  <avd> [--window]
#   bash scripts/emulator.sh wait   <avd>            # 阻塞直到 boot 完成
#   bash scripts/emulator.sh shot   <avd> <输出png>
#   bash scripts/emulator.sh stop   [avd|all]
#   bash scripts/emulator.sh list
#
# 存在意义：本机此前没有任何设备，导致悬浮歌词 / 平板双栏 / 车机三栏 / 逐字染色
# 等一批改动全部只到「编译通过 + 单测绿」。这里把模拟器变成一条可复跑的命令。
#
# ⚠️ 模拟器能验证布局与渲染，**不能**验证厂商 ROM 行为（HyperOS 的悬浮窗拦截）
#    与真实音频输出（EQ 听感）——那两项仍必须真机。
#
# ⚠️ Windows 侧两个坑（都踩过）：
#    ① `.bat` 必须经 `cmd //c` 调用，且包名里的 `;` 与 `"` 不要再包一层引号
#       （cmd 会把引号原样传给 Java → "Failed to find package"）。
#    ② adb 给模拟器的 serial 是**端口号**（emulator-5554），不是 AVD 名。
#       早期版本这里直接拼 `emulator-<avd>` → `wait-for-device` 永久阻塞。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# SDK 路径以 local.properties 为唯一出处（与 Gradle 保持一致），不写死。
# ⚠️ Properties 里的反斜杠是转义的：`C\:\\path\\to` 要先还原成 `C:/path/to`。
#    直接删反斜杠会把 `C:` 和后续片段粘成一串，所以顺序是：`\\`→`/`，`\:`→`:`。
SDK=""
if [[ -f "$REPO_ROOT/local.properties" ]]; then
  SDK="$(grep -E '^sdk\.dir=' "$REPO_ROOT/local.properties" | head -1 | cut -d= -f2- \
    | sed -e 's/\\\\/\//g' -e 's/\\:/:/g')"
fi
if [[ -z "$SDK" || ! -d "$SDK" ]]; then
  echo "找不到 Android SDK：请检查 local.properties 的 sdk.dir（解析结果：$SDK）" >&2
  exit 1
fi
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

export JAVA_HOME="${JAVA_HOME_OVERRIDE:-C:/Users/stu_l/.jdks/openjdk-21.0.2}"
if [[ "$JAVA_HOME" == *graal* ]]; then
  echo "JAVA_HOME 指向 graalvm，AGP/sdkmanager 不可用：$JAVA_HOME" >&2
  exit 1
fi

EMULATOR="$SDK/emulator/emulator.exe"
ADB="$SDK/platform-tools/adb.exe"
AVDMANAGER_DIR="$SDK/cmdline-tools/latest/bin"

[[ -x "$ADB" ]] || { echo "未安装 platform-tools（缺 $ADB）" >&2; exit 1; }

# AVD 名 → 设备档案 / 镜像 的固定映射，避免每次手敲参数。
avd_device() {
  case "$1" in
    wt_phone)  echo "pixel_7" ;;
    wt_tablet) echo "pixel_tablet" ;;
    wt_auto)   echo "automotive_1080p_landscape" ;;
    *) echo "" ;;
  esac
}

avd_image() {
  case "$1" in
    wt_phone)  echo "system-images;android-35;google_apis;x86_64" ;;
    wt_tablet) echo "system-images;android-35;google_apis_tablet;x86_64" ;;
    wt_auto)   echo "system-images;android-35-ext15;android-automotive;x86_64" ;;
    *) echo "" ;;
  esac
}

all_avds() { echo "wt_phone wt_tablet wt_auto"; }

# 反查 AVD 对应的 adb serial（见文件头坑 ②）。
serial_of() {
  local avd="$1" s
  for s in $("$ADB" devices 2>/dev/null | awk '/^emulator-/ {print $1}'); do
    if [[ "$("$ADB" -s "$s" emu avd name 2>/dev/null | tr -d '\r' | head -1)" == "$avd" ]]; then
      echo "$s"
      return 0
    fi
  done
  return 1
}

require_emulator() {
  [[ -x "$EMULATOR" ]] || {
    echo "未安装 emulator 包：sdkmanager --install emulator" >&2
    exit 1
  }
}

cmd_create() {
  local which="${1:-all}"
  require_emulator
  local targets
  if [[ "$which" == "all" ]]; then targets="$(all_avds)"; else targets="$which"; fi

  for avd in $targets; do
    local device image
    device="$(avd_device "$avd")"
    image="$(avd_image "$avd")"
    if [[ -z "$device" ]]; then
      echo "未知 AVD：$avd（可选 wt_phone / wt_tablet / wt_auto）" >&2
      exit 1
    fi
    if "$EMULATOR" -list-avds 2>/dev/null | grep -qx "$avd"; then
      echo "已存在，跳过：$avd"
      continue
    fi
    echo "创建 $avd  (device=$device, image=$image)"
    # 分号是包名的一部分，绝不能加引号：cmd 会把引号原样传进 Java。
    ( cd "$AVDMANAGER_DIR" && echo no | cmd //c "avdmanager.bat create avd -n $avd -k $image -d $device --force" ) >/dev/null
  done
  cmd_list
}

cmd_list() {
  require_emulator
  echo "已创建的 AVD："
  "$EMULATOR" -list-avds 2>/dev/null | sed 's/^/  /'
  echo "在线设备："
  "$ADB" devices | sed -n '2,$p' | sed 's/^/  /'
}

cmd_start() {
  local avd="${1:?用法: start <avd> [--window]}"
  local window="${2:-}"
  require_emulator
  if serial_of "$avd" >/dev/null; then
    echo "$avd 已在运行"
    return
  fi
  local args=(-avd "$avd" -no-boot-anim -no-snapshot -no-audio)
  if [[ "$window" == "--window" ]]; then
    args+=(-gpu host)
  else
    # 无窗口 + 软件渲染：截图仍可经 adb screencap 拿到真实帧缓冲。
    args+=(-no-window -gpu swiftshader_indirect)
  fi
  mkdir -p "$REPO_ROOT/build"
  echo "启动 $avd ..."
  "$EMULATOR" "${args[@]}" >"$REPO_ROOT/build/emulator-$avd.log" 2>&1 &
  echo "日志： build/emulator-$avd.log"
}

cmd_wait() {
  local avd="${1:?用法: wait <avd>}"
  echo "等待 $avd 启动 ..."
  local serial="" i=0
  while :; do
    if serial="$(serial_of "$avd")" \
      && [[ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      break
    fi
    sleep 3
    i=$((i + 1))
    if [[ $i -gt 120 ]]; then echo "启动超时（6 分钟）" >&2; exit 1; fi
  done
  for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$ADB" -s "$serial" shell settings put global "$scale" 0 >/dev/null 2>&1 || true
  done
  echo "$serial 已就绪（动画已关闭，便于截图对比）"
}

cmd_shot() {
  local avd="${1:?用法: shot <avd> <输出png>}"
  local out="${2:?用法: shot <avd> <输出png>}"
  local serial
  serial="$(serial_of "$avd")" || { echo "$avd 未在运行" >&2; exit 1; }
  mkdir -p "$(dirname "$out")"
  "$ADB" -s "$serial" exec-out screencap -p > "$out"
  echo "已保存 $out ($(wc -c < "$out") 字节)"
}

cmd_serial() {
  local avd="${1:?用法: serial <avd>}"
  serial_of "$avd" || { echo "$avd 未在运行" >&2; exit 1; }
}

cmd_stop() {
  local target="${1:-all}"
  if [[ "$target" == "all" ]]; then
    for avd in $(all_avds); do
      local s; s="$(serial_of "$avd")" && "$ADB" -s "$s" emu kill >/dev/null 2>&1 || true
    done
    echo "已请求关闭全部 AVD"
  else
    local s; s="$(serial_of "$target")" && "$ADB" -s "$s" emu kill >/dev/null 2>&1 || true
    echo "已请求关闭 $target"
  fi
}

case "${1:-}" in
  create) shift; cmd_create "$@" ;;
  start)  shift; cmd_start "$@" ;;
  wait)   shift; cmd_wait "$@" ;;
  shot)   shift; cmd_shot "$@" ;;
  serial) shift; cmd_serial "$@" ;;
  stop)   shift; cmd_stop "$@" ;;
  list)   shift; cmd_list "$@" ;;
  *)
    sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
