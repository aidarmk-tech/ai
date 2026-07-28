#!/usr/bin/env bash
set -Eeuo pipefail

DB_PATH="${PUMPRADAR_DB_PATH:-/var/lib/pumpradar/pumpradar.sqlite3}"
INSTALL_URL="https://raw.githubusercontent.com/aidarmk-tech/ai/chatgpt/pumpradar-v439-profit/server-bootstrap/install.sh"

fail() { printf '\n[PumpRadar] ERROR: %s\n' "$*" >&2; exit 1; }

[[ ${EUID:-$(id -u)} -eq 0 ]] || fail "Запустите установщик от root"
command -v sqlite3 >/dev/null 2>&1 || fail "sqlite3 не установлен"
command -v curl >/dev/null 2>&1 || fail "curl не установлен"

open_count=0
if [[ -f "$DB_PATH" ]]; then
  table_count() {
    local table="$1" predicate="$2"
    if [[ "$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$table';")" == "1" ]]; then
      sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM $table WHERE $predicate;"
    else
      printf '0\n'
    fi
  }

  open_count=$((
    $(table_count paper_slots "baseline_status='OPEN'") +
    $(table_count policy_runs "state='OPEN'") +
    $(table_count momentum_slots "primary_status='OPEN'") +
    $(table_count momentum_policy_runs "state='OPEN'")
  ))
fi

if (( open_count > 0 )); then
  fail "Обновление отменено: найдено открытых paper/policy записей: $open_count. Дождитесь закрытия позиции."
fi

printf '[PumpRadar] Открытых paper-позиций нет. Запускается проверенный установщик v4.3.9.\n'
curl -fsSL "$INSTALL_URL" | bash
