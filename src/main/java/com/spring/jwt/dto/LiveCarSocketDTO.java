package com.spring.jwt.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight DTO used only for Socket.IO responses, with date-time fields as Strings
 * to avoid serialization issues with LocalDateTime in netty-socketio.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LiveCarSocketDTO {

    private Integer bidCarId;

    private Integer beadingCarId;

    private String closingTime;   // ISO string

    private String createdAt;     // ISO string

    private Integer basePrice;

    private Integer userId;
}
