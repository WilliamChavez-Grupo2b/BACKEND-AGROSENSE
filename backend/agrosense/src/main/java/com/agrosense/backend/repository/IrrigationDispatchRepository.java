package com.agrosense.backend.repository;

import com.agrosense.backend.domain.enums.DispatchStatus;
import com.agrosense.backend.models.IrrigationDispatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface IrrigationDispatchRepository extends JpaRepository<IrrigationDispatch, Integer> {

    Optional<IrrigationDispatch> findFirstByCropIdAndStatusOrderByIdDispatchDesc(Integer cropId, DispatchStatus status);

    @Modifying(clearAutomatically = true)
    @Query("update IrrigationDispatch d set d.status = com.agrosense.backend.domain.enums.DispatchStatus.EXPIRED "
            + "where d.cropId = :cropId and d.status = com.agrosense.backend.domain.enums.DispatchStatus.PENDING "
            + "and d.expiresAt <= :limit")
    int expire(@Param("cropId") Integer cropId, @Param("limit") LocalDateTime limit);

    /** Only one caller can change a row from PENDING, so an order is never handed out twice. */
    @Modifying(clearAutomatically = true)
    @Query("update IrrigationDispatch d set d.status = com.agrosense.backend.domain.enums.DispatchStatus.DELIVERED, "
            + "d.deliveredAt = :now where d.idDispatch = :id "
            + "and d.status = com.agrosense.backend.domain.enums.DispatchStatus.PENDING")
    int claim(@Param("id") Integer id, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("update IrrigationDispatch d set d.status = :result, d.confirmedAt = :now where d.idDispatch = :id "
            + "and d.cropId = :cropId and d.status = com.agrosense.backend.domain.enums.DispatchStatus.DELIVERED")
    int confirm(@Param("id") Integer id, @Param("cropId") Integer cropId, @Param("result") DispatchStatus result,
            @Param("now") LocalDateTime now);
}
