#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Apply the release version to all four platforms, or check for drift."""
import json,re,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
c=json.loads((root/'release-config.json').read_text());v=c['version']
assert re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)',v)
check='--check' in sys.argv
for platform in ('macos','windows'):
 for name in ('package.json','package-lock.json'):
  p=root/'apps'/platform/name;data=json.loads(p.read_text());data['version']=v
  if name=='package-lock.json':data['packages']['']['version']=v
  if check:assert p.read_text()==json.dumps(data,ensure_ascii=False,indent=2)+'\n',f'Version/format drift: {p.relative_to(root)}'
  else:p.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
for platform,code in [('android',c['androidVersionCode']),('tv',c['tvVersionCode'])]:
 p=root/'apps'/platform/'app/build.gradle.kts';old=p.read_text();new=re.sub(r'versionCode = \d+',f'versionCode = {code}',old);new=re.sub(r'versionName = "[^"]+"',f'versionName = "{v}"',new)
 if check:assert old==new,f'Version drift: {platform}'
 else:p.write_text(new)
print(f'All platform versions: {v}')
