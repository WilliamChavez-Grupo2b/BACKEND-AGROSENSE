package com.agrosense.backend.service;

import com.agrosense.backend.dto.response.DeviceResponse;
import com.agrosense.backend.exception.ApiException;
import com.agrosense.backend.exception.BusinessRuleException;
import com.agrosense.backend.exception.ResourceNotFoundException;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Device;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DeviceService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeviceRepository deviceRepository;
    private final CropRepository cropRepository;

    @Transactional
    public DeviceResponse create(String email, Integer cropId, String deviceCode) {
        Crop crop = cropRepository.findByIdCropAndEstateUserEmail(cropId, email)
                .orElseThrow(() -> new ResourceNotFoundException("Crop not found"));
        String code = normalize(deviceCode);
        if (deviceRepository.existsByDeviceCode(code)) {
            throw new BusinessRuleException("Ya existe un dispositivo con ese código.");
        }
        String key = newKey();
        Device device = deviceRepository.save(Device.builder().crop(crop).deviceCode(code).keyHash(hash(key)).build());
        return DeviceResponse.from(device, key);
    }

    /** 401 without a key, 403 with a wrong one or an unknown device (the same, so codes cannot be probed), 409 inactive. */
    @Transactional(readOnly = true)
    public Device require(String deviceCode, String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Falta la llave del dispositivo.");
        }
        byte[] sent = hash(key.trim()).getBytes(StandardCharsets.US_ASCII);
        Device device = (deviceCode == null || deviceCode.isBlank() ? Optional.<Device>empty()
                : deviceRepository.findByDeviceCode(normalize(deviceCode)))
                .filter(known -> MessageDigest.isEqual(sent, known.getKeyHash().trim().getBytes(StandardCharsets.US_ASCII)))
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "Dispositivo desconocido o llave incorrecta."));
        if (!Boolean.TRUE.equals(device.getActive())) {
            throw new ApiException(HttpStatus.CONFLICT, "El dispositivo está inactivo.");
        }
        return device;
    }

    /**
     * A device trusted by configuration rather than by its key: the ESP32 on its own local network, read over TCP
     * (ESP32_DEVICE_ID). 404 when the code is unknown, 409 when the device is inactive.
     */
    @Transactional(readOnly = true)
    public Device requireConfigured(String deviceCode) {
        Device device = (deviceCode == null || deviceCode.isBlank() ? Optional.<Device>empty()
                : deviceRepository.findByDeviceCode(normalize(deviceCode)))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "No existe el dispositivo configurado '" + deviceCode + "'."));
        if (!Boolean.TRUE.equals(device.getActive())) {
            throw new ApiException(HttpStatus.CONFLICT, "El dispositivo está inactivo.");
        }
        return device;
    }

    @Transactional
    public boolean setKey(String deviceCode, String key) {
        return deviceRepository.findByDeviceCode(normalize(deviceCode))
                .map(device -> {
                    device.setKeyHash(hash(key.trim()));
                    return true;
                })
                .orElse(false);
    }

    public static String normalize(String deviceCode) {
        return deviceCode.trim().toUpperCase(Locale.ROOT);
    }

    static String newKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    public static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", impossible);
        }
    }
}
