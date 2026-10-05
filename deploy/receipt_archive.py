"""Private receipt snapshots. Paths and diagnostics contain no member information."""
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import uuid

LIMIT = 10_485_760

def config():
    bucket = os.environ.get("APP_RECEIPT_S3_BUCKET", "")
    prefix = os.environ.get("APP_RECEIPT_S3_PREFIX", "receipts")
    if not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", bucket) or not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9/_-]*", prefix) or prefix.endswith("/"):
        raise ValueError("Invalid private receipt storage configuration.")
    return bucket, prefix + "/"

def run(args):
    env = dict(os.environ)
    env["AWS_DEFAULT_REGION"] = env.get("APP_RECEIPT_S3_REGION", "us-east-1")
    endpoint = env.get("APP_RECEIPT_S3_ENDPOINT", "")
    # Receipt and off-site backup endpoints may differ.
    env.pop("AWS_ENDPOINT_URL", None)
    env.pop("AWS_ENDPOINT_URL_S3", None)
    if endpoint:
        env["AWS_ENDPOINT_URL_S3"] = endpoint
    result = subprocess.run(args, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if result.returncode:
        raise RuntimeError("Private receipt snapshot operation failed.")
    return result.stdout

def references():
    raw = run(["psql", "-At", "-v", "ON_ERROR_STOP=1", "-c", "SELECT storage_key::text||'|'||sha256||'|'||byte_size FROM attachments ORDER BY storage_key"])
    rows = []
    for line in raw.decode("ascii").splitlines():
        key, digest, size = line.split("|")
        if str(uuid.UUID(key)) != key or not re.fullmatch(r"[a-f0-9]{64}", digest) or not 0 <= int(size) <= LIMIT:
            raise ValueError("Invalid receipt metadata.")
        rows.append((key, digest, int(size)))
    return rows

def verify(path, digest, size):
    if not path.is_file() or path.is_symlink() or path.stat().st_size != size or size > LIMIT:
        raise ValueError("Receipt snapshot integrity check failed.")
    if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
        raise ValueError("Receipt snapshot integrity check failed.")

def empty():
    bucket, prefix = config()
    result = json.loads(run(["aws", "s3api", "list-objects-v2", "--bucket", bucket, "--prefix", prefix, "--max-keys", "1"]))
    if result.get("Contents"):
        raise ValueError("Refusing to restore over existing private receipts.")

def snapshot(directory):
    bucket, prefix = config()
    directory.mkdir()
    for key, digest, size in references():
        path = directory / key
        run(["aws", "s3api", "get-object", "--bucket", bucket, "--key", prefix + key, str(path)])
        verify(path, digest, size)

def restore(directory):
    bucket, prefix = config()
    empty()
    # Validate the complete restored database inventory before uploading any bytes.
    rows = references()
    expected = {key for key, _, _ in rows}
    if {path.name for path in directory.iterdir()} != expected:
        raise ValueError("Receipt snapshot inventory differs from restored database.")
    for key, digest, size in rows:
        verify(directory / key, digest, size)
    encryption = os.environ.get("APP_RECEIPT_S3_ENCRYPTION", "AES256")
    if encryption not in ("AES256", "NONE"):
        raise ValueError("Invalid private receipt encryption configuration.")
    for key, _, _ in rows:
        args = ["aws", "s3api", "put-object", "--bucket", bucket, "--key", prefix + key, "--if-none-match", "*", "--body", str(directory / key)]
        if encryption == "AES256":
            args += ["--server-side-encryption", "AES256"]
        run(args)

if __name__ == "__main__":
    try:
        if len(sys.argv) == 2 and sys.argv[1] == "empty":
            empty()
        elif len(sys.argv) == 3 and sys.argv[1] in ("snapshot", "restore"):
            globals()[sys.argv[1]](pathlib.Path(sys.argv[2]))
        else:
            raise ValueError("Invalid receipt snapshot command.")
    except Exception:
        # Never reflect subprocess diagnostics, credentials, keys or private paths.
        print("Private receipt backup/restore failed. Keep the application stopped and inspect the isolated target locally.", file=sys.stderr)
        sys.exit(1)
