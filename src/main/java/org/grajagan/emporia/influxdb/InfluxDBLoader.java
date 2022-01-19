package org.grajagan.emporia.influxdb;

/*-
 * #%L
 * Emporia Energy API Client
 * %%
 * Copyright (C) 2002 - 2020 Helge Weissig
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.grajagan.emporia.model.Channel;
import org.grajagan.emporia.model.Readings;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.BatchPoints;
import org.influxdb.dto.Point;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;

import java.net.URL;
import java.time.Instant;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;

@Data
@RequiredArgsConstructor
@Log4j2
public class InfluxDBLoader {

    private final URL influxDbUrl;
    private final String influxDbUser;
    private final String influxDbPassword;
    private final String influxDbName;

    private InfluxDB influxDB;

    @Setter(AccessLevel.PROTECTED)
    @Getter(AccessLevel.PROTECTED)
    private boolean isConnected = false;

    @Setter(AccessLevel.PROTECTED)
    @Getter(AccessLevel.PROTECTED)
    private BatchPoints batchPoints;

    private int writesCount = 0;

    private void connect() {
        influxDB = InfluxDBFactory.connect(influxDbUrl.toString(), influxDbUser, influxDbPassword);

        batchPoints = BatchPoints.database(influxDbName).retentionPolicy("autogen")
                .consistency(InfluxDB.ConsistencyLevel.ALL).build();

        isConnected = true;
    }

    public Readings load(Channel channel) {
        if (!isConnected) {
            connect();
        }
        String deviceName = getNameForChannel(channel);
        Query query =
                new Query("SELECT LAST(watts) AS watts FROM \"" + deviceName + "\" GROUP BY *",
                        influxDbName);
        QueryResult queryResult = influxDB.query(query);
        Readings readings = new Readings();
        readings.setChannel(channel);
        for (QueryResult.Result result : queryResult.getResults()) {
            if (result == null || result.getSeries() == null) {
                continue;
            }

            for (QueryResult.Series series : result.getSeries()) {
                if (series.getColumns().size() == 2 && series.getColumns().get(0).equals("time")
                        && series.getValues().size() == 1) {
                    String timeString = series.getValues().get(0).get(0).toString();
                    Instant time = Instant.parse(timeString);
                    float value = ((Double) series.getValues().get(0).get(1)).floatValue();
                    readings.setStart(time);
                    readings.setEnd(time);
                    readings.getUsageList().add(value);
                }
            }
        }

        return readings;
    }

    public void save(Readings readings) {
        if (!isConnected) {
            connect();
        }

        SortedMap<Instant, Float> data = readings.getDataPoints();
        String deviceName = getNameForChannel(readings.getChannel());
        String channelName = getNameForChannel(readings.getChannel());
        switch(channelName) {
            case "56528-1,2,3":
            channelName = "Main Panel";
            break;
            case "56528-2":
                channelName = "Oven";
                break;
            case "56528-4":
                channelName = "AC";
                break;
            case "56528-6":
                channelName = "Water Heater";
                break;
            case "56528-8":
                channelName = "Office + Kitchen Heating";
                break;
            case "56528-9":
                channelName = "Dryer";
                break;
            case "56528-11":
                channelName = "Dishwasher + Office";
                break;
            case "56528-13":
                channelName = "Pool";
                break;
            case "56528-15":
                channelName = "Fridge";
                break;
            default:
              // code block
          }
        
        // apparently, the math is done on the server!
        float multiplier = 1f; // readings.getChannel().getChannelMultiplier();

        for (Instant i : data.keySet()) {
            if (data.get(i) == null) {
                continue;
            }
            Point point =
                    Point.measurement("Emporia_energy_usage").time(i.toEpochMilli(), TimeUnit.MILLISECONDS)
                            .addField("watts", (int) (data.get(i) * multiplier * 100) / 100.0)
                            .tag("channel", channelName )
                            .build();

            log.trace("Created point: " + point.lineProtocol());
            batchPoints.point(point);

            if (batchPoints.getPoints().size() >= 30) {
                writeToDB();
            }
        }
    }

    private String getNameForChannel(Channel channel) {
        return channel.getDeviceGid() + "-" + channel.getChannelNum();
    }

    public void writeToDB() {
        if (batchPoints == null) {
            return;
        }

        log.trace("Writing " + batchPoints.getPoints().size() + " points!");
        try {
            influxDB.write(batchPoints);
            writesCount += batchPoints.getPoints().size();
            batchPoints.getPoints().clear();
        } catch (Exception e) {
            log.error("Error when uploading to InfluxDB", e);
        }
    }
}
