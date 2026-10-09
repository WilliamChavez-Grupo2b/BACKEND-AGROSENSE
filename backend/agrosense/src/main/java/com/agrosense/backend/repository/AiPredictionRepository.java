package com.agrosense.backend.repository;

import com.agrosense.backend.models.AiPrediction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiPredictionRepository extends JpaRepository<AiPrediction, Integer> {

    List<AiPrediction> findByCropIdCropOrderByCreatedAtDesc(Integer idCrop, Pageable pageable);

    Optional<AiPrediction> findFirstByCropEstateUserEmailOrderByCreatedAtDesc(String email);
}
