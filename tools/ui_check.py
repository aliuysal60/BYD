"""Manual emulator UI helper, restricted to the test emulator."""
import subprocess
import sys
import re
from pathlib import Path
import xml.etree.ElementTree as ET

ADB = ['adb', '-s', 'emulator-5554']
def adb(*args):
    return subprocess.check_output(ADB + list(args))
def dump():
    adb('shell', 'uiautomator dump /sdcard/inspector-ui.xml')
    raw=adb('shell', 'cat /sdcard/inspector-ui.xml')
    Path('build/ui.xml').write_bytes(raw)
    return ET.fromstring(raw)
action=sys.argv[1]
if action=='dump':
    for node in dump().iter('node'):
        if node.get('text') or node.get('content-desc'):
            print(node.get('text') or node.get('content-desc'), node.get('bounds'))
elif action=='tap':
    target=sys.argv[2]
    for node in dump().iter('node'):
        if node.get('text')==target or node.get('content-desc')==target:
            x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
            adb('shell',f'input tap {(x1+x2)//2} {(y1+y2)//2}')
            print('Tapped',target)
            break
    else:
        raise SystemExit('UI text not found: '+target)
elif action=='screen':
    Path(sys.argv[2]).write_bytes(adb('exec-out','screencap','-p'))
