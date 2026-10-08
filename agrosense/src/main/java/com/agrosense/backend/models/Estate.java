package com.agrosense.backend.models;

import jakarta.persistance.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List; 

@Entify
@Table(name="fincas")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Estate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTIFY)
    private Integer idEstate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_user", nullable = false)
    private User User;

    @Column(nullable = false)
    private String name; 

    private String Location; 

    @Column(precision= 10, scale = 7)
    private BigDecimal latitude 

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name= "area-ha", precision= 10, scale=2 )
    private BigDecimal areaHa;

}