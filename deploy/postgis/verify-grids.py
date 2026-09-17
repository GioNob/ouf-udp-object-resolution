#!/usr/bin/env python3
"""Verify a read-only PROJ grid inventory before PostgreSQL starts. Prints only its SHA256."""
import hashlib
import json
import os
from pathlib import Path
import re
import sys


def verify(root):
    root = Path(root)
    manifest = root / 'manifest.json'
    if manifest.is_symlink() or manifest.stat().st_size > 65536:
        raise ValueError('invalid manifest')
    content = manifest.read_bytes()
    document = json.loads(content)
    if document.get('schemaVersion') != 1 or not isinstance(document.get('grids'), dict):
        raise ValueError('invalid inventory')
    if len(document['grids']) > 100:
        raise ValueError('too many grids')
    metadata = document.get('metadata', {})
    for name, expected in document['grids'].items():
        if not re.fullmatch(r'[A-Za-z0-9_-]+\.(tif|gsb|gtx)', name) or not isinstance(expected, str) or not re.fullmatch(r'[a-f0-9]{64}', expected):
            raise ValueError('invalid grid reference')
        item = metadata.get(name, {})
        for key in ('version', 'licenseRef', 'provenanceRef', 'areaOfUse'):
            if not isinstance(item.get(key), str) or not item[key].strip():
                raise ValueError('grid version, licence, provenance and territory required')
        file = root / name
        if file.is_symlink() or not file.is_file():
            raise ValueError('missing grid')
        with file.open('rb') as stream:
            actual = hashlib.file_digest(stream, 'sha256').hexdigest()
        if actual != expected:
            raise ValueError('grid checksum mismatch')
    return 'sha256:' + hashlib.sha256(content).hexdigest()


if __name__ == '__main__':
    try:
        if os.environ.get('PROJ_NETWORK') != 'OFF':
            raise ValueError('PROJ_NETWORK must be OFF')
        print(verify('/opt/ouf/proj'))
    except (OSError, ValueError, TypeError, KeyError):
        sys.exit('OUF_PROJ_RESOURCE_VALIDATION_FAILED')
