package com.agrosense.backend.service;

import com.agrosense.backend.dto.response.DeviceResponse;
import com.agrosense.backend.exception.BusinessRuleException;
import com.agrosense.backend.exception.ResourceNotFoundException;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Device;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
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

/**
 * Field devices and their keys. A key is 32 random bytes, given to the user once; only its SHA-256 is stored,
 * so a copy of the database does not let anyone send events in a device's name.
 */
@Service
@RequiredArgsConstructor
public class DeviceService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeviceRepository deviceRepository;
    private final CropRepository cropRepository;

    /** Registers a device on one of the user's crops and returns it with its key, which is not kept. */
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

    /**
     * The active device with this code whose key is the one given; empty for any other case, without saying
     * which, so a caller cannot tell an unknown device from a wrong key.
     */
    @Transactional(readOnly = true)
    public Optional<Device> authenticate(String deviceCode, String key) {
        if (deviceCode == null || key == null || deviceCode.isBlank() || key.isBlank()) {
            return Optional.empty();
        }
        byte[] sent = hash(key.trim()).getBytes(StandardCharsets.US_ASCII);
        return deviceRepository.findByDeviceCode(normalize(deviceCode))
                .filter(Device::getActive)
                // Compared in constant time, so the stored hash cannot be found out byte by byte.
                .filter(device -> MessageDigest.isEqual(sent, device.getKeyHash().trim().getBytes(StandardCharsets.US_ASCII)));
    }

    /** Gives an existing device a new key; used to make the demo device usable with a known key. */
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

    /** SHA-256 of the key, as 64 lower-case hexadecimal characters. */
    public static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", impossible);
        }
    }
}
