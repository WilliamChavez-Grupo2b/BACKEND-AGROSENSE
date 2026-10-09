package com.agrosense.backend.repository;

import com.agrosense.backend.models.Cultivate;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface CultivateRepository
    extends JpaRepository <Cultivate, Integer>{
    List<Cultivate> findByActiveTrue();
    List<Cultivate> findByFinca_IdEstate(Integer idEstate);
    }