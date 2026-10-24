package com.dantheman827.mainredirect;

import android.os.Build;
import android.os.Process;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Copy assets into the TARGET UID's code cache, never the module's private data. */
final class ReplacementStore {
    private final String apk;
    private final File root;
    private String cached;

    ReplacementStore(String apk, String targetDataDir) {
        this.apk = apk;
        root = new File(targetDataDir, "code_cache/mainredirect");
    }

    synchronized String path() throws Exception {
        if (cached != null && new File(cached).isFile()) return cached;
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) {
            throw new IOException("Cannot create " + root);
        }
        String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        try (ZipFile zip = new ZipFile(apk)) {
            for (String abi : abis) {
                ZipEntry entry = zip.getEntry("assets/replacements/" + abi + "/libmain.so");
                if (entry == null) continue;
                File temp = File.createTempFile("payload-", ".tmp", root);
                try {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (InputStream in = zip.getInputStream(entry);
                         FileOutputStream out = new FileOutputStream(temp)) {
                        // The open descriptor can still write after chmod. Publish
                        // only read-only code, including on newer Android versions.
                        if (!temp.setReadOnly()) throw new IOException("Cannot make payload read-only");
                        byte[] buffer = new byte[32768];
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            out.write(buffer, 0, n);
                            digest.update(buffer, 0, n);
                        }
                        out.getFD().sync();
                    }
                    validateElf(temp, abi);
                    String hash = hex(digest.digest());
                    File directory = new File(root, abi + "-" + hash);
                    if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                        throw new IOException("Cannot create " + directory);
                    }
                    File destination = new File(directory, "libmain.so");
                    if (destination.exists()) {
                        if (!hash.equals(sha256(destination)) || destination.canWrite()) {
                            throw new IOException("Invalid cached payload; clear target app cache");
                        }
                    } else if (!temp.renameTo(destination)) {
                        // Another target process may have published the same bytes.
                        if (!destination.isFile() || !hash.equals(sha256(destination)) ||
                                destination.canWrite()) {
                            throw new IOException("Cannot publish " + destination);
                        }
                    }
                    cached = destination.getAbsolutePath();
                    de.robv.android.xposed.XposedBridge.log("MainRedirect: prepared " + cached);
                    return cached;
                } finally {
                    // Delete any unpublished temporary file (parent is writable).
                    if (temp.exists() && !temp.delete()) temp.deleteOnExit();
                }
            }
        }
        throw new IOException("No replacement for this process ABI: " + String.join(", ", abis));
    }

    private static void validateElf(File file, String abi) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            byte[] h = new byte[20];
            in.readFully(h);
            int elfClass;
            int machine;
            switch (abi) {
                case "arm64-v8a": elfClass = 2; machine = 183; break;
                case "armeabi-v7a": elfClass = 1; machine = 40; break;
                case "x86_64": elfClass = 2; machine = 62; break;
                case "x86": elfClass = 1; machine = 3; break;
                default: throw new IOException("Unsupported ABI: " + abi);
            }
            if (h[0] != 127 || h[1] != 'E' || h[2] != 'L' || h[3] != 'F' ||
                    h[4] != elfClass || h[5] != 1 ||
                    ((h[18] & 255) | ((h[19] & 255) << 8)) != machine) {
                throw new IOException("Bundled ELF does not match " + abi);
            }
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            result[2 * i] = digits[(bytes[i] & 255) >>> 4];
            result[2 * i + 1] = digits[bytes[i] & 15];
        }
        return new String(result);
    }
}
