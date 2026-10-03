$ErrorActionPreference = 'Stop'
# Entirely synthetic data in isolated named Docker resources; no application database is read.
$suffix = [guid]::NewGuid().ToString('N').Substring(0,12)
$network = "cums-drill-$suffix"
$database = "cums-drill-db-$suffix"
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
    Invoke-Docker exec $database createdb -U postgres drill_source
    Invoke-Docker exec $database createdb -U postgres drill_restored
    Invoke-Docker exec $database psql -U postgres -d drill_source -v ON_ERROR_STOP=1 -c 'CREATE TABLE drill_ledger (id integer PRIMARY KEY, amount numeric(19,4)); INSERT INTO drill_ledger VALUES(1,1234.5678),(2,-34.5678);' | Out-Null
    Invoke-Docker run --rm -v "${keys}:/keys" -v "${source}:/uploads" cums-backup:local bash -c 'umask 077; age-keygen -o /keys/identity 2>/dev/null; printf "synthetic-receipt\n" > /uploads/receipt' | Out-Null
    $recipient = (& docker run --rm -v "${keys}:/keys:ro" cums-backup:local age-keygen -y /keys/identity).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Synthetic key generation failed.' }
    $common = @('run','--rm','--network',$network,'--tmpfs','/work:mode=700,size=128m',
        '-e',"PGHOST=$database",'-e','PGUSER=postgres','-e','PGPASSWORD=synthetic-drill-only',
        '-e','BACKUP_QUIESCED=true','-v',"${archives}:/backups")
    Invoke-Docker @common -e PGDATABASE=drill_source -e BACKUP_LOCAL_ONLY=true -e "AGE_RECIPIENT=$recipient" -v "${source}:/uploads" cums-backup:local bash /opt/cums/backup.sh
    $archive = (& docker run --rm -v "${archives}:/backups:ro" cums-backup:local bash -c 'find /backups -name "*.tar.age" -type f').Trim()
    if ($LASTEXITCODE -ne 0 -or !$archive.StartsWith('/backups/cums-')) { throw 'Encrypted archive missing.' }
    Invoke-Docker @common -e PGDATABASE=drill_restored -e RESTORE_EMPTY_TARGET=true -v "${target}:/uploads" -v "${keys}:/keys:ro" cums-backup:local bash /opt/cums/restore.sh $archive /keys/identity
    $total = (& docker exec $database psql -U postgres -d drill_restored -Atqc 'SELECT sum(amount) FROM drill_ledger').Trim()
    if ($total -ne '1200.0000') { throw 'Restored ledger total differs.' }
    Invoke-Docker run --rm -v "${target}:/uploads:ro" cums-backup:local bash -c 'test "$(cat /uploads/receipt)" = synthetic-receipt'
    & docker @common -e PGDATABASE=drill_restored -e RESTORE_EMPTY_TARGET=true -v "${target}:/uploads" -v "${keys}:/keys:ro" cums-backup:local bash /opt/cums/restore.sh $archive /keys/identity *> $null
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
    foreach ($volume in @($source,$target,$archives,$keys)) { & docker volume rm $volume *> $null }
    & docker network rm $network *> $null
}
