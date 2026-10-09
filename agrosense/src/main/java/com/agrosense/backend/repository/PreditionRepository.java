package com.agrosense.backend.repository;

import com.agrosense.backend.domain.models.PreditionIA;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PreditionRepository
        extends JpaRepository<PreditionIA, Integer> {

    List<PreditionIA> findByCultivo_IdCultivoOrderByCreadoEnDesc(
            Integer idCultive, Pageable pageable);
}