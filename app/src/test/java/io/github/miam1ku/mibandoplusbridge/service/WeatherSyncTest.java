// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.service;

import io.github.miam1ku.mibandoplusbridge.protocol.BandWeatherEncoder;
import java.util.List;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WeatherSyncTest {
    @Test public void idlePollSkipsTheForecastTheBandAlreadyHas() {
        var forecast = sample(1_000);
        assertFalse(WeatherSync.transmitForecast(false, forecast, forecast));
        assertTrue(WeatherSync.transmitForecast(true, forecast, forecast));
        assertTrue(WeatherSync.transmitForecast(false, null, forecast));
        assertTrue(WeatherSync.transmitForecast(false, forecast, sample(2_000)));
    }

    @Test public void band8CityListStatusStillAllowsTheForecast() {
        var unsupported = XiaomiProto.Command.newBuilder().setType(10).setSubtype(5).setStatus(1).build();
        assertTrue(WeatherSync.unsupportedCityList(unsupported));
        assertFalse(WeatherSync.unsupportedCityList(unsupported.toBuilder().setStatus(0).build()));
        assertFalse(WeatherSync.unsupportedCityList(unsupported.toBuilder().setSubtype(6).build()));
    }

    private static BandWeatherEncoder.Sample sample(long publishedAtMs) {
        return new BandWeatherEncoder.Sample("weathercn:101110102", "城市", "地点", "Asia/Shanghai", "C",
                publishedAtMs, 1, 21, null, null, null, null, null, null, List.of(), List.of());
    }
}
