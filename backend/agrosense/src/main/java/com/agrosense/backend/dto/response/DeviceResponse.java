package com.agrosense.backend.dto.response;

import com.agrosense.backend.models.Device;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** A registered device. {@code deviceKey} is only present in the answer that registers it, and never again. */
@Data
@Builder
public class DeviceResponse {

    private Integer idDevice;
    private Integer idCrop;
    private String deviceCode;
    private Boolean active;
    private LocalDateTime createdAt;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String deviceKey;

    public static DeviceResponse from(Device device, String key) {
        return DeviceResponse.builder()
                .idDevice(device.getIdDevice())
                .idCrop(device.getCrop().getIdCrop())
                .deviceCode(device.getDeviceCode())
                .active(device.getActive())
                .createdAt(device.getCreatedAt())
                .deviceKey(key)
                .build();
    }
}
