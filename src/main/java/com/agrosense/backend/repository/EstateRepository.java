package com.agrosense.backend.repository;

import com.agrosense.backend.models.Estate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EstateRepository extends JpaRepository<Estate, Integer> {

    List<Estate> findByUserIdUser(Integer idUser);

    List<Estate> findByUserEmailOrderByNameAsc(String email);

    Optional<Estate> findByIdEstateAndUserEmail(Integer idEstate, String email);
}
