package com.ticketbooking.service;

import com.ticketbooking.entity.Order;
import com.ticketbooking.exception.BookingException;
import com.ticketbooking.repository.OrderRepository;
import com.ticketbooking.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    // All dependencies injected via @RequiredArgsConstructor
    // No need to write a constructor manually — Lombok does it
    private final RedisService redisService;
    private final TicketRepository ticketRepository;
    private final OrderRepository orderRepository;
    private final RedissonClient redissonClient;

    // These three values come from application.yml
    // app.ticket.lock-key-prefix = "lock:user:booking:"
    // app.ticket.lock-wait-time-seconds = 3
    // app.ticket.lock-lease-time-seconds = 10
    @Value("${app.ticket.lock-key-prefix}")
    private String lockKeyPrefix;

    @Value("${app.ticket.lock-wait-time-seconds}")
    private long lockWaitTime;

    @Value("${app.ticket.lock-lease-time-seconds}")
    private long lockLeaseTime;

    // ─────────────────────────────────────────────────────────────
    // PUBLIC ENTRY POINT
    // This is what the controller calls.
    // Handles the distributed lock, then delegates to executeBooking.
    // ─────────────────────────────────────────────────────────────
    public Order bookTicket(String userId, Long ticketId, int quantity) {

        // Lock key is unique per user per ticket
        // "lock:user:booking:alice:1"
        // alice booking ticket 2 gets a different lock — unaffected
        // bob booking ticket 1 gets a different lock — unaffected
        String lockKey = lockKeyPrefix + userId + ":" + ticketId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // Try to acquire lock within 3 seconds
            // Hold it for maximum 10 seconds (auto-release safety net)
            boolean acquired = lock.tryLock(lockWaitTime, lockLeaseTime,
                    TimeUnit.SECONDS);
            if (!acquired) {
                throw new BookingException(
                        "Your request is already being processed. Please wait.");
            }

            // Lock acquired — proceed with booking
            return executeBooking(userId, ticketId, quantity);

        } catch (InterruptedException e) {
            // Thread was interrupted while waiting for the lock
            Thread.currentThread().interrupt();
            throw new BookingException("Booking was interrupted. Please try again.");

        } finally {
            // ALWAYS runs — releases the lock even if an exception was thrown
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // PRIVATE BOOKING LOGIC
    // Runs inside the distributed lock.
    // Steps happen in this exact order — each gates the next.
    // ─────────────────────────────────────────────────────────────
    private Order executeBooking(String userId, Long ticketId, int quantity) {

        // ── STEP 2: Idempotency check ─────────────────────────────
        // Has this user already confirmed a booking for this ticket?
        // If yes, reject immediately — no Redis or extra DB work needed
        orderRepository.findByUserIdAndTicketId(userId, ticketId)
                .filter(o -> o.getStatus() == Order.OrderStatus.CONFIRMED)
                .ifPresent(o -> {
                    throw new BookingException(
                            "You have already booked this ticket. OrderId: " + o.getId());
                });

        // ── STEP 3: Validate ticket exists in Postgres ────────────
        // Prevents bookings for non-existent ticket IDs
        ticketRepository.findById(ticketId)
                .orElseThrow(() ->
                        new BookingException("Ticket not found: " + ticketId));

        // ── STEP 4: Atomic Redis decrement via Lua script ─────────
        // This is NOT inside a DB transaction — deliberate
        // The Lua script handles atomicity on the Redis side
        boolean decremented = redisService.tryDecrementStock(ticketId, quantity);
        if (!decremented) {
            throw new BookingException(
                    "Sorry, this ticket is sold out.");
        }

        // ── STEP 5 + 6: Save to DB, compensate if it fails ───────
        try {
            return persistOrder(userId, ticketId, quantity);

        } catch (Exception dbException) {
            // DB write failed AFTER Redis was already decremented
            // We must restore the Redis stock or that seat is lost forever
            log.error(
                    "DB write failed for userId={} ticketId={}. Compensating Redis.",
                    userId, ticketId, dbException);

            redisService.compensateStock(ticketId, quantity);

            throw new BookingException(
                    "Order could not be saved. Your seat has been released. Please retry.");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // DB WRITE — @Transactional scoped only to this method
    // If the INSERT or the UPDATE fails, both are rolled back
    // together inside Postgres. Redis is untouched by this transaction.
    // ─────────────────────────────────────────────────────────────
    @Transactional
    protected Order persistOrder(String userId, Long ticketId, int quantity) {

        // Build and save the order row
        Order order = Order.builder()
                .userId(userId)
                .ticketId(ticketId)
                .quantity(quantity)
                .status(Order.OrderStatus.CONFIRMED)
                .build();

        Order saved = orderRepository.save(order);

        // Increment sold count directly in DB
        // No read needed — direct UPDATE tickets SET sold_count = sold_count + 1
        ticketRepository.incrementSoldCount(ticketId, quantity);

        log.info("Order CONFIRMED — orderId={} userId={} ticketId={}",
                saved.getId(), userId, ticketId);

        return saved;
    }


}