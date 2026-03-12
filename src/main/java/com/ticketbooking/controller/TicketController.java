package com.ticketbooking.controller;

import com.ticketbooking.dto.BookingRequest;
import com.ticketbooking.dto.BookingResponse;
import com.ticketbooking.entity.Order;
import com.ticketbooking.exception.BookingException;
import com.ticketbooking.service.BookingService;
import com.ticketbooking.service.RedisService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

// @RestController = @Controller + @ResponseBody
// Means: handle HTTP requests and return JSON automatically
@Slf4j
@RestController
@RequestMapping("/api/v1/tickets")
@RequiredArgsConstructor
public class TicketController {

    // Spring injects these automatically via @RequiredArgsConstructor
    private final BookingService bookingService;
    private final RedisService redisService;

    // ─────────────────────────────────────────────────────────────
    // ENDPOINT 1 — BOOK A TICKET
    // POST /api/v1/tickets/book
    //
    // @RequestBody  → read JSON from request body into BookingRequest
    // @Valid        → run the @NotBlank/@NotNull/@Min checks first
    //
    // Returns 200 OK on success
    // Returns 409 Conflict on business rule failure
    // Returns 500 on unexpected error
    // ─────────────────────────────────────────────────────────────
    @PostMapping("/book")
    public ResponseEntity<?> bookTicket(
            @Valid @RequestBody BookingRequest req) {

        log.info("Booking request received — userId={} ticketId={} qty={}",
                req.userId(), req.ticketId(), req.quantity());

        try {
            // Hand off to BookingService — controller does nothing else
            Order order = bookingService.bookTicket(
                    req.userId(),
                    req.ticketId(),
                    req.quantity()
            );

            // Build and return the success response
            BookingResponse response = new BookingResponse(
                    order.getId(),
                    order.getUserId(),
                    order.getTicketId(),
                    order.getStatus().name(),
                    order.getCreatedAt(),
                    "Booking confirmed!"
            );

            log.info("Booking successful — orderId={}", order.getId());
            return ResponseEntity.ok(response);

//        } catch (BookingService.BookingException ex) {
        } catch (BookingException ex) {
            // Business rule failure — sold out, already booked, etc.
            // These are expected failures — log as warn not error
            log.warn("Booking rejected — userId={} reason={}",
                    req.userId(), ex.getMessage());

            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(Map.of(
                            "error", ex.getMessage(),
                            "timestamp", LocalDateTime.now().toString()
                    ));

        } catch (Exception ex) {
            // Unexpected failure — DB down, Redis down, NPE, etc.
            // These are real errors — log as error
            log.error("Unexpected error during booking for userId={}",
                    req.userId(), ex);

            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "An unexpected error occurred. Please try again.",
                            "timestamp", LocalDateTime.now().toString()
                    ));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ENDPOINT 2 — CHECK STOCK
    // GET /api/v1/tickets/1/stock
    //
    // @PathVariable → reads {ticketId} from the URL
    //
    // Returns 200 with current Redis stock count
    // Returns 404 if ticketId not found in Redis
    //
    // Used during testing to verify Redis is decrementing correctly
    // ─────────────────────────────────────────────────────────────
    @GetMapping("/{ticketId}/stock")
    public ResponseEntity<?> getStock(@PathVariable Long ticketId) {

        log.info("Stock check requested — ticketId={}", ticketId);

        Long stock = redisService.getStock(ticketId);

        if (stock == null) {
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "error", "No stock data found for ticketId: " + ticketId
                    ));
        }

        return ResponseEntity.ok(Map.of(
                "ticketId", ticketId,
                "availableStock", stock
        ));
    }
}

/*
## WHAT THE TWO ENDPOINTS DO
```
ENDPOINT 1:
  Method:  POST
  URL:     http://localhost:8080/api/v1/tickets/book
  Body:    {"userId":"alice","ticketId":1,"quantity":1}
  200:     {"orderId":1,"status":"CONFIRMED","message":"Booking confirmed!"}
  409:     {"error":"Sorry, this ticket is sold out."}

ENDPOINT 2:
  Method:  GET
  URL:     http://localhost:8080/api/v1/tickets/1/stock
  200:     {"ticketId":1,"availableStock":99}
  404:     {"error":"No stock data found for ticketId: 1"}
```

 */