package com.agrosense.backend.repository;

import com.agrosense.backend.models.Cultivate;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface CultiveRepository
    extends JpaRepository <Cultive, Integer>{
    List<Cultive> findByActiveTrue();
    List<Cultive> findByFinca_IdEstate(Integer idEstate);
    }