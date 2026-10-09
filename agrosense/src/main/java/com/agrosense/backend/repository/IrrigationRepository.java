package com.agrosense.backend.repository;

import com.agrosense.backend.domain.models.Irrigation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface IrrigationRepository
        extends JpaRepository<Irrigation, Integer> {

    List<Irrigation> findByCultivo_IdCultivoOrderByInicioDesc(Integer idCultive);
}