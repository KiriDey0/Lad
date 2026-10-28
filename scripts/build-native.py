#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Build a native artifact from a clean tree so generated version constants cannot drift."""
import argparse,json,os,shutil,subprocess,sys
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('platform',choices=['android','tv'])
parser.add_argument('--debug',action='store_true',help='Build the separate QA package instead of a signed distribution APK')
a=parser.parse_args();root=Path(__file__).resolve().parents[1]
if not a.debug:
 required=['LAD_ANDROID_KEYSTORE','LAD_ANDROID_STORE_PASSWORD','LAD_ANDROID_KEY_ALIAS','LAD_ANDROID_KEY_PASSWORD']
 if any(not os.environ.get(n) for n in required):parser.error('Provide the distribution signing environment; never store it in the repository')
subprocess.run([sys.executable,str(root/'scripts/sync-version.py'),'--check'],check=True)
java=Path(os.environ['JAVA_HOME'])/'bin'/('java.exe' if os.name=='nt' else 'java') if os.environ.get('JAVA_HOME') else shutil.which('java')
if not java:parser.error('JDK 17 is required; set JAVA_HOME')
build='Debug' if a.debug else 'Release';app=root/'apps'/a.platform
subprocess.run([str(java),'-cp','gradle/wrapper/gradle-wrapper.jar','org.gradle.wrapper.GradleWrapperMain',':app:clean',':app:testDebugUnitTest',f':app:assemble{build}',f':app:lint{build}'],cwd=app,check=True)
metadata=json.loads((app/f'app/build/outputs/apk/{build.lower()}/output-metadata.json').read_text());version=json.loads((root/'release-config.json').read_text())
assert all(e['versionName']==version['version'] and e['versionCode']==version[a.platform+'VersionCode'] for e in metadata['elements'])
print('Clean native build and packaged version metadata verified')
