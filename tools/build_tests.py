import sys
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build

out = build.ROOT / 'build/tests'
for directory in ('classes', 'dex'):
    (out / directory).mkdir(parents=True, exist_ok=True)
build.run([build.tool('aapt2'), 'link', '-o', out / 'resources.apk', '--manifest', 'tests/AndroidManifest.xml', '-I', build.ANDROID])
build.run([build.java_tool('javac'), '-source', '8', '-target', '8', '-encoding', 'UTF-8', '-classpath', str(build.ANDROID) + build.os.pathsep + str(build.ROOT / 'build/portable/all-classes.jar'), '-d', out / 'classes', 'tests/IntegrationTests.java'])
with ZipFile(out / 'classes.jar', 'w', ZIP_DEFLATED) as z:
    for p in (out / 'classes').rglob('*.class'):
        z.write(p, p.relative_to(out / 'classes').as_posix())
build.run([build.tool('d8'), '--lib', build.ANDROID, '--classpath', build.ROOT / 'build/portable/all-classes.jar', '--min-api', '23', '--output', out / 'dex', out / 'classes.jar'])
with ZipFile(out / 'unsigned.apk', 'w', ZIP_DEFLATED) as z, ZipFile(out / 'resources.apk') as resources:
    for info in resources.infolist():
        z.writestr(info, resources.read(info.filename))
    z.write(out / 'dex/classes.dex', 'classes.dex')
build.run([build.tool('zipalign'), '-f', '4', out / 'unsigned.apk', out / 'aligned.apk'])
build.run([build.tool('apksigner'), 'sign', '--ks', 'inspector-local.p12', '--ks-pass', 'pass:inspector-local', '--ks-key-alias', 'inspector', '--out', out / 'tests.apk', out / 'aligned.apk'])
print(out / 'tests.apk')
