package com.agrosense.backend.repository;

import com.agrosense.backend.domain.enums.DispatchStatus;
import com.agrosense.backend.models.IrrigationDispatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface IrrigationDispatchRepository extends JpaRepository<IrrigationDispatch, Integer> {

    Optional<IrrigationDispatch> findFirstByCropIdAndStatusOrderByIdDispatchDesc(Integer cropId, DispatchStatus status);

    List<IrrigationDispatch> findByCropIdAndStatus(Integer cropId, DispatchStatus status);

    /** Only one caller can change a row from PENDING, so an order is never handed out twice. */
    @Modifying
    @Query("update IrrigationDispatch d set d.status = com.agrosense.backend.domain.enums.DispatchStatus.DELIVERED, "
            + "d.deliveredAt = :now where d.idDispatch = :id "
            + "and d.status = com.agrosense.backend.domain.enums.DispatchStatus.PENDING")
    int claim(@Param("id") Integer id, @Param("now") LocalDateTime now);
}
