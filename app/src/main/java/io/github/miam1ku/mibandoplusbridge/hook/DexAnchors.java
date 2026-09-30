// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.hook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds methods by a literal string in their dex code. Obfuscated class and method names move;
 * the log lines and action strings do not.
 */
final class DexAnchors {
    record MethodRef(String classDescriptor, String name, String[] parameters) {}

    private DexAnchors() {}

    static List<MethodRef> methodsReferencing(String apkPath, String literal) throws IOException {
        List<MethodRef> found = new ArrayList<>();
        byte[] needle = literal.getBytes(StandardCharsets.UTF_8);
        try (ZipFile zip = new ZipFile(apkPath)) {
            zip.stream().filter(entry -> entry.getName().startsWith("classes") && entry.getName().endsWith(".dex"))
                    .forEach(entry -> {
                        try {
                            scan(read(zip, entry), needle, found);
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    });
        } catch (IllegalStateException wrapped) {
            if (wrapped.getCause() instanceof IOException io) throw io;
            throw wrapped;
        }
        return found;
    }

    /**
     * Class names in an apk, from dex bytes only.
     * {@code dalvik.system.DexFile} dlopens the host odex. Zygisk Next linker mode
     * replaces that dlopen and aborts the host; a Java catch cannot stop it.
     */
    static List<String> classNames(String apkPath) throws IOException {
        List<String> found = new ArrayList<>();
        try (ZipFile zip = new ZipFile(apkPath)) {
            zip.stream().filter(entry -> entry.getName().startsWith("classes") && entry.getName().endsWith(".dex"))
                    .forEach(entry -> {
                        try {
                            collectClasses(read(zip, entry), found);
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    });
        } catch (IllegalStateException wrapped) {
            if (wrapped.getCause() instanceof IOException io) throw io;
            throw wrapped;
        }
        return found;
    }

    private static void collectClasses(byte[] dex, List<String> found) {
        if (dex.length < 112 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x' || dex[3] != '\n') return;
        ByteBuffer buf = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN);
        int stringIdsSize = buf.getInt(56);
        int stringIdsOff = buf.getInt(60);
        int typeIdsSize = buf.getInt(64);
        int typeIdsOff = buf.getInt(68);
        int classDefsSize = buf.getInt(96);
        int classDefsOff = buf.getInt(100);
        if (stringIdsSize <= 0 || typeIdsSize <= 0 || classDefsSize <= 0) return;
        if (stringIdsSize > 1_000_000 || typeIdsSize > 1_000_000 || classDefsSize > 1_000_000) return;
        for (int ci = 0; ci < classDefsSize; ci++) {
            int def = classDefsOff + ci * 32;
            if (def < 0 || def + 4 > dex.length) return;
            int classIdx = buf.getInt(def);
            if (classIdx < 0 || classIdx >= typeIdsSize) continue;
            int type = typeIdsOff + classIdx * 4;
            if (type < 0 || type + 4 > dex.length) continue;
            int stringIdx = buf.getInt(type);
            if (stringIdx < 0 || stringIdx >= stringIdsSize) continue;
            int id = stringIdsOff + stringIdx * 4;
            if (id < 0 || id + 4 > dex.length) continue;
            String descriptor = safeText(dex, buf.getInt(id));
            if (descriptor.length() < 2 || descriptor.charAt(0) != 'L'
                    || descriptor.charAt(descriptor.length() - 1) != ';') continue;
            found.add(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
        }
    }

    private static String safeText(byte[] dex, int off) {
        if (off < 0 || off >= dex.length) return "";
        int pos = off;
        int shift = 0;
        while (pos < dex.length && shift <= 28) {
            int b = dex[pos++] & 0xff;
            if (b < 0x80) break;
            shift += 7;
        }
        int end = pos;
        while (end < dex.length && dex[end] != 0) end++;
        if (end >= dex.length) return "";
        return new String(dex, pos, end - pos, StandardCharsets.UTF_8);
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    private static void scan(byte[] dex, byte[] needle, List<MethodRef> found) {
        if (dex.length < 112 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x') return;
        ByteBuffer buf = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN);
        int stringIdsSize = buf.getInt(56);
        int stringIdsOff = buf.getInt(60);
        int typeIdsSize = buf.getInt(64);
        int typeIdsOff = buf.getInt(68);
        int protoIdsSize = buf.getInt(72);
        int protoIdsOff = buf.getInt(76);
        int methodIdsSize = buf.getInt(88);
        int methodIdsOff = buf.getInt(92);
        int classDefsSize = buf.getInt(96);
        int classDefsOff = buf.getInt(100);
        int literal = -1;
        for (int i = 0; i < stringIdsSize; i++) {
            if (anchor(stringBytes(dex, buf.getInt(stringIdsOff + i * 4)), needle)) {
                literal = i;
                break;
            }
        }
        if (literal < 0) return;
        byte[] width = widths();
        for (int ci = 0; ci < classDefsSize; ci++) {
            int classData = buf.getInt(classDefsOff + ci * 32 + 24);
            if (classData == 0) continue;
            try {
                Cursor cursor = new Cursor(dex, classData);
                int[] sizes = new int[4];
                for (int i = 0; i < 4; i++) sizes[i] = cursor.uleb();
                for (int i = 0; i < sizes[0] + sizes[1]; i++) {
                    cursor.uleb();
                    cursor.uleb();
                }
                for (int list = 0; list < 2; list++) {
                    int methodIdx = 0;
                    for (int i = 0; i < sizes[2 + list]; i++) {
                        methodIdx += cursor.uleb();
                        cursor.uleb();
                        int code = cursor.uleb();
                        if (methodIdx < 0 || methodIdx >= methodIdsSize) throw new IllegalStateException("idx");
                        if (code != 0 && references(dex, code, literal, width)) {
                            found.add(methodRef(dex, buf, methodIdx, methodIdsOff, typeIdsOff, typeIdsSize,
                                    protoIdsOff, protoIdsSize, stringIdsOff));
                        }
                    }
                }
            } catch (RuntimeException skipped) {
                // A damaged class_data item must not hide anchors in later classes.
            }
        }
    }

    private static boolean references(byte[] dex, int codeOff, int literal, byte[] width) {
        if (codeOff < 0 || codeOff + 16 > dex.length) return false;
        ByteBuffer buf = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN);
        int insnsSize = buf.getInt(codeOff + 12);
        int insnsOff = codeOff + 16;
        if (insnsSize <= 0 || insnsSize > 200_000 || insnsOff + insnsSize * 2 > dex.length) return false;
        for (int i = 0; i < insnsSize; ) {
            int unit = buf.getShort(insnsOff + i * 2) & 0xffff;
            int op = unit & 0xff;
            int size = width[op] & 0xff;
            if (size == 0 || i + size > insnsSize) return false;
            if (op == 0x1a && (buf.getShort(insnsOff + (i + 1) * 2) & 0xffff) == literal) return true;
            if (op == 0x1b && i + 2 < insnsSize) {
                int sid = (buf.getShort(insnsOff + (i + 1) * 2) & 0xffff)
                        | ((buf.getShort(insnsOff + (i + 2) * 2) & 0xffff) << 16);
                if (sid == literal) return true;
            }
            i += size;
        }
        return false;
    }

    private static MethodRef methodRef(byte[] dex, ByteBuffer buf, int methodIdx, int methodIdsOff,
            int typeIdsOff, int typeIdsSize, int protoIdsOff, int protoIdsSize, int stringIdsOff) {
        int method = methodIdsOff + methodIdx * 8;
        int classIdx = buf.getShort(method) & 0xffff;
        int protoIdx = buf.getShort(method + 2) & 0xffff;
        int nameIdx = buf.getInt(method + 4);
        if (classIdx >= typeIdsSize || protoIdx >= protoIdsSize) throw new IllegalStateException("ref");
        String className = cstring(dex, buf.getInt(stringIdsOff + (buf.getInt(typeIdsOff + classIdx * 4)) * 4));
        String name = cstring(dex, buf.getInt(stringIdsOff + nameIdx * 4));
        int proto = protoIdsOff + protoIdx * 12;
        int paramsOff = buf.getInt(proto + 8);
        String[] params = new String[0];
        if (paramsOff != 0) {
            int count = buf.getInt(paramsOff);
            params = new String[count];
            for (int i = 0; i < count; i++) {
                int typeIdx = buf.getShort(paramsOff + 4 + i * 2) & 0xffff;
                params[i] = cstring(dex, buf.getInt(stringIdsOff + (buf.getInt(typeIdsOff + typeIdx * 4)) * 4));
            }
        }
        return new MethodRef(className, name, params);
    }

    private static byte[] stringBytes(byte[] dex, int off) {
        int[] pos = new int[] {off};
        uleb(dex, pos);
        int end = pos[0];
        while (end < dex.length && dex[end] != 0) end++;
        byte[] out = new byte[end - pos[0]];
        System.arraycopy(dex, pos[0], out, 0, out.length);
        return out;
    }

    private static String cstring(byte[] dex, int off) {
        int[] pos = new int[] {off};
        uleb(dex, pos);
        int end = pos[0];
        while (end < dex.length && dex[end] != 0) end++;
        return new String(dex, pos[0], end - pos[0], StandardCharsets.UTF_8);
    }

    private static boolean anchor(byte[] text, byte[] needle) {
        if (text.length < needle.length) return false;
        for (int i = 0; i < needle.length; i++) if (text[i] != needle[i]) return false;
        return text.length == needle.length || text[needle.length] == ' ';
    }


    private static int uleb(byte[] dex, int[] pos) {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = dex[pos[0]++] & 0xff;
            value |= (b & 0x7f) << shift;
            if (b < 0x80) return value;
            shift += 7;
        }
    }

    private static final class Cursor {
        private final byte[] dex;
        private int pos;
        Cursor(byte[] dex, int pos) { this.dex = dex; this.pos = pos; }
        int uleb() {
            int[] at = new int[] {pos};
            int value = DexAnchors.uleb(dex, at);
            pos = at[0];
            return value;
        }
    }

    /** Widths in code units. Zero means the method scan stops; payloads after a switch still match earlier strings. */
    private static byte[] widths() {
        byte[] w = new byte[256];
        int[] one = {0x00, 0x01, 0x04, 0x07, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12,
                0x1d, 0x1e, 0x21, 0x27, 0x28};
        int[] two = {0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f, 0x20, 0x22, 0x23, 0x29};
        int[] three = {0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, 0x24, 0x25, 0x26, 0x2a, 0x2b, 0x2c,
                0x6e, 0x6f, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78};
        for (int op : one) w[op] = 1;
        for (int op = 0x7b; op <= 0x8f; op++) w[op] = 1;
        for (int op = 0xb0; op <= 0xcf; op++) w[op] = 1;
        for (int op : two) w[op] = 2;
        for (int op = 0x2d; op <= 0x3d; op++) w[op] = 2;
        for (int op = 0x44; op <= 0x6d; op++) w[op] = 2;
        for (int op = 0x90; op <= 0xe2; op++) w[op] = 2;
        for (int op : three) w[op] = 3;
        for (int op = 0xe3; op <= 0xff; op++) w[op] = 3;
        w[0x18] = 5;
        return w;
    }
}
