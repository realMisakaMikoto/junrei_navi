#!/bin/sh
set -eu

cd /opt/anitabi-api

active_project_file=/etc/anitabi-api/active-compose-project
if [ ! -f "$active_project_file" ] || [ -L "$active_project_file" ]; then
    echo "Active Compose project file is missing or unsafe" >&2
    exit 1
fi
if [ "$(stat -c '%u' "$active_project_file")" -ne 0 ] || find "$active_project_file" -perm /022 -print -quit | grep -q .; then
    echo "Active Compose project file must be root-owned and not group/world writable" >&2
    exit 1
fi

active_project=$(sed -n '1p' "$active_project_file")
actual_bytes=$(wc -c < "$active_project_file")
expected_bytes=$((${#active_project} + 1))
if [ "$actual_bytes" -ne "$expected_bytes" ]; then
    echo "Active Compose project file must contain exactly one line" >&2
    exit 1
fi
case "$active_project" in
    ''|*[!a-zA-Z0-9_.-]*)
        echo "Active Compose project name is invalid" >&2
        exit 1
        ;;
esac

docker compose -f compose.yaml -f compose.amap.yaml -p "$active_project" exec -T api node dist/admin.js backup
