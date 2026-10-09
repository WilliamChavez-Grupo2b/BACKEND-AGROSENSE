package com.agrosense.backend.repository;

import com.agrosense.backend.models.Estate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EstateRepository extends JpaRepository<Estate, Integer> {
}
