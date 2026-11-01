package com.agrosense.backend.models;

import com.agrosense.backend.domain.enums.DispatchStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** An irrigation order on its way to the device: what was asked, whether it arrived and what the device did. */
@Entity
@Table(name = "irrigation_dispatches")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IrrigationDispatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_dispatch")
    private Integer idDispatch;

    @Column(name = "id_crop", nullable = false)
    private Integer cropId;

    @Column(nullable = false)
    private Integer minutes;

    @Column(nullable = false, length = 16)
    private String source;

    @Column(precision = 5, scale = 4)
    private BigDecimal confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private DispatchStatus status = DispatchStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;
}
