#!/bin/sh
# SPDX-License-Identifier: AGPL-3.0-only
set -eu
pid="$1"; target="$2"; archive="$3"; version="$4"; ack="$5"; journal="$6"
stage=""; moved=0; installed=0; newpid=""
fail() {
  trap - EXIT INT TERM
  if [ "$moved" -eq 1 ]; then
    if [ -n "$newpid" ]; then kill "$newpid" 2>/dev/null || true; sleep 2; fi
    if [ "$installed" -eq 1 ]; then /bin/mv "$target" "$stage/failed.app" 2>/dev/null || true; fi
    /bin/mv "$stage/previous.app" "$target" 2>/dev/null || true
    "$target/Contents/MacOS/Лад" >/dev/null 2>&1 &
  fi
  printf '{"ok":false,"message":"Не удалось установить обновление. Предыдущая версия сохранена."}' > "$journal"
  exit 1
}
trap fail EXIT INT TERM
n=0; while kill -0 "$pid" 2>/dev/null; do n=$((n+1)); [ "$n" -lt 90 ] || exit 1; sleep 1; done
stage=$(/usr/bin/mktemp -d "$(/usr/bin/dirname "$target")/.lad-update.XXXXXX")
/usr/bin/ditto -x -k "$archive" "$stage/new"
new="$stage/new/Лад.app"
[ -d "$new" ]
[ "$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$new/Contents/Info.plist")" = 'com.lad.music' ]
[ "$(/usr/libexec/PlistBuddy -c 'Print CFBundleShortVersionString' "$new/Contents/Info.plist")" = "$version" ]
/usr/bin/codesign --verify --deep --strict "$new"
/bin/mv "$target" "$stage/previous.app"; moved=1
/bin/mv "$new" "$target"; installed=1
LAD_UPDATE_ACK="$ack" "$target/Contents/MacOS/Лад" >/dev/null 2>&1 &
newpid=$!
n=0; while [ ! -f "$ack" ]; do n=$((n+1)); [ "$n" -lt 90 ] || exit 1; kill -0 "$newpid" 2>/dev/null || exit 1; sleep 1; done
printf '{"ok":true}' > "$journal"
trap - EXIT INT TERM
/bin/rm -rf "$stage"
