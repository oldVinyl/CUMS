param([switch]$S3)
$ErrorActionPreference = 'Stop'
# Entirely synthetic data in isolated named Docker resources; no application database is read.
$suffix = [guid]::NewGuid().ToString('N').Substring(0,12)
$network = "cums-drill-$suffix"
$database = "cums-drill-db-$suffix"
$objectServer = "cums-drill-s3-$suffix"
$receiptKey = "11111111-1111-1111-1111-111111111111"
$source = "cums-drill-source-$suffix"
$target = "cums-drill-target-$suffix"
$archives = "cums-drill-archives-$suffix"
$keys = "cums-drill-keys-$suffix"
function Invoke-Docker {
    & docker @args
    if ($LASTEXITCODE -ne 0) { throw 'Disposable restore drill command failed.' }
}
try {
    Invoke-Docker network create $network | Out-Null
    foreach ($volume in @($source,$target,$archives,$keys)) { Invoke-Docker volume create $volume | Out-Null }
    Invoke-Docker run -d --name $database --network $network -e POSTGRES_PASSWORD=synthetic-drill-only postgres:16-alpine | Out-Null
    $ready = $false
    for ($attempt=0; $attempt -lt 30; $attempt++) {
        & docker exec $database pg_isready -U postgres *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (!$ready) { throw 'Disposable database did not start.' }
    if ($S3) {
        Invoke-Docker run -d --name $objectServer --network $network cums-storage-test:local | Out-Null
        $s3Options = @('-e','APP_RECEIPT_STORAGE=s3','-e',"APP_RECEIPT_S3_ENDPOINT=http://${objectServer}:9000",'-e',"AWS_ENDPOINT_URL=http://${objectServer}:9000",'-e','AWS_ACCESS_KEY_ID=synthetic-root','-e','AWS_SECRET_ACCESS_KEY=synthetic-password-only','-e','AWS_DEFAULT_REGION=us-east-1','-e','APP_RECEIPT_S3_ENCRYPTION=NONE')
        $objectReady=$false
        for ($attempt=0; $attempt -lt 30; $attempt++) {
            & docker run --rm --network $network @s3Options cums-backup:local aws s3api create-bucket --bucket synthetic-source *> $null
            if ($LASTEXITCODE -eq 0) {$objectReady=$true;break}
            Start-Sleep -Seconds 1
        }
        if (!$objectReady) {throw 'Disposable object storage did not start.'}
        Invoke-Docker run --rm --network $network @s3Options cums-backup:local aws s3api create-bucket --bucket synthetic-restored | Out-Null
    } else {$s3Options=@()}
    Invoke-Docker exec $database createdb -U postgres drill_source
    Invoke-Docker exec $database createdb -U postgres drill_restored
    Invoke-Docker exec $database psql -U postgres -d drill_source -v ON_ERROR_STOP=1 -c 'CREATE TABLE drill_ledger (id integer PRIMARY KEY, amount numeric(19,4)); INSERT INTO drill_ledger VALUES(1,1234.5678),(2,-34.5678);' | Out-Null
    Invoke-Docker run --rm -v "${keys}:/keys" -v "${source}:/uploads" cums-backup:local bash -c 'umask 077; age-keygen -o /keys/identity 2>/dev/null; printf "synthetic-receipt\n" > /uploads/11111111-1111-1111-1111-111111111111' | Out-Null
    if ($S3) {
        $digest = (& docker run --rm -v "${source}:/uploads:ro" cums-backup:local bash -c 'sha256sum /uploads/11111111-1111-1111-1111-111111111111').Split(' ')[0]
        Invoke-Docker exec $database psql -U postgres -d drill_source -v ON_ERROR_STOP=1 -c "CREATE TABLE attachments(storage_key uuid,sha256 text,byte_size integer); INSERT INTO attachments VALUES('$receiptKey','$digest',18);" | Out-Null
        Invoke-Docker run --rm --network $network @s3Options -v "${source}:/uploads:ro" cums-backup:local aws s3api put-object --bucket synthetic-source --key "receipts/$receiptKey" --body "/uploads/$receiptKey" | Out-Null
    }
    $recipient = (& docker run --rm -v "${keys}:/keys:ro" cums-backup:local age-keygen -y /keys/identity).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Synthetic key generation failed.' }
    $common = @('run','--rm','--network',$network,'--tmpfs','/work:mode=700,size=128m',
        '-e',"PGHOST=$database",'-e','PGUSER=postgres','-e','PGPASSWORD=synthetic-drill-only',
        '-e','BACKUP_QUIESCED=true','-v',"${archives}:/backups") + $s3Options
    Invoke-Docker @common -e APP_RECEIPT_S3_BUCKET=synthetic-source -e PGDATABASE=drill_source -e BACKUP_LOCAL_ONLY=true -e "AGE_RECIPIENT=$recipient" -v "${source}:/uploads" cums-backup:local bash /opt/cums/backup.sh
    $archive = (& docker run --rm -v "${archives}:/backups:ro" cums-backup:local bash -c 'find /backups -name "*.tar.age" -type f').Trim()
    if ($LASTEXITCODE -ne 0 -or !$archive.StartsWith('/backups/cums-')) { throw 'Encrypted archive missing.' }
    Invoke-Docker @common -e APP_RECEIPT_S3_BUCKET=synthetic-restored -e PGDATABASE=drill_restored -e RESTORE_EMPTY_TARGET=true -v "${target}:/uploads" -v "${keys}:/keys:ro" cums-backup:local bash /opt/cums/restore.sh $archive /keys/identity
    $total = (& docker exec $database psql -U postgres -d drill_restored -Atqc 'SELECT sum(amount) FROM drill_ledger').Trim()
    if ($total -ne '1200.0000') { throw 'Restored ledger total differs.' }
    if ($S3) {
        Invoke-Docker run --rm --network $network @s3Options -v "${target}:/uploads" cums-backup:local aws s3api get-object --bucket synthetic-restored --key "receipts/$receiptKey" "/uploads/$receiptKey" | Out-Null
        Invoke-Docker exec $database createdb -U postgres drill_occupied_bucket
        & docker @common -e APP_RECEIPT_S3_BUCKET=synthetic-restored -e PGDATABASE=drill_occupied_bucket -e RESTORE_EMPTY_TARGET=true -v "${keys}:/keys:ro" cums-backup:local bash /opt/cums/restore.sh $archive /keys/identity *> $null
        if ($LASTEXITCODE -eq 0) {throw 'S3 restore failed to reject occupied prefix.'}
    }
    Invoke-Docker run --rm -v "${target}:/uploads:ro" cums-backup:local bash -c 'test "$(cat /uploads/11111111-1111-1111-1111-111111111111)" = synthetic-receipt'
    & docker @common -e APP_RECEIPT_S3_BUCKET=synthetic-restored -e PGDATABASE=drill_restored -e RESTORE_EMPTY_TARGET=true -v "${target}:/uploads" -v "${keys}:/keys:ro" cums-backup:local bash /opt/cums/restore.sh $archive /keys/identity *> $null
    if ($LASTEXITCODE -eq 0) { throw 'Restore failed to reject populated target.' }
    # A wrong private key must reject decryption before any database restore.
    Invoke-Docker run --rm -v "${keys}:/keys" cums-backup:local bash -c 'age-keygen -o /keys/wrong-identity 2>/dev/null'
    Invoke-Docker exec $database createdb -U postgres drill_wrong_key
    & docker @common -e PGDATABASE=drill_wrong_key -e RESTORE_EMPTY_TARGET=true -v "${source}:/uploads" -v "${keys}:/keys:ro" cums-backup:local age -d -i /keys/wrong-identity $archive *> $null
    if ($LASTEXITCODE -eq 0) { throw 'Archive decrypted with a wrong identity.' }
    Write-Output 'PASS: encrypted database + receipt restore, exact financial total, populated-target protection and wrong-key rejection.'
} finally {
    # Delete only the exact disposable resources created above.
    & docker rm -f $database *> $null
    if ($S3) { & docker rm -f $objectServer *> $null }
    foreach ($volume in @($source,$target,$archives,$keys)) { & docker volume rm $volume *> $null }
    & docker network rm $network *> $null
}
