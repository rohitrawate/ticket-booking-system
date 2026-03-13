package com.ticketbooking.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// ─────────────────────────────────────────────────────────────
// REQUEST DTO
// What the caller must send in the request body as JSON:
// {
//   "userId":   "alice",
//   "ticketId": 1,
//   "quantity": 1
// }
// ─────────────────────────────────────────────────────────────
public record BookingRequest(

        @NotBlank(message = "userId cannot be blank")
        String userId,

        @NotNull(message = "ticketId is required")
        Long ticketId,

        @Min(value = 1, message = "quantity must be at least 1")
        int quantity
) {}