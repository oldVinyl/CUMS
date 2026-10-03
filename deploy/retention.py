"""Delete only this application's generated archive keys, never unrelated objects."""
import json
import os
import re
import subprocess


def expired(keys, prefix, folder, count):
    pattern = (r'cums-\d{8}T\d{6}Z\.tar\.age' if folder == 'daily'
               else r'\d{6}\.tar\.age')
    base = f'{prefix}/{folder}/'
    valid = sorted(key for key in keys if key.startswith(base)
                   and re.fullmatch(pattern, key[len(base):]))
    return valid[:-count]


def main():
    bucket = os.environ['BACKUP_BUCKET']
    prefix = os.environ.get('BACKUP_PREFIX', 'cums').strip('/')
    if not prefix or '..' in prefix.split('/'):
        raise ValueError('A dedicated backup prefix is required')
    result = subprocess.run(['aws', 's3api', 'list-objects-v2', '--bucket', bucket,
                             '--prefix', prefix + '/', '--output', 'json'],
                            check=True, capture_output=True, text=True)
    keys = [item['Key'] for item in json.loads(result.stdout).get('Contents', [])]
    for folder, count in [('daily', 30), ('monthly', 12)]:
        for key in expired(keys, prefix, folder, count):
            subprocess.run(['aws', 's3api', 'delete-object', '--bucket', bucket,
                            '--key', key], check=True, stdout=subprocess.DEVNULL)


if __name__ == '__main__':
    main()
