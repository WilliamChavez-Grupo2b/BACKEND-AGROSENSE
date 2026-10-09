package com.agrosense.backend.pattern.creational.factory;

import com.agrosense.backend.domain.enums.Sensor_Type;
import com.agrosense.backend.models.Cultive;
import com.agrosense.backend.models.Sensor;
import org.springframework.stereotype.Component;

@Component
public class SensorFactory {

    /**
     * Creates a configured Sensor based on its type.
     * Each type has different default units and configuration.
     */
    public Sensor create(Sensor_Type type,
                         String Sensorcode,
                         String Location,
                         Cultive cultive) {
        return switch (type) {
            case Soil_Moisture -> createSoilMoisture(Sensorcode, Location, cultive);
            case Air_Temperature -> createAirTemperature(Sensorcode, Location, cultive);
            case Soil_Temperature -> createSoilTemperature(Sensorcode, Location, cultive);
            case pH -> createPh(Sensorcode, Location, cultive);
            case Conductivity -> createConductivity(Sensorcode, Location, cultive);
            case Light -> createLight(Sensorcode, Location, cultive);
            case Rain_Gauge -> createRainGauge(Sensorcode, Location, cultive);
            case Relative_Humidity -> createRelativeHumidity(Sensorcode, Location, cultive);
        };
    }

    private Sensor createSoilMoisture(String Sensorcode, String Location, Cultive cultive) {
        return Sensor.builder()
                .Sensorcode(Sensorcode)
                .typesensor(Sensor_Type.Soil_Moisture)
                .Location(Location)
                .cultive(cultive)
                .activo(true)
                .build();
    }

    private Sensor createAirTemperature(String Sensorcode, String Location, Cultive cultive) {
        return Sensor.builder()
                .Sensorcode(Sensorcode)
                .typesensor(Sensor_Type.Air_Temperature)
                .Location(Location)
                .cultive(cultive)
                .activo(true)
                .build();
    }

    private Sensor createSoilTemperature(String Sensorcode, String Location, Cultive cultive) {
        return Sensor.builder()
                .Sensorcode(Sensorcode)
                .typesensor(Sensor_Type.Soil_Temperature)
                .Location(Location)
                .cultive(cultive)
                .activo(true)
                .build();
    }

    private Sensor createPh(String Sensorcode, String Location, Cultive cultive) {
        return Sensor.builder()
                .Sensorcode(Sensorcode)
                .typesensor(Sensor_Type.pH)
                .Location(Location)
                .cultive(cultive)
                .activo(true)
                .build();
    }

    private Sensor createConductivity(String Sensorcode, String Location, Cultive cultive) {
        return Sensor.builder()
                .Sensorcode(Sensorcode)
                .typesensor(Sensor_Type.Conductivity)
                .Location(Location)
                .cultive(cultive)
                .activo(true)
                .build();
    }

    private Sensor createLight(String Sensorcode, String Location, Cultive cultive) {
        return Sensor.builder()
                .Sensorcode(Sensorcode)
                .typesensor(Sensor_Type.Light)
                .Location(Location)
                .cultive(cultive)
                .activo(true)
                .build();
    }

    private Sensor createRainGauge(String Sensorcode, String Location, Cultive cultive) {
        return Sensor.builder()
                .Sensorcode(Sensorcode)
                .typesensor(Sensor_Type.Rain_Gauge)
                .Location(Location)
                .cultive(cultive)
                .activo(true)
                .build();
    }

    private Sensor createRelativeHumidity(String Sensorcode, String Location, Cultive cultive) {
        return Sensor.builder()
                .Sensorcode(Sensorcode)
                .typesensor(Sensor_Type.Relative_Humidity)
                .Location(Location)
                .cultive(cultive)
                .activo(true)
                .build();
    }
}