"""Extract pinned LibADB source; apply bounded socket and cancellation fixes."""
from pathlib import Path
from zipfile import ZipFile
import shutil

root = Path(__file__).resolve().parents[1]
dest = root / 'vendor/libadb-src'
with ZipFile(root / 'vendor/libadb-android-3.1.1-source.zip') as archive:
    for name in archive.namelist():
        marker = '/libadb/src/main/java/'
        if marker in name and name.endswith('.java'):
            target = dest / name.split(marker, 1)[1]
        elif '/LICENSES/' in name and not name.endswith('/'):
            target = root / 'vendor/licenses/libadb' / name.split('/LICENSES/', 1)[1]
        else:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(archive.read(name))

path = dest / 'io/github/muntashirakon/adb/AdbConnection.java'
src = path.read_text(encoding='utf-8')
src = src.replace('this.mSocket = new Socket(host, port);',
    'this.mSocket = new Socket();\n            this.mSocket.connect(new java.net.InetSocketAddress(host, port), 3000);')
src = src.replace('mConnectionThread.join();', 'mConnectionThread.join(1500);')
src = src.replace('''        synchronized (stream) {
            stream.wait();
        }''', '''        synchronized (stream) {
            long end = android.os.SystemClock.elapsedRealtime() + 15000;
            while (!stream.isOpenAcknowledged() && !stream.isClosed() && isConnected()) {
                long remaining = end - android.os.SystemClock.elapsedRealtime();
                if (remaining <= 0) {
                    mOpenedStreams.remove(localId);
                    stream.close();
                    throw new java.net.SocketTimeoutException("ADB stream open timed out");
                }
                stream.wait(Math.min(remaining, 500));
            }
        }''')
src = src.replace('mMaxData = msg.arg1;', 'mMaxData = Math.max(4096, Math.min(msg.arg1, 1024 * 1024));')
src = src.replace('''        // Destroy keypair
        try {
            mKeyPair.destroy();
        } catch (DestroyFailedException ignore) {
        }''', '        // Identity lifetime is owned by AdbBridge, not by an individual connection.')
path.write_text(src, encoding='utf-8')

path = dest / 'io/github/muntashirakon/adb/AdbStream.java'
src = path.read_text(encoding='utf-8')
src = src.replace('void updateRemoteId(int remoteId) {', 'boolean isOpenAcknowledged() { return mRemoteId != 0; }\n\n    void updateRemoteId(int remoteId) {')
src = src.replace('while ((data = mReadQueue.poll()) == null && !mIsClosed)', 'while ((data = mReadQueue.poll()) == null && !mIsClosed && !mPendingClose)')
src = src.replace('''            if (mIsClosed) {
                throw new IOException("Stream closed.");
            }''', '''            if (mIsClosed) {
                return -1;
            }''')
path.write_text(src, encoding='utf-8')

path = dest / 'io/github/muntashirakon/adb/AdbProtocol.java'
src = path.read_text(encoding='utf-8')
src = src.replace('''        ByteBuffer bbuf = ByteBuffer.allocate(destination.length() + 1);
        bbuf.put(StringCompat.getBytes(destination, "UTF-8"));''', '''        byte[] encodedDestination = StringCompat.getBytes(destination, "UTF-8");
        ByteBuffer bbuf = ByteBuffer.allocate(encodedDestination.length + 1);
        bbuf.put(encodedDestination);''')
path.write_text(src, encoding='utf-8')

path = dest / 'io/github/muntashirakon/adb/PairingConnectionCtx.java'
src = path.read_text(encoding='utf-8')
src = src.replace('private DataInputStream mInputStream;', 'private volatile Socket boundedSocket;\n    private volatile boolean cancelled;\n    private DataInputStream mInputStream;')
src = src.replace('socket = new Socket(mHost, mPort);', 'socket = new Socket();\n            boundedSocket = socket;\n            if (cancelled) throw new IOException("Cancelled");\n            socket.connect(new java.net.InetSocketAddress(mHost, mPort), 3000);\n            socket.setSoTimeout(12000);')
src = src.replace('sslSocket.startHandshake();', 'sslSocket.setSoTimeout(12000);\n        sslSocket.startHandshake();')
start = src.index('    @Override\n    public void close() {')
end = src.index('    private static class PeerInfo', start)
src = src[:start] + '''    /** Locally patched: close a partially initialized pairing exchange safely. */
    public void cancel() {
        cancelled = true;
        Socket socket = boundedSocket;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    @Override
    public void close() {
        cancel();
        Arrays.fill(mPswd, (byte) 0);
        if (mInputStream != null) try { mInputStream.close(); } catch (IOException ignored) { }
        if (mOutputStream != null) try { mOutputStream.close(); } catch (IOException ignored) { }
        if (mPairingAuthCtx != null) { mPairingAuthCtx.destroy(); mPairingAuthCtx = null; }
    }

''' + src[end:]
path.write_text(src, encoding='utf-8')
print('LibADB 3.1.1 source prepared with local timeout/cleanup patches')
assets = root / 'vendor/notice-assets'
assets.mkdir(parents=True, exist_ok=True)
shutil.copytree(root / 'vendor/licenses', assets / 'licenses', dirs_exist_ok=True)
shutil.copyfile(root / 'THIRD_PARTY_NOTICES.md', assets / 'THIRD_PARTY_NOTICES.md')
