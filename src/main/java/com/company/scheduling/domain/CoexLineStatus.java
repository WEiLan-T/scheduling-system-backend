package com.company.scheduling.domain;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Entity @Table(name = "coex_line_status")
public class CoexLineStatus {
    @Id private String lineId;
    private String workshopId;
    private String caliberLimit;
    private String lineStatus;
    private String enteredBy;
    private LocalDateTime createdAt = LocalDateTime.now();
}