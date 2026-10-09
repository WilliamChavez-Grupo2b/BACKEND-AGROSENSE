package com.agrosense.backend.repository;

import com.agrosense.backend.models.Irrigation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IrrigationRepository extends JpaRepository<Irrigation, Integer> {

    List<Irrigation> findByCropIdCropOrderByStartedAtDesc(Integer idCrop);

    List<Irrigation> findByCropIdCropOrderByStartedAtDesc(Integer idCrop, Pageable pageable);
}
