package com.company.scheduling.domain;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Entity @Table(name = "system_users")
public class SystemUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private String username;
    private String passwordHash;
    private String role;
    private String enteredBy;
    private LocalDateTime createdAt = LocalDateTime.now();
}