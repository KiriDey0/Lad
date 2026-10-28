# SPDX-License-Identifier: AGPL-3.0-only
"""Smoke the packaged sidecar via its inherited UTF-8 pipes, without an account."""
import json, os, queue, subprocess, sys, threading
from pathlib import Path

exe = Path(sys.argv[1]).resolve()
output = Path(sys.argv[2]).resolve()
p = subprocess.Popen([str(exe)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
    env={**os.environ, 'PYTHONUTF8':'1', 'PYTHONIOENCODING':'utf-8',
         'LAD_MUSIC_SOURCE':str(exe.parent.parent/'backend-source'/'vendor')})
messages = queue.Queue()
def read():
    for line in p.stdout:
        try: messages.put(json.loads(line.decode('utf-8')))
        except (ValueError, UnicodeError): messages.put({'invalid':True})
    messages.put({'eof':True})
threading.Thread(target=read,daemon=True).start()
def wait(predicate):
    while True:
        m=messages.get(timeout=45)
        if m.get('invalid') or m.get('eof'): raise RuntimeError('Sidecar closed or invalid UTF-8 JSON')
        if predicate(m):return m
counter=0
def rpc(method,params=None):
    global counter
    counter+=1
    p.stdin.write((json.dumps({'id':counter,'method':method,'params':params or {}},ensure_ascii=False)+'\n').encode('utf-8'))
    p.stdin.flush()
    m=wait(lambda v:v.get('id')==counter)
    if m.get('error'):raise RuntimeError(m['error'])
    return m.get('result')
try:
    wait(lambda m:m.get('event')=='ready')
    rpc('boot', {'credentials':{},'saved':{}})
    rpc('demo')
    rpc('control', {'action':'pause'})
    rpc('control', {'action':'seek','value':73})
    s=rpc('snapshot')
    assert s['playback']['track']['title']=='Первый свет'
    assert s['playback']['position']==73
    assert s['playback']['playing'] is False
    assert len(s['queue']['tracks'])==6
    result={'standaloneBackend':True,'unicodeRpc':True,'queueTracks':6,'seekSeconds':73,'accountUsed':False}
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False))
finally:
    p.stdin.close()
    try:p.wait(timeout=10)
    except subprocess.TimeoutExpired:p.kill();p.wait();raise
