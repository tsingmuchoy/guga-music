#!/usr/bin/env bash
# 把本应用「追加」到 vivo 原子随身听（com.vivo.musicwidgetmix）的常驻音乐 App 白名单末尾。
#
# 【为什么必须追加，不能覆盖】
# AppUtils.Z(ctx, 包名) 判断音源准入：
#     名单 = Settings.Secure["music_app_white_list"]（云控 JSON）∪ 13 个 framework_support_list
#     名单为空时，退回 s4.m0.a().d() —— 硬编码在 widget APK 里的 61 个音乐/听书 App
# 一旦往 music_app_white_list 写了东西，那份 61 个 App 的硬编码名单就会被完全屏蔽。
# 只写自己一个 → QQ音乐 / 网易云 / 波点音乐等全部从小部件里消失（本脚本 v1 踩过这个坑）。
#
# 所以：若云控名单为空，先用 tools/vivo-system-apps.json 完整重建那 61 个，
# 再在末尾追加本应用；若云控名单已有内容，则原样保留、只追加本应用。
#
# 【用法】
#   bash tools/vivo-whitelist.sh             # 追加并重启小部件
#   bash tools/vivo-whitelist.sh --status    # 只看当前状态
#   bash tools/vivo-whitelist.sh --restore   # 还原到写入前
#   PKG=com.example.app bash tools/vivo-whitelist.sh
#
# 【注意】
# - 写完必须重启小部件进程，它只在启动时读一次这份名单。
# - vivo 云控下次下发会覆盖回原值，固件升级后可能需要重跑。
# - shell 权限（adb / Shizuku）即可，不需要 root。
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-adb}"
PKG="${PKG:-com.guga.music}"
KEY="music_app_white_list"
BACKUP="$HERE/.vivo-whitelist.bak"

command -v "$ADB" >/dev/null 2>&1 || { echo "✘ 找不到 adb，请用 ADB=/path/to/adb 指定" >&2; exit 1; }
PY="$(command -v python || command -v python3)"
[ -n "$PY" ] || { echo "✘ 需要 python" >&2; exit 1; }

get()  { "$ADB" shell settings get secure "$KEY" 2>/dev/null | tr -d '\r' | head -1; }
put()  { "$ADB" shell "settings put secure $KEY '$1'" >/dev/null; }
chk()  { "$ADB" shell settings get secure resident_music_app_white_list 2>/dev/null | tr -d '\r' | head -1; }

if [ "$1" = "--status" ]; then
  echo "云控名单 $KEY = $(get)"
  echo "小部件生效名单 resident_music_app_white_list = $(chk)"
  exit 0
fi

if [ "$1" = "--restore" ]; then
  if [ -f "$BACKUP" ]; then
    OLD="$(cat "$BACKUP")"
    if [ -z "$OLD" ] || [ "$OLD" = "null" ]; then
      "$ADB" shell settings delete secure "$KEY" >/dev/null 2>&1 || true
    else
      put "$OLD"
    fi
    echo "✔ 已还原：$KEY = $(get)"
  else
    echo "✘ 没有备份文件 $BACKUP" >&2
  fi
  "$ADB" shell settings delete secure resident_music_app_white_list >/dev/null 2>&1 || true
  "$ADB" shell settings delete secure lock_music_app_white_list >/dev/null 2>&1 || true
  "$ADB" shell am force-stop com.vivo.musicwidgetmix >/dev/null 2>&1 || true
  echo "✔ 已清掉派生缓存并重启小部件（回到系统默认名单）"
  exit 0
fi

CUR="$(get)"
[ "$CUR" = "null" ] && CUR=""
[ -f "$BACKUP" ] || printf '%s' "${CUR:-null}" > "$BACKUP"
echo "▶ 当前云控名单：${CUR:-（空，走系统硬编码 61 个）}"

DATA="$HERE/vivo-system-apps.json"
# Git Bash 的 /d/... 路径 Windows 版 python 打不开，转成 D:\...
if command -v cygpath >/dev/null 2>&1; then DATA="$(cygpath -w "$DATA")"; fi
NEW="$(CUR="$CUR" PKG="$PKG" DATA="$DATA" "$PY" - <<'PY'
import json, os
cur = os.environ.get("CUR", "").strip()
pkg = os.environ.get("PKG", "com.guga.music")
data = json.load(open(os.environ["DATA"], encoding="utf-8"))
coop, lock = set(data["cooperate"]), set(data["lock"])

def entry(p, is_new=False):
    return {
        "packageName": p, "appName": p, "colorType": "", "dataStatus": 1,
        "isCooperate": 1 if p in coop or is_new else 0,
        "showLockScreen": 1 if p in lock or is_new else 0,
    }

try:
    items = json.loads(cur) if cur else []
except Exception:
    items = []
if not isinstance(items, list):
    items = []

if items:
    # 云控已有内容：原样保留，只把本应用追加到末尾
    base = items
else:
    # 云控为空：完整重建系统硬编码名单，否则其他音乐 App 会全部失效
    base = [entry(p) for p in data["main"]]

base = [i for i in base if not (isinstance(i, dict) and i.get("packageName") == pkg)]
base.append(entry(pkg, True))
print(json.dumps(base, ensure_ascii=False, separators=(",", ":")))
PY
)"
[ -n "$NEW" ] || { echo "✘ 生成名单失败" >&2; exit 1; }

put "$NEW"
"$ADB" shell am force-stop com.vivo.musicwidgetmix >/dev/null 2>&1 || true
sleep 4
"$ADB" shell am start -n com.vivo.musicwidgetmix/com.vivo.musicwidgetmix.activity.FlipActivity >/dev/null 2>&1 || true
sleep 5

EFFECT="$(chk)"
echo "✔ 小部件生效名单：$EFFECT"
for p in com.tencent.qqmusic com.netease.cloudmusic cn.wenyu.bodian "$PKG"; do
  case "$EFFECT" in *"\"$p\""*) echo "  ✔ $p 仍在名单中" ;; *) echo "  ✘ $p 丢失！请立刻 --restore" ;; esac
done
echo
echo "接下来：用Apollo放一首歌 → 打开原子随身听 → 设置 → 播放诊断看协作状态"
