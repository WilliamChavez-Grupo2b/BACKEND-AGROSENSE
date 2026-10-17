package com.agrosense.backend.repository;

import com.agrosense.backend.models.Device;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Integer> {

    /** Loads the device with its crop and owner, which an incoming event needs to be stored and delivered. */
    @EntityGraph(attributePaths = {"crop", "crop.estate", "crop.estate.user"})
    Optional<Device> findByDeviceCode(String deviceCode);

    boolean existsByDeviceCode(String deviceCode);
}
