package com.agrosense.backend.repository;

import com.agrosense.backend.models.Crop;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CropRepository extends JpaRepository<Crop, Integer> {
}
