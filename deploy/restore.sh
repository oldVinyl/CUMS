#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
: "${RESTORE_EMPTY_TARGET:?Set RESTORE_EMPTY_TARGET=true for an isolated empty target}"
[[ "$RESTORE_EMPTY_TARGET" == true ]] || exit 1
: "${BACKUP_QUIESCED:?Stop backend and set BACKUP_QUIESCED=true}"
[[ "$BACKUP_QUIESCED" == true ]] || exit 1
[[ "$(stat -f -c %T /work)" == tmpfs ]] || { echo 'Restore work directory must be tmpfs.' >&2; exit 1; }
[[ $# == 2 && -f "$1" && -f "$2" ]] || { echo 'Usage: restore.sh encrypted-archive age-identity-file' >&2; exit 1; }
[[ "$(psql -Atqc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")" == 0 ]] || { echo 'Refusing to restore over a populated database.' >&2; exit 1; }
[[ -z "$(find /uploads -mindepth 1 -print -quit)" ]] || { echo 'Refusing to restore over existing uploads.' >&2; exit 1; }
stage=$(mktemp -d /work/restore.XXXXXXXX)
trap 'rm -rf -- "$stage"' EXIT
age -d -i "$2" "$1" > "$stage/archive.tar"
# Validate every path and file type before extraction (no traversal, links or devices).
python3 - "$stage/archive.tar" <<'PY'
import pathlib, sys, tarfile
with tarfile.open(sys.argv[1]) as archive:
    for member in archive.getmembers():
        path = pathlib.PurePosixPath(member.name)
        if (path.is_absolute() or '..' in path.parts or not (member.isfile() or member.isdir())
                or not path.parts or path.parts[0] not in ('FORMAT', 'database.dump', 'uploads')
                or (path.parts[0] != 'uploads' and len(path.parts) != 1)):
            raise SystemExit('Invalid archive member')
PY
tar -xf "$stage/archive.tar" -C "$stage"
[[ "$(cat "$stage/FORMAT")" == cums-backup-v1 ]] || { echo 'Unsupported backup format.' >&2; exit 1; }
pg_restore --exit-on-error --single-transaction --no-owner --no-acl --dbname="$PGDATABASE" "$stage/database.dump"
# The original application's keys must be provisioned separately before starting it.
[[ -d "$stage/uploads" ]] || { echo 'Uploads directory missing.' >&2; exit 1; }
cp -a "$stage/uploads/." /uploads/
chown -R 10001:10001 /uploads
printf 'Database and private uploads restored. Verify keys, health and reconciliation before startup.\n'
