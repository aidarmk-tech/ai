#!/usr/bin/env bash
set -euo pipefail
cat <<'TXT'
This opens rclone's interactive Google Drive setup.
Use remote name: gdrive
Storage: drive
Root folder ID for the prepared folder: 1FDTuXpdwOU-lKISLu4OYwU1s0LRzv1BP
Set the Root folder ID to the prepared folder ID above. Leave Shared Drive disabled.
A phone-only OAuth flow may require an SSH tunnel or an rclone-capable Android terminal;
when unavailable, use the local compressed exports and upload them manually.
TXT
sudo -u pumpradar -H rclone config
systemctl start pumpradar-backup.service
systemctl status pumpradar-backup.service --no-pager
