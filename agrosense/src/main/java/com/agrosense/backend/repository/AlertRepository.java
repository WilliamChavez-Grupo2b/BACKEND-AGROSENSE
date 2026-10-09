package com.agrosense.backend.repository;

import com.agrosense.backend.models.Alert;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRepository extends JpaRepository<Alert, Integer> {
}
