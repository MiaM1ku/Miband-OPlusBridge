/* Copyright (C) 2017-2024 Andreas Shimokawa, Yoran Vulker.
 * Adapted Xiaomi condition codes from Gadgetbridge commit
 * 75f923904f8504b03fabdee0987fd1c269a92278; see compat/upstream.json.
 */
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

/** Current OHealth WeatherCodeNewEnum.weatherCode -> Xiaomi condition; -1 means unsupported. */
public final class WeatherConditionMap {
    private WeatherConditionMap() {}

    public static int fromOHealth(int code) {
        // OHealth 6.9.37 WeatherCodeNewEnum; Xiaomi values from the pinned
        // Gadgetbridge XiaomiWeatherConditions table and observed Band 11 frames.
        // Unsupported categories must not masquerade as clear skies.
        return switch (code) {
            case 1, 6, 7 -> 7;
            case 2, 3, 4, 5 -> 3;
            case 8 -> 8;
            case 9, 10, 11 -> 9;
            case 12, 13 -> 10;
            case 14, 15, 16 -> 12;
            case 17 -> 19;
            case 18, 19, 20 -> 5;
            case 22, 23, 24, 25 -> 4;
            case 26 -> 33;
            case 27, 28 -> 13;
            case 29 -> 14;
            case 30 -> 26;
            case 31 -> 15;
            case 32 -> 27;
            case 33 -> 16;
            case 34 -> 28;
            case 35, 36 -> 17;
            case 37 -> 6;
            case 38, 39, 40, 50, 51, 52, 53 -> 18;
            case 41, 42, 43, 44 -> 32;
            case 45, 46, 47 -> 29;
            case 48 -> 20;
            case 49 -> 31;
            case 54, 55, 68 -> 0;
            case 56, 57, 58, 69 -> 1;
            case 59 -> 2;
            case 60, 61, 62, 63, 64, 65, 66, 67 -> 30;
            default -> -1;
        };
    }
}
