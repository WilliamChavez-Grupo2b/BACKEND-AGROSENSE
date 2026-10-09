package com.agrosense.backend.repository;

import com.agrosense.backend.models.Estate;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EstateRepository 
    extends JpaRepository<Estate, Integer>{

    List<Estate> findbyUser_idUser(Integer idUser);
    }
