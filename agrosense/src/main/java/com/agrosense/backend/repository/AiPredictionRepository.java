package com.agrosense.backend.repository;

import com.agrosense.backend.models.AiPrediction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiPredictionRepository extends JpaRepository<AiPrediction, Integer> {
}
