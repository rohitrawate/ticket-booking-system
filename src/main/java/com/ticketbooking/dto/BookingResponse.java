package com.ticketbooking.dto;

import java.time.LocalDateTime;

// ─────────────────────────────────────────────────────────────
// RESPONSE DTO
// What we send back to the caller on success:
// {
//   "orderId":  1,
//   "userId":   "alice",
//   "ticketId": 1,
//   "status":   "CONFIRMED",
//   "bookedAt": "2026-03-09T10:00:00",
//   "message":  "Booking confirmed!"
// }
// ─────────────────────────────────────────────────────────────
public record BookingResponse(
        Long orderId,
        String userId,
        Long ticketId,
        String status,
        LocalDateTime bookedAt,
        String message
) {}