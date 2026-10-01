// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.protocol;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class BandWeatherEncoderTest {
    private static final long EPOCH = Instant.parse("2026-09-23T18:32:06Z").toEpochMilli();
    private static final String BAND_CODE = "weathercn:000000001";

    @Test public void sendingWeatherNeverReplacesConfiguredCitiesOrInventsMetrics() {
        var frames = BandWeatherEncoder.encode(sample("c", "Asia/Shanghai", 56, 21));
        assertEquals(List.of(0, 1, 2), frames.stream().map(XiaomiProto.Command::getSubtype).toList());
        for (var frame : frames) {
            assertEquals(10, frame.getType());
            assertFalse(frame.getWeather().hasLocations());
        }
        var current = frame(frames, 0).getWeather().getCurrent();
        assertEquals(1, current.getWeatherCondition());
        assertEquals(21, current.getTemperature().getValue());
        assertEquals("℃", current.getTemperature().getUnit());
        assertFalse(current.hasAqi());
        assertFalse(current.hasWind());
        assertFalse(current.hasHumidity());
        assertFalse(current.hasPressure());
        var day = entries(frames, 1).getEntry(0);
        assertEquals("2026-09-24T06:00:00+08:00", day.getSunriseSunset().getSunrise());
        assertEquals("2026-09-24T18:00:00+08:00", day.getSunriseSunset().getSunset());
        var hour = entries(frames, 2).getEntry(0);
        assertFalse(hour.hasWind());
        assertFalse(hour.hasAqi());
        assertFalse(hour.hasSunriseSunset());
    }

    @Test public void fahrenheitConvertsExactlyOnceAcrossCurrentAndForecasts() {
        var frames = BandWeatherEncoder.encode(sample("f", "+05:30", 56, 69.8));
        assertEquals(21, frame(frames, 0).getWeather().getCurrent().getTemperature().getValue());
        assertEquals(26, entries(frames, 1).getEntry(0).getTemperatureRange().getFrom());
        assertEquals(21, entries(frames, 1).getEntry(0).getTemperatureRange().getTo());
        assertEquals(21, entries(frames, 2).getEntry(0).getTemperatureRange().getTo());
        assertEquals("2026-09-24T00:02:06+05:30",
                frame(frames, 0).getWeather().getCurrent().getMetadata().getPublicationTimestamp());
    }

    @Test public void unsupportedCurrentConditionsRejectTheUpdate() {
        for (int code : new int[] {0, 21, 70, 71, -1, 72}) {
            assertEquals("UNKNOWN_WEATHER_CONDITION", assertThrows(IllegalArgumentException.class,
                    () -> BandWeatherEncoder.encode(sample("c", "Asia/Shanghai", code, 21))).getMessage());
        }
    }

    @Test public void unknownForecastConditionsPreserveRowsAndTemperatures() {
        var source = sample("c", "Asia/Shanghai", 56, 21);
        var days = new ArrayList<>(source.daily());
        var original = days.get(1);
        days.set(1, new BandWeatherEncoder.Day(original.timeMs(), 56, 21, 17, 24, null, null));
        var hours = new ArrayList<>(source.hourly());
        hours.set(1, new BandWeatherEncoder.Hour(hours.get(1).timeMs(), 70, 18));
        var frames = BandWeatherEncoder.encode(withForecasts(source, days, hours));
        var daily = entries(frames, 1);
        assertEquals(7, daily.getEntryCount());
        assertFalse(daily.getEntry(1).hasConditionRange());
        assertEquals(17, daily.getEntry(1).getTemperatureRange().getTo());
        assertEquals(24, daily.getEntry(1).getTemperatureRange().getFrom());
        assertFalse(daily.getEntry(1).hasSunriseSunset());
        assertTrue(daily.getEntry(2).hasConditionRange());
        var hourly = entries(frames, 2);
        assertEquals(23, hourly.getEntryCount());
        assertFalse(hourly.getEntry(1).hasConditionRange());
        assertEquals(18, hourly.getEntry(1).getTemperatureRange().getTo());
        assertTrue(hourly.getEntry(2).hasConditionRange());
    }

    @Test public void pastRowsAreTrimmedInCityTimezoneWithoutChangingPublication() {
        // Publication is already Sep 24 in this city, but remains Sep 23 in UTC.
        var source = sample("c", "Asia/Shanghai", 56, 21);
        var days = new ArrayList<>(source.daily());
        long previousDay = days.get(0).timeMs() - 86_400_000L;
        days.add(0, new BandWeatherEncoder.Day(previousDay, 54, 54, -5, -1, null, null));
        var hours = new ArrayList<>(source.hourly());
        hours.add(0, new BandWeatherEncoder.Hour(hours.get(0).timeMs() - 3_600_000L, 54, -5));
        var frames = BandWeatherEncoder.encode(withForecasts(source, days, hours));
        assertEquals(7, entries(frames, 1).getEntryCount());
        assertEquals(21, entries(frames, 1).getEntry(0).getTemperatureRange().getTo());
        assertEquals(23, entries(frames, 2).getEntryCount());
        assertEquals(21, entries(frames, 2).getEntry(0).getTemperatureRange().getTo());
        assertEquals("2026-09-24T02:32:06+08:00",
                frame(frames, 1).getWeather().getForecast().getMetadata().getPublicationTimestamp());
        assertEquals("2026-09-24T02:32:06+08:00",
                frame(frames, 2).getWeather().getForecast().getMetadata().getPublicationTimestamp());
    }

    @Test public void missingFirstOrInternalDayDoesNotShiftRemainingDays() {
        var source = sample("c", "Asia/Shanghai", 56, 21);
        for (int missing : new int[] {0, 2}) {
            var days = new ArrayList<>(source.daily());
            days.remove(missing);
            var frames = BandWeatherEncoder.encode(withForecasts(source, days, source.hourly()));
            assertEquals(List.of(0, 2), frames.stream().map(XiaomiProto.Command::getSubtype).toList());
            assertEquals(21, frame(frames, 0).getWeather().getCurrent().getTemperature().getValue());
        }
    }

    @Test public void missingFirstHourUsesTheLiveReadingAndAnInternalGapDropsTheFrame() {
        var source = sample("c", "Asia/Shanghai", 56, 21);
        var leading = new ArrayList<>(source.hourly());
        leading.remove(0);
        var frames = BandWeatherEncoder.encode(withForecasts(source, source.daily(), leading));
        assertEquals(List.of(0, 1, 2), frames.stream().map(XiaomiProto.Command::getSubtype).toList());
        var hourly = entries(frames, 2);
        assertEquals(23, hourly.getEntryCount());
        assertEquals(21, hourly.getEntry(0).getTemperatureRange().getTo());
        assertEquals(1, hourly.getEntry(0).getConditionRange().getTo());
        var internal = new ArrayList<>(source.hourly());
        internal.remove(2);
        assertEquals(List.of(0, 1), BandWeatherEncoder.encode(withForecasts(source, source.daily(), internal))
                .stream().map(XiaomiProto.Command::getSubtype).toList());
        assertEquals(List.of(0), BandWeatherEncoder.encode(withForecasts(source, List.of(), List.of()))
                .stream().map(XiaomiProto.Command::getSubtype).toList());
    }


    @Test public void forecastsStopAtWireLimitsEvenWhenSourceHasMoreRows() {
        var source = sample("c", "Asia/Shanghai", 56, 21);
        var days = new ArrayList<>(source.daily());
        days.add(new BandWeatherEncoder.Day(days.get(6).timeMs() + 86_400_000L, 54, 54, 10, 15, null, null));
        var hours = new ArrayList<>(source.hourly());
        hours.add(new BandWeatherEncoder.Hour(hours.get(22).timeMs() + 3_600_000L, 54, 10));
        var frames = BandWeatherEncoder.encode(withForecasts(source, days, hours));
        assertEquals(7, entries(frames, 1).getEntryCount());
        assertEquals(23, entries(frames, 2).getEntryCount());
    }

    @Test public void explicitBindingRejectsChangesToEachOfFiveIdentityFields() {
        var source = sample("c", "Asia/Shanghai", 56, 21);
        var configured = city(BAND_CODE, "Band city");
        String[] confirmed = {source.locationKey(), source.cityName(), source.locationName(), BAND_CODE, "Band city"};
        var bound = BandWeatherEncoder.bindExplicitCity(source, confirmed[0], confirmed[1], confirmed[2],
                confirmed[3], confirmed[4], configured);
        assertEquals(BAND_CODE, bound.locationKey());
        for (int changed = 0; changed < confirmed.length; changed++) {
            var stale = confirmed.clone();
            stale[changed] = changed == 3 ? "weathercn:000000002" : "changed";
            assertThrows(IllegalArgumentException.class, () -> BandWeatherEncoder.bindExplicitCity(source,
                    stale[0], stale[1], stale[2], stale[3], stale[4], configured));
        }
        assertThrows(IllegalArgumentException.class, () -> BandWeatherEncoder.bindExplicitCity(source,
                null, null, null, null, null, configured));
        assertThrows(IllegalArgumentException.class, () -> BandWeatherEncoder.bindExplicitCity(source,
                confirmed[0], confirmed[1], confirmed[2], confirmed[3], confirmed[4], null));
    }

    @Test public void oneConfiguredCitySyncsWithoutAManualConfirmation() {
        var source = sample("c", "Asia/Shanghai", 56, 21);
        var configured = city(BAND_CODE, "Band city");
        var bound = BandWeatherEncoder.bindObservedCity(source, configured, "", "");
        assertEquals(BAND_CODE, bound.locationKey());
        assertEquals("Band city", bound.locationName());
        assertEquals("Source city", bound.cityName());
        var echoed = BandWeatherEncoder.echoLocations(configured);
        assertEquals(10, echoed.getType());
        assertEquals(6, echoed.getSubtype());
        assertEquals(1, echoed.getWeather().getLocations().getLocationCount());
        assertEquals(BAND_CODE, echoed.getWeather().getLocations().getLocation(0).getCode());
        assertEquals("Band city", echoed.getWeather().getLocations().getLocation(0).getName());
        var several = configured.toBuilder().addLocation(
                XiaomiProto.WeatherLocation.newBuilder().setCode("weathercn:000000002").setName("Other")).build();
        assertThrows(IllegalArgumentException.class,
                () -> BandWeatherEncoder.bindObservedCity(source, several, "", ""));
        assertEquals(2, BandWeatherEncoder.echoLocations(several).getWeather().getLocations().getLocationCount());
    }

    @Test public void oneNonWeathercnCityIsKept() {
        var source = sample("c", "Asia/Shanghai", 56, 21);
        var configured = city("accu:246810", "Band city");
        var bound = BandWeatherEncoder.bindObservedCity(source, configured, "", "");
        assertEquals("accu:246810", bound.locationKey());
        assertEquals("Band city", bound.locationName());
        var echoed = BandWeatherEncoder.echoLocations(configured);
        assertEquals(6, echoed.getSubtype());
        assertEquals("accu:246810", echoed.getWeather().getLocations().getLocation(0).getCode());
        assertEquals(0, BandWeatherEncoder.acceptedCityCount(city("not a code", "Band city")));
        assertEquals("WEATHER_CITY_SETUP_REQUIRED", assertThrows(IllegalArgumentException.class,
                () -> BandWeatherEncoder.echoLocations(city("not a code", "Band city"))).getMessage());
    }

    @Test public void emptyCityListRegistersTheSourceLocationWithoutReplacingCities() {
        var source = sample("c", "Asia/Shanghai", 56, 21);
        var generic = new BandWeatherEncoder.Sample("phone-location", source.cityName(), source.locationName(),
                source.timezone(), source.unit(), source.publishedAtMs(), source.conditionCode(),
                source.temperature(), null, null, null, null, null, null, source.daily(), source.hourly());
        var add = BandWeatherEncoder.addCurrentLocation(generic);
        assertEquals(10, add.getType());
        assertEquals(7, add.getSubtype());
        assertFalse(add.getWeather().hasLocations());
        assertEquals("phone-location", add.getWeather().getLocation().getCode());
        assertEquals("Source place", add.getWeather().getLocation().getName());
        assertEquals("WEATHER_CITY_SETUP_REQUIRED", assertThrows(IllegalArgumentException.class,
                () -> BandWeatherEncoder.echoLocations(XiaomiProto.WeatherLocations.getDefaultInstance()))
                .getMessage());
    }

    @Test public void observedHumidityAndAirQualityAreSentWithoutInventingEither() {
        var source = sample("c", "Asia/Shanghai", 56, 21);
        var measured = new BandWeatherEncoder.Sample(source.locationKey(), source.cityName(), source.locationName(),
                source.timezone(), source.unit(), source.publishedAtMs(), source.conditionCode(),
                source.temperature(), 94, 36, 3, 180, 2, 1013.0, source.daily(), source.hourly());
        var current = frame(BandWeatherEncoder.encode(measured), 0).getWeather().getCurrent();
        assertEquals(94, current.getHumidity().getValue());
        assertEquals("%", current.getHumidity().getUnit());
        assertEquals(36, current.getAqi().getValue());
        assertEquals("", current.getAqi().getUnit());
        assertEquals(3, current.getWind().getValue());
        assertEquals("180.0", current.getWind().getUnit());
        assertEquals(101300f, current.getPressure(), 0.1f);
        assertFalse(frame(BandWeatherEncoder.encode(source), 0).getWeather().getCurrent().hasHumidity());
        assertFalse(frame(BandWeatherEncoder.encode(source), 0).getWeather().getCurrent().hasWind());
    }

    @Test public void sourceLocationIsSentWhenTheBandHasNoConfiguredCity() {
        var source = sample("c", "Asia/Shanghai", 56, 21);
        var generic = new BandWeatherEncoder.Sample("phone-location", source.cityName(), source.locationName(),
                source.timezone(), source.unit(), source.publishedAtMs(), source.conditionCode(),
                source.temperature(), null, 36, null, null, null, null, source.daily(), source.hourly());
        var current = frame(BandWeatherEncoder.encode(generic), 0).getWeather().getCurrent();
        assertEquals("phone-location", current.getMetadata().getLocationKey());
        assertEquals("Source city", current.getMetadata().getCityName());
        assertEquals(36, current.getAqi().getValue());
        assertEquals("", current.getAqi().getUnit());
        var unbound = BandWeatherEncoder.bindObservedCity(generic,
                XiaomiProto.WeatherLocations.getDefaultInstance(), "", "");
        assertEquals("phone-location", unbound.locationKey());
        assertEquals("Source place", unbound.locationName());
    }

    private static XiaomiProto.WeatherLocations city(String code, String name) {
        return XiaomiProto.WeatherLocations.newBuilder().addLocation(
                XiaomiProto.WeatherLocation.newBuilder().setCode(code).setName(name)).build();
    }

    private static XiaomiProto.Command frame(List<XiaomiProto.Command> frames, int subtype) {
        return frames.stream().filter(command -> command.getSubtype() == subtype).findFirst().orElseThrow();
    }

    private static XiaomiProto.ForecastEntries entries(List<XiaomiProto.Command> frames, int subtype) {
        return frame(frames, subtype).getWeather().getForecast().getEntries();
    }

    private static BandWeatherEncoder.Sample withForecasts(BandWeatherEncoder.Sample source,
            List<BandWeatherEncoder.Day> days, List<BandWeatherEncoder.Hour> hours) {
        return new BandWeatherEncoder.Sample(source.locationKey(), source.cityName(), source.locationName(),
                source.timezone(), source.unit(), source.publishedAtMs(), source.conditionCode(),
                source.temperature(), source.humidityPercent(), source.aqi(), source.windPower(),
                source.windDegree(), source.uvIndex(), source.pressure(), days, hours);
    }

    private static BandWeatherEncoder.Sample sample(String unit, String zone, int condition, double temp) {
        var days = new ArrayList<BandWeatherEncoder.Day>();
        var hours = new ArrayList<BandWeatherEncoder.Hour>();
        var published = Instant.ofEpochMilli(EPOCH).atZone(ZoneId.of(zone));
        for (int i = 0; i < 7; i++) {
            var date = published.toLocalDate().plusDays(i);
            days.add(new BandWeatherEncoder.Day(date.atStartOfDay(published.getZone()).toInstant().toEpochMilli(),
                    56, 2, unit.equals("f") ? 69.8 : 21, unit.equals("f") ? 78.8 : 26,
                    date.atTime(6, 0).atZone(published.getZone()).toInstant().toEpochMilli(),
                    date.atTime(18, 0).atZone(published.getZone()).toInstant().toEpochMilli()));
        }
        for (int i = 0; i < 23; i++) {
            hours.add(new BandWeatherEncoder.Hour(published.truncatedTo(ChronoUnit.HOURS).plusHours(i)
                    .toInstant().toEpochMilli(), 56, unit.equals("f") ? 69.8 : 21));
        }
        return new BandWeatherEncoder.Sample(BAND_CODE, "Source city", "Source place", zone, unit,
                EPOCH, condition, temp, null, null, null, null, null, null, days, hours);
    }
}
