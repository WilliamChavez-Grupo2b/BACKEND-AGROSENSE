package com.agrosense.backend.repository;

import com.agrosense.backend.models.Crop;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CropRepository extends JpaRepository<Crop, Integer> {

    List<Crop> findByActiveTrue();

    List<Crop> findByEstateIdEstate(Integer idEstate);

    Optional<Crop> findByIdCropAndEstateUserEmail(Integer idCrop, String email);

    List<Crop> findByEstateIdEstateAndActiveTrueOrderByNameAsc(Integer idEstate);

    List<Crop> findByEstateUserEmailAndActiveTrueOrderByNameAsc(String email);

    long countByEstateUserEmailAndActiveTrue(String email);

    /** Locks the crop's row until the transaction ends, so concurrent work on one crop runs in turn. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Crop c where c.idCrop = :idCrop")
    Optional<Crop> findByIdForUpdate(@Param("idCrop") Integer idCrop);
}
