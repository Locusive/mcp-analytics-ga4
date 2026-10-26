#!/usr/bin/env python3
"""Build and validate a signed Maven Central bundle without uploading or printing secrets."""
from pathlib import Path
import hashlib
import os
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
environment = os.environ.copy()
key_file = root / 'secrets' / 'signing-key.asc'
if 'MAVEN_SIGNING_KEY' not in environment and key_file.exists():
    environment['MAVEN_SIGNING_KEY'] = key_file.read_text()
if not environment.get('MAVEN_SIGNING_KEY'):
    raise SystemExit('Set MAVEN_SIGNING_KEY or create ignored secrets/signing-key.asc before preparing a release.')
environment.setdefault('MAVEN_SIGNING_PASSWORD', '')
subprocess.run([str(root / 'gradlew'), 'clean', 'check', ':library:publishMavenJavaPublicationToCentralBundleRepository'],
               cwd=root, env=environment, check=True)
repository = root / 'build' / 'central-repository'
artifacts = list(repository.glob('**/mcp-analytics-ga4-*.pom'))
if len(artifacts) != 1:
    raise SystemExit('Expected exactly one versioned publication POM.')
version_dir = artifacts[0].parent
release_files = [artifact for artifact in version_dir.iterdir()
                 if artifact.name.endswith(('.jar', '.pom', '.module'))]
for artifact in release_files:
    if artifact.name.endswith(('.jar', '.pom', '.module')):
        if not artifact.with_name(artifact.name + '.asc').exists():
            raise SystemExit('A release artifact is missing its signature.')
for artifact in release_files + [path.with_name(path.name + '.asc') for path in release_files]:
    for algorithm in ['md5', 'sha1', 'sha256', 'sha512']:
        digest = hashlib.new(algorithm, artifact.read_bytes()).hexdigest()
        artifact.with_name(artifact.name + '.' + algorithm).write_text(digest)
output = root / 'build' / 'mcp-analytics-ga4-central-bundle.zip'
with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
    for artifact in sorted(version_dir.iterdir()):
        archive.write(artifact, artifact.relative_to(repository))
print('Signed release bundle:', output)
print('Bundle SHA-256:', hashlib.sha256(output.read_bytes()).hexdigest())
print('Upload requires an authenticated verified namespace; this script does not publish.')
