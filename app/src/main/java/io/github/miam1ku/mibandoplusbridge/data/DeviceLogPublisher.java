// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/** Publishes a completed device-log bundle to the public Downloads collection. */
public final class DeviceLogPublisher {
    private static final long MAX_EXTRACTED_BYTES = 512L * 1024 * 1024;
    private static final int MAX_EXTRACTED_FILES = 50_000;
    private static final DateTimeFormatter FOLDER_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    public record Result(File browseFile, Uri browseUri, Uri archiveUri, String relativeDirectory,
                         String archiveName, int extractedFiles, long extractedBytes) {}

    private enum ArchiveType { ZIP, TAR, GZIP_TAR, UNKNOWN }

    private DeviceLogPublisher() {}

    public static Result publish(Context context, File source) throws IOException {
        if (source == null || !source.isFile() || source.length() <= 0) {
            throw new IOException("DEVICE_LOG_FILE_MISSING");
        }
        ArchiveType type = detect(source);
        String archiveName = displayName(source, type);
        String relative = "Download/Miband-OPlusBridge/device-logs/"
                + FOLDER_TIME.format(LocalDateTime.now()) + "/";
        Uri archiveUri = copyToDownloads(context, source, relative, archiveName, mime(type, archiveName));

        int files = 0;
        long bytes = 0;
        File browseFile = null;
        Uri browseUri = null;
        try {
            if (type != ArchiveType.UNKNOWN) {
                browseFile = new File(source.getParentFile(), browseName(source.getName()));
                ExtractCount count = extract(context, source, type, relative, browseFile);
                files = count.files;
                bytes = count.bytes;
                if (files > 0 && browseFile.isFile() && browseFile.length() > 0) {
                    browseUri = copyToDownloads(context, browseFile, relative,
                            browseFile.getName(), "application/zip");
                } else {
                    browseFile.delete();
                    browseFile = null;
                }
            }
        } catch (Exception ignored) {
            // The original archive is still complete and shareable; extraction is best effort.
            if (browseFile != null) browseFile.delete();
            browseFile = null;
            browseUri = null;
            files = 0;
            bytes = 0;
        }
        return new Result(browseFile, browseUri, archiveUri, relative,
                archiveName, files, bytes);
    }

    private static ExtractCount extract(Context context, File source, ArchiveType type,
            String relativeRoot, File browseFile) throws Exception {
        try (BufferedInputStream buffered = new BufferedInputStream(new FileInputStream(source), 64 * 1024);
             ArchiveInputStream archive = archive(buffered, type);
             ZipOutputStream normalized = new ZipOutputStream(
                     new java.io.BufferedOutputStream(new java.io.FileOutputStream(browseFile), 64 * 1024))) {
            int files = 0;
            long bytes = 0;
            Set<String> normalizedNames = new HashSet<>();
            ArchiveEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (files >= MAX_EXTRACTED_FILES || bytes >= MAX_EXTRACTED_BYTES) break;
                if (entry.isDirectory()) continue;
                if (entry instanceof TarArchiveEntry tar && tar.isSymbolicLink()) continue;
                String safe = safeEntryName(entry.getName());
                if (safe == null) continue;
                Path path = Paths.get(safe);
                String parent = path.getParent() == null
                        ? "" : path.getParent().toString().replace('\\', '/') + "/";
                String display = path.getFileName().toString();
                String normalizedName = uniqueEntry(safe, normalizedNames);
                Uri uri = createDownload(context, relativeRoot + parent, display,
                        mimeFromName(display));
                try (OutputStream out = context.getContentResolver().openOutputStream(uri, "w")) {
                    if (out == null) throw new IOException("DEVICE_LOG_STORAGE_FAILED");
                    normalized.putNextEntry(new ZipEntry(normalizedName));
                    long copied = copyLimited(archive, out, normalized, MAX_EXTRACTED_BYTES - bytes);
                    normalized.closeEntry();
                    bytes += copied;
                    files++;
                } catch (Exception failure) {
                    context.getContentResolver().delete(uri, null, null);
                    throw failure;
                }
                finish(context, uri);
            }
            return new ExtractCount(files, bytes);
        }
    }

    private static ArchiveInputStream archive(BufferedInputStream input, ArchiveType type)
            throws IOException {
        return switch (type) {
            case ZIP -> new ZipArchiveInputStream(input);
            case TAR -> new TarArchiveInputStream(input);
            case GZIP_TAR -> new TarArchiveInputStream(new GzipCompressorInputStream(input, true));
            case UNKNOWN -> throw new IOException("DEVICE_LOG_ARCHIVE_UNKNOWN");
        };
    }

    private static ArchiveType detect(File file) throws IOException {
        byte[] head = new byte[512];
        int read;
        try (InputStream input = new FileInputStream(file)) {
            read = input.read(head);
        }
        if (read >= 4 && head[0] == 'P' && head[1] == 'K'
                && (head[2] == 3 || head[2] == 5 || head[2] == 7)) return ArchiveType.ZIP;
        if (read >= 2 && (head[0] & 0xff) == 0x1f && (head[1] & 0xff) == 0x8b) {
            return ArchiveType.GZIP_TAR;
        }
        if (read >= 262 && head[257] == 'u' && head[258] == 's' && head[259] == 't'
                && head[260] == 'a' && head[261] == 'r') return ArchiveType.TAR;
        return ArchiveType.UNKNOWN;
    }

    private static Uri copyToDownloads(Context context, File source, String relative,
            String fileName, String mime) throws IOException {
        Uri uri = createDownload(context, relative, fileName, mime);
        try (InputStream input = new FileInputStream(source);
             OutputStream output = context.getContentResolver().openOutputStream(uri, "w")) {
            if (output == null) throw new IOException("DEVICE_LOG_STORAGE_FAILED");
            input.transferTo(output);
        } catch (Exception failure) {
            context.getContentResolver().delete(uri, null, null);
            throw failure instanceof IOException typed ? typed
                    : new IOException("DEVICE_LOG_STORAGE_FAILED", failure);
        }
        finish(context, uri);
        return uri;
    }

    private static Uri createDownload(Context context, String relative, String name, String mime) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relative);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (uri == null) throw new IllegalStateException("DEVICE_LOG_STORAGE_FAILED");
        return uri;
    }

    private static void finish(Context context, Uri uri) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        context.getContentResolver().update(uri, values, null, null);
    }

    private static long copyLimited(InputStream input, OutputStream output,
            ZipOutputStream normalized, long remaining) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long copied = 0;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) break;
            output.write(buffer, 0, count);
            normalized.write(buffer, 0, count);
            copied += count;
            remaining -= count;
        }
        if (remaining == 0 && input.read() >= 0) {
            throw new IOException("DEVICE_LOG_EXTRACT_LIMIT_REACHED");
        }
        return copied;
    }

    static String safeEntryName(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.replace('\\', '/').replaceAll("[\\p{Cntrl}]", "")
                .replaceFirst("^/+", "");
        for (String segment : value.split("/")) {
            if ("..".equals(segment)) return null;
        }
        Path path = Paths.get(value).normalize();
        if (path.isAbsolute() || path.startsWith("..")) return null;
        String clean = path.toString().replace('\\', '/');
        return clean.isBlank() || ".".equals(clean) ? null : clean;
    }

    private static String displayName(File source, ArchiveType type) {
        String name = source.getName().isBlank() ? "device-log.bin" : source.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        if (type == ArchiveType.GZIP_TAR && !(lower.endsWith(".gz") || lower.endsWith(".tgz"))) {
            return name + ".tar.gz";
        }
        if (type == ArchiveType.ZIP && !lower.endsWith(".zip")) return name + ".zip";
        return name;
    }

    private static String browseName(String source) {
        String lower = source.toLowerCase(Locale.ROOT);
        String base = lower.endsWith(".tar.gz") ? source.substring(0, source.length() - 7)
                : lower.endsWith(".tgz") ? source.substring(0, source.length() - 4)
                : lower.endsWith(".gz") ? source.substring(0, source.length() - 3)
                : lower.endsWith(".tar") ? source.substring(0, source.length() - 4)
                : source;
        return base + "-browse.zip";
    }

    private static String uniqueEntry(String path, Set<String> used) {
        if (used.add(path)) return path;
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        String parent = slash >= 0 ? path.substring(0, slash + 1) : "";
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int suffix = dot > slash ? dot - slash - 1 : name.length();
        String stem = name.substring(0, suffix);
        String extension = name.substring(suffix);
        for (int index = 2; ; index++) {
            String candidate = parent + stem + "-" + index + extension;
            if (used.add(candidate)) return candidate;
        }
    }

    private static String mime(ArchiveType type, String fileName) {
        return switch (type) {
            case ZIP -> "application/zip";
            case TAR -> "application/x-tar";
            case GZIP_TAR -> "application/gzip";
            case UNKNOWN -> "application/octet-stream";
        };
    }

    private static String mimeFromName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < name.length()) {
            String found = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (found != null) return found;
        }
        return "application/octet-stream";
    }

    private record ExtractCount(int files, long bytes) {}
}
