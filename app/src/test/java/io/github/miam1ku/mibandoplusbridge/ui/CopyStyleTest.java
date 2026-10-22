// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

/** User-facing Chinese copy states the situation and the next step in positive words. */
public final class CopyStyleTest {
    /** Words that turn a hint into a prohibition or a dead end. */
    static final List<String> AVOID = List.of(
            "不", "无法", "请勿", "禁止", "没有", "未", "暂不", "失败", "拒绝", "错误", "丢失");

    /** Files whose Chinese strings match host text or name products, not bridge copy. */
    static final Set<String> EXEMPT = Set.of(
            "notify/CallPresentation.java",
            "hook/OHealthDeviceHook.java",
            "hook/OHealthWeatherHook.java",
            "data/BandCatalog.java",
            "protocol/SportReportParser.java");

    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    @Test public void sourceLiteralsUsePositiveWording() throws IOException {
        Path root = sourceRoot();
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                relative = relative.substring(relative.indexOf("mibandoplusbridge/") + "mibandoplusbridge/".length());
                if (EXEMPT.contains(relative)) continue;
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index).trim();
                    if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) continue;
                    Matcher literal = LITERAL.matcher(line);
                    while (literal.find()) {
                        String value = literal.group(1);
                        if (!CJK.matcher(value).find()) continue;
                        scanned++;
                        String hit = firstAvoided(value);
                        if (hit != null) offenders.add(relative + ":" + (index + 1) + " [" + hit + "] " + value);
                    }
                }
            }
        }
        assertTrue("scanner found " + scanned + " Chinese literals", scanned > 300);
        assertEquals("firstAvoided self-check", "未", firstAvoided("未连接"));
        assertEquals("Rephrase these strings positively:\n" + String.join("\n", offenders), 0, offenders.size());
    }

    @Test public void statusCopyEndsWithStateOrAction() {
        List<String> samples = new ArrayList<>();
        for (SetupProgress.Step step : SetupProgress.Step.values()) {
            samples.add(Copy.setupLabel(step));
            samples.add(Copy.setupHint(step));
        }
        for (String code : List.of("", "OBSERVED_PROFILE_REQUIRED", "NATIVE_OWNERSHIP_REQUIRED", "BINDING_REGION",
                "BLUETOOTH_DISABLED", "SOMETHING_ELSE")) {
            samples.add(Copy.offlineReason(code));
        }
        for (String code : List.of("USER_LOCKED", "UNPROVISIONED", "BINDING_ADDRESS", "BINDING_REGION",
                "TOKEN_ENCODING_UNSUPPORTED", "OBSERVED_PROFILE_REQUIRED", "FIRMWARE_OR_MODEL_UNSUPPORTED",
                "OOB_BRANCH_UNSUPPORTED", "PROTOCOL_VERSION_UNSUPPORTED", "DEVICE_IDENTITY_CHANGED",
                "BAND_STATE_STORAGE_FAILED", "ROOT_REQUIRED", "HOST_VERSION_UNSUPPORTED",
                "NEARBY_PERMISSION_REQUIRED", "DEVICE_NOT_REGISTERED", "DIAGNOSTIC_SOCKET_STILL_ACTIVE",
                "OWNERSHIP_RECOVERY_REQUIRED", "OWNERSHIP_ROLLBACK_FAILED", "X", "")) {
            samples.add(Copy.ownershipFailure(code));
        }
        for (String code : List.of("IMPORT_WINDOW_OPEN", "IMPORT_WINDOW_CLOSED", "DIAGNOSTIC_WINDOW_OPEN",
                "PROTOCOL_CAPTURE_OPEN", "UNPROVISIONED", "BINDING_SAVED", "BINDING_INCOMPLETE", "NO_BAND_KEY",
                "MI_DATABASE_MISSING", "MI_DATABASE_UNREADABLE", "ROOT_ACTION_FAILED", "MULTIPLE_BANDS",
                "USER_LOCKED", "CREDENTIAL_STORAGE_FAILED", "X")) {
            samples.add(Copy.importResult(code, "", "", ""));
        }
        for (String code : List.of("WEATHER_CITY_CONFIRMATION_REQUIRED", "WEATHER_CITY_SETUP_REQUIRED",
                "WEATHER_CITY_LIST_INVALID", "WEATHER_SOURCE_STALE", "WEATHER_BAND_REJECTED", "WEATHER_BUSY",
                "WEATHER_DISCONNECTED", "WEATHER_DATA_INVALID", "X")) {
            samples.add(Copy.weatherError(code));
        }
        for (String code : List.of("OHEALTH_LOCATION_PERMISSION_REQUIRED", "OHEALTH_LOCATION_DISABLED",
                "OHEALTH_LOCATION_UNAVAILABLE", "OHEALTH_WEATHER_CLOUD_UNAVAILABLE", "OHEALTH_WEATHER_TIMEOUT", "X")) {
            samples.add(Copy.weatherSource(code, false));
        }
        for (String code : List.of("ACCOUNT_CONFIRMATION_REQUIRED", "HEALTH_OUTBOX_FULL", "HISTORY_FORMAT_UNSUPPORTED",
                "HISTORY_FILE_REJECTED", "HISTORY_STORAGE_FAILED", "HEALTH_REPLAY_PAUSED")) {
            samples.add(Copy.healthCollection(code));
        }
        samples.add(Copy.healthHost("UNAVAILABLE"));
        for (int stage = 0; stage < 8; stage++) samples.add(Copy.stageName(stage));
        samples.add(Copy.stageName(null));
        samples.add(Copy.tileSubtitle(false, false, false, -1));
        samples.add(Copy.tileSubtitle(true, false, false, -1));
        samples.add(Copy.tileSubtitle(true, true, false, -1));
        samples.add(Copy.tileSubtitle(true, true, true, 50));
        samples.add(Copy.GUIDE);
        for (String sample : samples) {
            if (sample.isEmpty()) continue;
            String hit = firstAvoided(sample);
            assertTrue("[" + hit + "] " + sample, hit == null);
        }
        assertFalse(Copy.ownershipFailure("X").isBlank());
        assertFalse(Copy.importResult("X", "", "", "").isBlank());
        assertFalse(Copy.weatherError("X").isBlank());
    }

    static String firstAvoided(String value) {
        for (String word : AVOID) if (value.contains(word)) return word;
        return null;
    }

    private static Path sourceRoot() {
        for (String candidate : List.of("src/main/java", "app/src/main/java")) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path)) return path;
        }
        throw new IllegalStateException("main source root");
    }
}
