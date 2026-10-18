"""Portable, offline Android SDK build. Pinned dependencies live in vendor/."""
import os
import shutil
import subprocess
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parent
os.chdir(ROOT)
SDK = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'AppData/Local/Android/Sdk'))))
BT = SDK / 'build-tools/35.0.0'
ANDROID = SDK / 'platforms/android-35/android.jar'
WIN = os.name == 'nt'
VERSION = '4.15.2'

def run(args):
    print('> ' + ' '.join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), check=True)

def tool(name):
    return BT / (name + ('.bat' if name in ('d8', 'apksigner') else '.exe') if WIN else name)

def java_tool(name):
    java = Path(os.environ['JAVA_HOME']) / 'bin' / (name + ('.exe' if WIN else '')) if os.environ.get('JAVA_HOME') else shutil.which(name)
    if not java:
        raise SystemExit(f'{name} bulunamadı; JDK kurun veya JAVA_HOME ayarlayın.')
    return java

ASSET_BIN = ROOT / 'app/src/main/assets/bin'

def build_keeper(build):
    """tools/native/PowerKeeper.java → app/src/main/assets/bin/keeper.jar

    app_process64 dex ister; android.jar classpath'iyle javac → d8 → classes.dex
    içeren jar. Böylece PowerKeeper.java'daki her değişiklik (örn. KEEPER_VER
    artışı) keeper.jar'a ve dolayısıyla cihaza otomatik yansır.
    """
    src = ROOT / 'tools/native/PowerKeeper.java'
    if not src.is_file():
        raise SystemExit(f'keeper kaynağı yok: {src}')
    work = build / 'keeper'
    (work / 'classes').mkdir(parents=True, exist_ok=True)
    (work / 'dex').mkdir(parents=True, exist_ok=True)
    run([java_tool('javac'), '-source', '8', '-target', '8', '-encoding', 'UTF-8',
         '-classpath', str(ANDROID), '-d', work / 'classes', src])
    classes_jar = work / 'keeper-classes.jar'
    with ZipFile(classes_jar, 'w', ZIP_DEFLATED) as jar:
        for p in (work / 'classes').rglob('*.class'):
            jar.write(p, p.relative_to(work / 'classes').as_posix())
    run([tool('d8'), '--lib', ANDROID, '--min-api', '23',
         '--output', work / 'dex', classes_jar])
    dex = work / 'dex' / 'classes.dex'
    if not dex.is_file():
        raise SystemExit('keeper: classes.dex üretilemedi')
    ASSET_BIN.mkdir(parents=True, exist_ok=True)
    out = ASSET_BIN / 'keeper.jar'
    with ZipFile(out, 'w', ZIP_DEFLATED) as jar:
        jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\n\r\n')
        jar.write(dex, 'classes.dex')
    print(f'keeper.jar güncellendi: {out} ({out.stat().st_size} bayt)')

def build_qcam():
    """tools/native/qcam_loop8.c → app/src/main/assets/bin/qcam_loop8 (aarch64 PIE)

    Mevcut binary NDK r25 ile derlenmiş; aynı NDK'nın aarch64 clang'ı kullanılır.
    NDK yoksa derleme atlanır (mevcut binary korunur) — qcam kaynağı sık değişmez.
    qcam_patched_c0..c3 bu adımın DIŞINDA: onlar araç sisteminden gelen
    qcarcam_test binary'sinin yamalı kopyaları (patch_dumpname.py), kaynaktan
    derlenemez.
    """
    src = ROOT / 'tools/native/qcam_loop8.c'
    if not src.is_file():
        print('qcam kaynağı yok — derleme atlanıyor (mevcut binary korunur).')
        return
    ndk_root = SDK / 'ndk'
    if not ndk_root.is_dir():
        print('NDK bulunamadı — qcam_loop8 derlemesi atlanıyor (mevcut binary korunur).')
        return
    # En yüksek sürümlü NDK'yı seç.
    versions = sorted((d for d in ndk_root.iterdir() if d.is_dir()),
                      key=lambda p: [int(x) for x in p.name.split('.') if x.isdigit()] or [0])
    if not versions:
        print('NDK dizini boş — qcam_loop8 derlemesi atlanıyor.')
        return
    ndk = versions[-1]
    host = 'windows-x86_64' if WIN else ('darwin-x86_64' if os.uname().sysname == 'Darwin' else 'linux-x86_64')
    cc = ndk / 'toolchains/llvm/prebuilt' / host / 'bin' / ('aarch64-linux-android24-clang' + ('.cmd' if WIN else ''))
    if not cc.is_file():
        print(f'NDK clang bulunamadı ({cc}) — qcam derlemesi atlanıyor.')
        return
    ASSET_BIN.mkdir(parents=True, exist_ok=True)
    out = ASSET_BIN / 'qcam_loop8'
    # Android Bionic libc pthread'i içerir — ayrı -lpthread YOK (ld bulamaz).
    run([cc, '-O2', '-fPIE', '-pie', str(src), '-o', str(out)])
    print(f'qcam_loop8 güncellendi: {out} ({out.stat().st_size} bayt)')

def main():
    if not ANDROID.exists():
        raise SystemExit('Android SDK platform 35 bulunamadı. ANDROID_HOME ayarlayın.')
    lock = json.loads((ROOT / 'vendor/dependencies.lock.json').read_text())
    for dep in lock:
        file = ROOT / 'vendor' / dep['file']
        if not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest() != dep['sha256']:
            raise SystemExit(f'Bağımlılık eksik veya SHA-256 uyuşmuyor: {file}')
    run([shutil.which('python') or 'python3', 'tools/prepare_vendor.py'])
    build = ROOT / 'build/portable'
    if build.exists():
        resolved = build.resolve()
        if resolved.parent != (ROOT / 'build').resolve():
            raise SystemExit('Unexpected build path')
        shutil.rmtree(resolved)
    for part in ('classes', 'generated', 'dex', 'deps'):
        (build / part).mkdir(parents=True, exist_ok=True)
    (ROOT / 'dist').mkdir(exist_ok=True)
    # Yerel yardımcıları asset'e üret (APK paketlemeden ÖNCE):
    # keeper.jar her zaman kaynaktan derlenir; qcam_loop8 NDK varsa derlenir.
    build_keeper(build)
    build_qcam()
    dependencies = list((ROOT / 'vendor').glob('*.jar'))
    natives = {}
    for name in ('conscrypt-android-2.5.3.aar', 'spake2-android-2.2.1.aar'):
        with ZipFile(ROOT / 'vendor' / name) as z:
            jar = build / 'deps' / (name + '.jar')
            jar.write_bytes(z.read('classes.jar'))
            dependencies.append(jar)
            for member in z.namelist():
                if member.startswith('jni/') and member.endswith('.so'):
                    natives['lib/' + member[4:]] = z.read(member)
    run([tool('aapt2'), 'compile', '--dir', 'app/src/main/res', '-o', build / 'resources.zip'])
    run([tool('aapt2'), 'link', '-o', build / 'resources.apk', '--manifest', 'app/src/main/AndroidManifest.xml', '-I', ANDROID, '--java', build / 'generated', '--min-sdk-version', '23', '--target-sdk-version', '32', build / 'resources.zip'])
    sources = [*Path('app/src/main/java').rglob('*.java'), *Path('vendor/libadb-src').rglob('*.java'), *(build / 'generated').rglob('*.java')]
    (build / 'sources.txt').write_text('\n'.join('"' + p.as_posix() + '"' for p in sources), encoding='utf-8')
    classpath = os.pathsep.join(map(str, [ANDROID, *dependencies]))
    run([java_tool('javac'), '-source', '8', '-target', '8', '-encoding', 'UTF-8', '-classpath', classpath, '-d', build / 'classes', '@' + str(build / 'sources.txt')])
    with ZipFile(build / 'all-classes.jar', 'w', ZIP_DEFLATED) as jar:
        seen = set()
        for p in (build / 'classes').rglob('*.class'):
            name = p.relative_to(build / 'classes').as_posix()
            jar.write(p, name); seen.add(name)
        for dependency in dependencies:
            if dependency.name.startswith('annotation-'):
                continue
            with ZipFile(dependency) as z:
                for name in z.namelist():
                    if name.endswith('.class') and not name.startswith('META-INF/') and name != 'module-info.class' and name not in seen:
                        jar.writestr(name, z.read(name)); seen.add(name)
    run([tool('d8'), '--lib', ANDROID, '--classpath', ROOT / 'vendor/annotation-1.3.0.jar', '--min-api', '23', '--output', build / 'dex', build / 'all-classes.jar'])
    with ZipFile(build / 'unsigned.apk', 'w', ZIP_DEFLATED) as apk, ZipFile(build / 'resources.apk') as resource:
        for info in resource.infolist():
            apk.writestr(info, resource.read(info.filename))
        for p in (build / 'dex').glob('*.dex'):
            apk.write(p, p.name)
        for name, content in natives.items():
            apk.writestr(name, content)
        for p in (ROOT / 'vendor/licenses').rglob('*'):
            if p.is_file():
                apk.write(p, 'assets/licenses/' + p.relative_to(ROOT / 'vendor/licenses').as_posix())
        apk.write('THIRD_PARTY_NOTICES.md', 'assets/THIRD_PARTY_NOTICES.md')
        for p in (ROOT / 'app/src/main/assets').rglob('*'):
            if p.is_file():
                apk.write(p, 'assets/' + p.relative_to(ROOT / 'app/src/main/assets').as_posix())
    run([tool('zipalign'), '-f', '-p', '4', build / 'unsigned.apk', build / 'aligned.apk'])
    key = ROOT / 'inspector-local.p12'
    if not key.is_file():
        raise SystemExit('Mevcut imza anahtarı gerekli: inspector-local.p12')
    output = ROOT / 'dist' / f'DiLink-Inspector-{VERSION}.apk'
    run([tool('apksigner'), 'sign', '--ks', key, '--ks-pass', 'pass:inspector-local', '--key-pass', 'pass:inspector-local', '--ks-key-alias', 'inspector', '--out', output, build / 'aligned.apk'])
    run([tool('apksigner'), 'verify', '--verbose', output])
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    (ROOT / 'SHA256SUMS.txt').write_text(f'{digest}  {output.name}\n')
    print(f'APK: {output}\nSHA-256: {digest}')

if __name__ == '__main__':
    main()
