/* Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo, opcode.
 * Adapted weather wire semantics from Gadgetbridge commit
 * 75f923904f8504b03fabdee0987fd1c269a92278; see compat/upstream.json.
 */
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import org.json.JSONArray;
import org.json.JSONObject;

/** Converts complete OHealth weather samples into only fields observed on Band 11. */
public final class BandWeatherEncoder {
    public record Day(long timeMs, int dayCode, int nightCode, double minTemp, double maxTemp,
                      Long sunriseMs, Long sunsetMs) {}
    public record Hour(long timeMs, int conditionCode, double temperature) {}
    public record Sample(String locationKey, String cityName, String locationName, String timezone,
                         String unit, long publishedAtMs, int conditionCode, double temperature,
                         Integer humidityPercent, Integer aqi, Integer windPower, Integer windDegree,
                         Integer uvIndex, Double pressure, List<Day> daily, List<Hour> hourly) {}

    private static final DateTimeFormatter BAND_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);

    private BandWeatherEncoder() {}

    public static Sample parse(JSONObject snapshot) throws Exception {
        JSONArray days = snapshot.getJSONArray("daily");
        JSONArray hours = snapshot.getJSONArray("hourly");
        ArrayList<Day> daily = new ArrayList<>(days.length());
        ArrayList<Hour> hourly = new ArrayList<>(hours.length());
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.getJSONObject(i);
            daily.add(new Day(day.getLong("timeMs"), day.getInt("dayCode"),
                    day.getInt("nightCode"), day.getDouble("minTemp"), day.getDouble("maxTemp"),
                    day.has("sunriseMs") ? day.getLong("sunriseMs") : null,
                    day.has("sunsetMs") ? day.getLong("sunsetMs") : null));
        }
        for (int i = 0; i < hours.length(); i++) {
            JSONObject hour = hours.getJSONObject(i);
            hourly.add(new Hour(hour.getLong("timeMs"), hour.getInt("conditionCode"),
                    hour.getDouble("temperature")));
        }
        return new Sample(snapshot.getString("locationKey"), snapshot.getString("cityName"),
                snapshot.getString("locationName"), snapshot.getString("timezone"),
                snapshot.getString("unit"), snapshot.getLong("publishedAtMs"),
                snapshot.getInt("conditionCode"), snapshot.getDouble("temperature"),
                snapshot.has("humidity") ? snapshot.getInt("humidity") : null,
                snapshot.has("aqi") ? snapshot.getInt("aqi") : null,
                snapshot.has("windPower") ? snapshot.getInt("windPower") : null,
                snapshot.has("windDegree") ? snapshot.getInt("windDegree") : null,
                snapshot.has("uvIndex") ? snapshot.getInt("uvIndex") : null,
                snapshot.has("pressure") ? snapshot.getDouble("pressure") : null,
                List.copyOf(daily), List.copyOf(hourly));
    }

    /** Check the forecast without assuming OHealth's city key is a Band weather code. */
    public static void validateForecast(Sample sample) {
        if (sample == null || sample.locationKey() == null || sample.locationKey().isBlank()
                || sample.locationKey().length() > 64
                || sample.cityName() == null || sample.cityName().isBlank() || sample.cityName().length() > 80
                || sample.locationName() == null || sample.locationName().isBlank() || sample.locationName().length() > 80
                || !("c".equals(sample.unit()) || "f".equals(sample.unit()))
                || sample.daily() == null || sample.hourly() == null) {
            throw new IllegalArgumentException("WEATHER_SAMPLE_INCOMPLETE");
        }
        zone(sample.timezone());
        checkTime(sample.publishedAtMs());
        mapCondition(sample.conditionCode());
        celsius(sample.temperature(), sample.unit());
        if (sample.humidityPercent() != null
                && (sample.humidityPercent() < 0 || sample.humidityPercent() > 100)) {
            throw new IllegalArgumentException("WEATHER_HUMIDITY_INVALID");
        }
        if (sample.aqi() != null && (sample.aqi() < 0 || sample.aqi() > 500)) {
            throw new IllegalArgumentException("WEATHER_AQI_INVALID");
        }
        if (sample.windPower() != null && (sample.windPower() < 0 || sample.windPower() > 17)) {
            throw new IllegalArgumentException("WEATHER_WIND_INVALID");
        }
        if (sample.windDegree() != null && (sample.windDegree() < 0 || sample.windDegree() > 360)) {
            throw new IllegalArgumentException("WEATHER_WIND_INVALID");
        }
        if (sample.uvIndex() != null && (sample.uvIndex() < 0 || sample.uvIndex() > 15)) {
            throw new IllegalArgumentException("WEATHER_UV_INVALID");
        }
        if (sample.pressure() != null && !(sample.pressure() >= 800 && sample.pressure() <= 1100
                || sample.pressure() >= 80_000 && sample.pressure() <= 110_000)) {
            throw new IllegalArgumentException("WEATHER_PRESSURE_INVALID");
        }
        long previousDay = -1;
        for (Day day : sample.daily()) {
            checkTime(day.timeMs());
            if (day.timeMs() <= previousDay || !Double.isFinite(day.minTemp())
                    || !Double.isFinite(day.maxTemp()) || day.maxTemp() < day.minTemp()) {
                throw new IllegalArgumentException("WEATHER_DAY_INVALID");
            }
            previousDay = day.timeMs();
            celsius(day.minTemp(), sample.unit());
            celsius(day.maxTemp(), sample.unit());
            if (day.sunriseMs() != null && day.sunsetMs() != null) {
                checkTime(day.sunriseMs());
                checkTime(day.sunsetMs());
                if (day.sunriseMs() >= day.sunsetMs()) throw new IllegalArgumentException("WEATHER_SUN_TIMES_INVALID");
            }
        }
        long previousHour = -1;
        for (Hour hour : sample.hourly()) {
            checkTime(hour.timeMs());
            if (hour.timeMs() <= previousHour || !Double.isFinite(hour.temperature())) {
                throw new IllegalArgumentException("WEATHER_HOUR_INVALID");
            }
            previousHour = hour.timeMs();
            celsius(hour.temperature(), sample.unit());
        }
    }


    public static Sample bindExplicitCity(Sample sample, String sourceKey, String sourceCity,
                                          String sourcePlace, String bandCode, String bandName,
                                          XiaomiProto.WeatherLocations configured) {
        validateForecast(sample);
        if (!sample.locationKey().equals(sourceKey) || !sample.cityName().equals(sourceCity)
                || !sample.locationName().equals(sourcePlace)
                || bandCode == null || !bandCode.matches("weathercn:[0-9]{9}")
                || bandName == null || bandName.isBlank() || bandName.length() > 80) {
            throw new IllegalArgumentException("WEATHER_CITY_CONFIRMATION_INVALID");
        }
        boolean matched = false;
        if (configured != null) {
            for (var city : configured.getLocationList()) {
                if (bandCode.equals(city.getCode()) && bandName.equals(city.getName())) {
                    matched = true;
                    break;
                }
            }
        }
        if (!matched) throw new IllegalArgumentException("WEATHER_CITY_CONFIRMATION_INVALID");
        return copy(sample, bandCode, sample.cityName(), sample.locationName());
    }

    /** One configured city is enough. Several cities still need the previously confirmed pair. */
    public static Sample bindObservedCity(Sample sample, XiaomiProto.WeatherLocations configured,
                                          String confirmedCode, String confirmedName) {
        validateForecast(sample);
        var valid = validCities(configured);
        if (valid.isEmpty()) return sample;
        XiaomiProto.WeatherLocation chosen = valid.get(0);
        if (valid.size() > 1) {
            chosen = null;
            for (var city : valid) {
                if (city.getCode().equals(confirmedCode) && city.getName().equals(confirmedName)) chosen = city;
            }
            if (chosen == null) throw new IllegalArgumentException("WEATHER_CITY_CONFIRMATION_REQUIRED");
        }
        String locationName = chosen.getName();
        String cityName = locationName.equals(sample.cityName()) ? sample.locationName() : sample.cityName();
        return copy(sample, chosen.getCode(), cityName, locationName);
    }

    /** Official sync writes the band's current list back unchanged before the forecast frames. */
    public static XiaomiProto.Command echoLocations(XiaomiProto.WeatherLocations configured) {
        var copy = XiaomiProto.WeatherLocations.newBuilder();
        for (var city : validCities(configured)) copy.addLocation(city);
        if (copy.getLocationCount() == 0) throw new IllegalArgumentException("WEATHER_CITY_SETUP_REQUIRED");
        return XiaomiProto.Command.newBuilder().setType(10).setSubtype(6)
                .setWeather(XiaomiProto.Weather.newBuilder().setLocations(copy)).build();
    }

    private static java.util.ArrayList<XiaomiProto.WeatherLocation> validCities(
            XiaomiProto.WeatherLocations configured) {
        var valid = new java.util.ArrayList<XiaomiProto.WeatherLocation>();
        if (configured == null) return valid;
        for (var city : configured.getLocationList()) {
            if (city.getCode().matches("weathercn:[0-9]{9}") && city.hasName()
                    && !city.getName().isBlank() && city.getName().length() <= 80) valid.add(city);
        }
        return valid;
    }

    private static Sample copy(Sample sample, String code, String cityName, String locationName) {
        return new Sample(code, cityName, locationName, sample.timezone(), sample.unit(),
                sample.publishedAtMs(), sample.conditionCode(), sample.temperature(),
                sample.humidityPercent(), sample.aqi(), sample.windPower(), sample.windDegree(),
                sample.uvIndex(), sample.pressure(), sample.daily(), sample.hourly());
    }




    public static List<XiaomiProto.Command> encode(Sample sample) {
        validateForecast(sample);
        ZoneId zone = zone(sample.timezone());
        int condition = mapCondition(sample.conditionCode());
        var metadata = XiaomiProto.WeatherMetadata.newBuilder()
                .setPublicationTimestamp(format(sample.publishedAtMs(), zone))
                .setCityName(sample.cityName()).setLocationName(sample.locationName())
                .setLocationKey(sample.locationKey()).setIsCurrentLocation(true).build();
        String symbol = "\u2103";
        var current = XiaomiProto.WeatherCurrent.newBuilder().setMetadata(metadata)
                .setWeatherCondition(condition)
                .setTemperature(XiaomiProto.WeatherUnitValue.newBuilder()
                        .setUnit(symbol).setValue(celsius(sample.temperature(), sample.unit())));
        if (sample.humidityPercent() != null) current.setHumidity(measurement(sample.humidityPercent(), "%"));
        if (sample.aqi() != null) current.setAqi(measurement(sample.aqi(), ""));
        if (sample.windPower() != null && sample.windDegree() != null) {
            current.setWind(measurement(sample.windPower(), String.format(java.util.Locale.ROOT, "%.1f",
                    sample.windDegree().doubleValue())));
        }
        if (sample.uvIndex() != null) current.setUv(measurement(sample.uvIndex(), ""));
        if (sample.pressure() != null) current.setPressure((float) (sample.pressure() >= 80_000
                ? sample.pressure() : sample.pressure() * 100));
        current.setWarning(XiaomiProto.WeatherWarnings.newBuilder());
        var commands = new ArrayList<XiaomiProto.Command>(3);
        commands.add(XiaomiProto.Command.newBuilder().setType(10).setSubtype(0)
                .setWeather(XiaomiProto.Weather.newBuilder().setCurrent(current)).build());
        var daily = dailyEntries(sample, zone, symbol);
        if (daily != null) commands.add(forecastCommand(1, metadata, daily));
        var hourly = hourlyEntries(sample, zone);
        if (hourly != null) commands.add(forecastCommand(2, metadata, hourly));
        return List.copyOf(commands);
    }

    private static XiaomiProto.Command forecastCommand(int subtype, XiaomiProto.WeatherMetadata metadata,
                                                       XiaomiProto.ForecastEntries entries) {
        return XiaomiProto.Command.newBuilder().setType(10).setSubtype(subtype)
                .setWeather(XiaomiProto.Weather.newBuilder().setForecast(
                        XiaomiProto.WeatherForecast.newBuilder().setMetadata(metadata).setEntries(entries))).build();
    }

    private static XiaomiProto.ForecastEntries dailyEntries(Sample sample, ZoneId zone, String symbol) {
        var expected = Instant.ofEpochMilli(sample.publishedAtMs()).atZone(zone).toLocalDate();
        var entries = XiaomiProto.ForecastEntries.newBuilder();
        for (Day day : sample.daily()) {
            var date = Instant.ofEpochMilli(day.timeMs()).atZone(zone).toLocalDate();
            if (entries.getEntryCount() == 0 && date.isBefore(expected)) continue;
            // Entries carry no timestamp: a gap would shift every later row on the band.
            if (!date.equals(expected)) return null;
            var entry = XiaomiProto.ForecastEntry.newBuilder()
                    .setTemperatureRange(XiaomiProto.WeatherRange.newBuilder()
                            .setFrom(celsius(day.maxTemp(), sample.unit()))
                            .setTo(celsius(day.minTemp(), sample.unit())))
                    .setTemperatureSymbol(symbol);
            if (sample.aqi() != null) entry.setAqi(measurement(sample.aqi(), ""));
            int dayCondition = WeatherConditionMap.fromOHealth(day.dayCode());
            int nightCondition = WeatherConditionMap.fromOHealth(day.nightCode());
            if (dayCondition >= 0 && nightCondition >= 0) {
                entry.setConditionRange(XiaomiProto.WeatherRange.newBuilder()
                        .setFrom(dayCondition).setTo(nightCondition));
            }
            if (day.sunriseMs() != null && day.sunsetMs() != null) {
                entry.setSunriseSunset(XiaomiProto.WeatherSunriseSunset.newBuilder()
                        .setSunrise(format(day.sunriseMs(), zone)).setSunset(format(day.sunsetMs(), zone)));
            }
            entries.addEntry(entry);
            if (entries.getEntryCount() == 7) break;
            expected = expected.plusDays(1);
        }
        return entries.getEntryCount() == 0 ? null : entries.build();
    }

    private static XiaomiProto.ForecastEntries hourlyEntries(Sample sample, ZoneId zone) {
        var expected = Instant.ofEpochMilli(sample.publishedAtMs()).atZone(zone).truncatedTo(ChronoUnit.HOURS);
        var entries = XiaomiProto.ForecastEntries.newBuilder();
        for (Hour hour : sample.hourly()) {
            var time = Instant.ofEpochMilli(hour.timeMs()).atZone(zone).truncatedTo(ChronoUnit.HOURS);
            if (entries.getEntryCount() == 0 && time.isBefore(expected)) continue;
            if (entries.getEntryCount() == 0 && time.equals(expected.plusHours(1))) {
                entries.addEntry(observedHour(sample, sample.conditionCode(), sample.temperature()));
                expected = expected.plusHours(1);
            }
            // Entries carry no timestamp: an internal gap would shift every later row.
            if (!time.equals(expected)) return null;
            entries.addEntry(observedHour(sample, hour.conditionCode(), hour.temperature()));
            if (entries.getEntryCount() == 23) break;
            expected = expected.plusHours(1);
        }
        return entries.getEntryCount() == 0 ? null : entries.build();
    }

    private static XiaomiProto.ForecastEntry observedHour(Sample sample, int conditionCode, double temperature) {
        var entry = XiaomiProto.ForecastEntry.newBuilder()
                .setTemperatureRange(XiaomiProto.WeatherRange.newBuilder()
                        .setFrom(0).setTo(celsius(temperature, sample.unit())));
        int condition = WeatherConditionMap.fromOHealth(conditionCode);
        if (condition >= 0) entry.setConditionRange(XiaomiProto.WeatherRange.newBuilder().setFrom(0).setTo(condition));
        if (sample.aqi() != null) entry.setAqi(measurement(sample.aqi(), ""));
        if (sample.windPower() != null && sample.windDegree() != null) {
            entry.setWind(measurement(sample.windPower(), String.format(java.util.Locale.ROOT, "%.1f",
                    sample.windDegree().doubleValue())));
        }
        return entry.build();
    }


    private static XiaomiProto.WeatherUnitValue measurement(int value, String unit) {
        return XiaomiProto.WeatherUnitValue.newBuilder().setUnit(unit).setValue(value).build();
    }
    private static int mapCondition(int oHealthCode) {
        int mapped = WeatherConditionMap.fromOHealth(oHealthCode);
        if (mapped < 0) throw new IllegalArgumentException("UNKNOWN_WEATHER_CONDITION");
        return mapped;
    }
    private static int celsius(double value, String unit) {
        double celsius = "f".equals(unit) ? (value - 32) * 5 / 9 : value;
        if (!Double.isFinite(celsius) || celsius < -90 || celsius > 60) {
            throw new IllegalArgumentException("WEATHER_TEMPERATURE_INVALID");
        }
        return (int) Math.round(celsius);
    }
    private static ZoneId zone(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 64) throw new IllegalArgumentException("WEATHER_ZONE_MISSING");
        try {
            double hours = Double.parseDouble(raw);
            if (!Double.isFinite(hours) || Math.abs(hours) > 18) throw new IllegalArgumentException("WEATHER_ZONE_INVALID");
            return ZoneOffset.ofTotalSeconds((int) Math.round(hours * 3600));
        } catch (NumberFormatException nonNumeric) {
            try { return ZoneId.of(raw); }
            catch (Exception invalid) { throw new IllegalArgumentException("WEATHER_ZONE_INVALID"); }
        }
    }
    private static void checkTime(long epochMs) {
        if (epochMs < 1_000_000_000_000L || epochMs > 4_102_444_800_000L) {
            throw new IllegalArgumentException("WEATHER_TIME_INVALID");
        }
    }
    private static String format(long epochMs, ZoneId zone) {
        checkTime(epochMs);
        return BAND_TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone));
    }
}
