#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
# Run with the application stopped; pg_dump alone cannot snapshot receipt files.
: "${BACKUP_QUIESCED:?Stop backend and set BACKUP_QUIESCED=true}"
[[ "$BACKUP_QUIESCED" == true ]] || exit 1
: "${AGE_RECIPIENT:?Set an age public recipient; keep its private key elsewhere}"
[[ "$(stat -f -c %T /work)" == tmpfs ]] || { echo 'Backup work directory must be tmpfs.' >&2; exit 1; }
[[ "${BACKUP_LOCAL_ONLY:-false}" == true ]] || : "${BACKUP_BUCKET:?Set off-site bucket}"
prefix="${BACKUP_PREFIX:-cums}"
[[ "$prefix" =~ ^[a-zA-Z0-9][a-zA-Z0-9/_-]*$ && "$prefix" != */ ]] || { echo 'Use a dedicated safe backup prefix.' >&2; exit 1; }
stage=$(mktemp -d /work/backup.XXXXXXXX)
trap 'rm -rf -- "$stage"' EXIT
stamp=$(date -u +%Y%m%dT%H%M%SZ)
archive="/backups/cums-$stamp.tar.age"
[[ ! -e "$archive" ]] || { echo 'Backup name already exists.' >&2; exit 1; }
pg_dump --format=custom --no-owner --no-acl --file="$stage/database.dump"
printf 'cums-backup-v1\n' > "$stage/FORMAT"
# Receipt snapshots use anonymous UUID keys and validate bytes against the database.
case "${APP_RECEIPT_STORAGE:-local}" in
  local) python3 /opt/cums/receipt_archive.py snapshot_local "$stage/uploads"; receipt_root="$stage" ;;
  s3) python3 /opt/cums/receipt_archive.py snapshot "$stage/uploads"; receipt_root="$stage" ;;
  *) echo "Invalid receipt storage driver." >&2; exit 1 ;;
esac
# Only encrypted bytes reach persistent storage.
tar -C "$stage" -cf - FORMAT database.dump -C "$receipt_root" uploads | age -r "$AGE_RECIPIENT" > "$archive.partial"
mv -- "$archive.partial" "$archive"
if [[ "${BACKUP_LOCAL_ONLY:-false}" != true ]]; then
  aws s3 cp "$archive" "s3://$BACKUP_BUCKET/$prefix/daily/$(basename "$archive")" --only-show-errors
  aws s3 cp "$archive" "s3://$BACKUP_BUCKET/$prefix/monthly/$(date -u +%Y%m).tar.age" --only-show-errors
  python3 /opt/cums/retention.py
fi
printf 'Encrypted database and uploads backup completed: %s\n' "$(basename "$archive")"
# Local encrypted files also stay bounded. Delete only names produced by this script.
python3 - <<'PY'
import pathlib, re
files = sorted(p for p in pathlib.Path('/backups').iterdir()
               if re.fullmatch(r'cums-\d{8}T\d{6}Z\.tar\.age', p.name) and p.is_file())
for old in files[:-30]:
    old.unlink()
PY
