package com.agrosense.backend.repository;

import com.agrosense.backend.models.Crop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CropRepository extends JpaRepository<Crop, Integer> {

    List<Crop> findByActiveTrue();

    List<Crop> findByEstateIdEstate(Integer idEstate);

    long countByEstateUserEmailAndActiveTrue(String email);
}
