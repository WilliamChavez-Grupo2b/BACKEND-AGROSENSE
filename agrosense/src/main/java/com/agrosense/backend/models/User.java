package com.agrosense.backend.models;

import jakarta.persistence.*;
import lombok.*;
import java.time.Local DateTime; 

@Entity
@Table(name= "usuarios")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTIFY)
    private Integer idUser;

    @Column(nullable = false)
    private String name; 

    @Column(nullable = false)
    private String lastname;

    @Column(nullable = false, unique = true)
    private String email;  

    @Column(name = "password_hash", nullable = false)
    private String password_hash;

    @Column(nullable = false)
    private String role; 

    @Builder.Default
    private Boolean active = true;  

    @Column(name = "created in")
    private LocalDateTime CreatedIn; 

    @Column(name = "last access")
    private LocalDateTime LastAccess; 

    @PrePersist
    public void prePersist(){
        this.CreatedIn = LocalDateTime.now();
    }
}