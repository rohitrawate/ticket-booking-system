//package com.ticketbooking;
//
//public class ConcurrentBookingTest {
//}
package com.ticketbooking;

import com.ticketbooking.entity.Order;
import com.ticketbooking.entity.Ticket;
import com.ticketbooking.exception.BookingException;
import com.ticketbooking.repository.OrderRepository;
import com.ticketbooking.repository.TicketRepository;
import com.ticketbooking.service.BookingService;
import com.ticketbooking.service.RedisService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// @SpringBootTest: starts the FULL application context
// Uses real Redis, real Postgres, real services — nothing mocked
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConcurrentBookingTest {

    // 100 users competing for only 50 tickets
    // Change these numbers to experiment after the test passes
    private static final int TOTAL_USERS  = 100;
    private static final int TICKET_STOCK = 50;

    // Spring injects the real beans — same ones the app uses
    @Autowired BookingService   bookingService;
    @Autowired TicketRepository ticketRepository;
    @Autowired OrderRepository  orderRepository;
    @Autowired RedisService     redisService;

    // Shared across @BeforeEach and the test method
    private static Long testTicketId;

    // ─────────────────────────────────────────────────────────────
    // RUNS BEFORE EVERY TEST METHOD
    // Gives each test a completely clean, controlled state
    // ─────────────────────────────────────────────────────────────
    @BeforeEach
    void setUp() {
        // Wipe all data from both tables
        // Order matters — orders reference tickets, delete orders first
        orderRepository.deleteAll();
        ticketRepository.deleteAll();

        // Create one fresh ticket with exactly TICKET_STOCK seats
        Ticket ticket = Ticket.builder()
                .name("Concert VIP")
                .eventName("Concurrent Fest 2026")
                .totalStock(TICKET_STOCK)
                .soldCount(0)
                .price(199.0)
                .build();

        // Save to Postgres and capture the generated id
        testTicketId = ticketRepository.save(ticket).getId();

        // Load this ticket's stock into Redis
        // Available = totalStock(50) - soldCount(0) = 50
        redisService.warmUpCache();

        System.out.println("──────────────────────────────────────────");
        System.out.println("Test setup complete");
        System.out.println("Ticket id    : " + testTicketId);
        System.out.println("Stock in Redis: " + redisService.getStock(testTicketId));
        System.out.println("──────────────────────────────────────────");
    }

    // ─────────────────────────────────────────────────────────────
    // THE MAIN CONCURRENCY TEST
    // ─────────────────────────────────────────────────────────────
    @Test
//    @Order(1)
    @org.junit.jupiter.api.Order(1)
    @DisplayName("100 concurrent users — exactly 50 confirmed, zero oversell")
    void whenHundredConcurrentUsers_thenNoOverselling()
            throws InterruptedException {

        // ── STARTING GATE ─────────────────────────────────────────
        // All threads wait here until we call startGate.countDown()
        // This ensures all 100 fire at the exact same millisecond
        CountDownLatch startGate = new CountDownLatch(1);

        // ── COMPLETION GATE ───────────────────────────────────────
        // Main thread waits here until all 100 threads finish
        CountDownLatch doneLatch = new CountDownLatch(TOTAL_USERS);

        // ── THREAD-SAFE COUNTERS ──────────────────────────────────
        // Regular int would give wrong results under concurrency
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        // ── CREATE 100 TASKS ──────────────────────────────────────
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < TOTAL_USERS; i++) {

            // Each user gets a unique UUID — simulates 100 different people
            // UUID.randomUUID() is thread-safe
            final String userId = "user-" + UUID.randomUUID();

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    // BLOCK HERE — wait for the starting gun
                    // All 100 threads pile up at this line
                    startGate.await();

                    // STARTING GUN FIRED — attempt to book
                    bookingService.bookTicket(userId, testTicketId, 1);

                    // If we reach here, booking succeeded
                    successCount.incrementAndGet();

                } catch (BookingException e) {
                    // Expected for 50 users — sold out or rejected
                    failCount.incrementAndGet();

                } catch (InterruptedException e) {
                    // Thread was interrupted while waiting at startGate
                    Thread.currentThread().interrupt();

                } finally {
                    // ALWAYS runs — signals this thread is done
                    // Decrements doneLatch whether success or failure
                    doneLatch.countDown();
                }
            });

            futures.add(future);
        }

        // ── FIRE THE STARTING GUN ─────────────────────────────────
        // This single line releases all 100 waiting threads
        // They all call bookTicket() simultaneously
        System.out.println("Starting gate opened — 100 threads released");
        startGate.countDown();

        // ── WAIT FOR ALL THREADS TO FINISH ────────────────────────
        // Blocks until doneLatch reaches 0
        // Timeout of 30 seconds prevents the test hanging forever
        boolean allFinished = doneLatch.await(30, TimeUnit.SECONDS);

        // ── PRINT RESULTS ─────────────────────────────────────────
        System.out.println("──────────────────────────────────────────");
        System.out.println("CONCURRENCY TEST RESULTS");
        System.out.println("──────────────────────────────────────────");
        System.out.println("Total users    : " + TOTAL_USERS);
        System.out.println("Ticket stock   : " + TICKET_STOCK);
        System.out.println("Confirmed      : " + successCount.get());
        System.out.println("Rejected       : " + failCount.get());
        System.out.println("Redis stock    : " + redisService.getStock(testTicketId));
        System.out.println("DB sold_count  : " +
                ticketRepository.findById(testTicketId)
                        .map(Ticket::getSoldCount).orElse(-1));
        System.out.println("──────────────────────────────────────────");

        // ── ASSERTIONS ────────────────────────────────────────────

        // All 100 threads must finish within 30 seconds
        assertThat(allFinished)
                .isTrue()
                .withFailMessage("Test timed out — threads did not finish in 30s");

        // Core assertion: exactly TICKET_STOCK confirmed
        // Not 49 (undersell), not 51 (oversell) — exactly 50
        assertThat(successCount.get())
                .isEqualTo(TICKET_STOCK)
                .withFailMessage(
                        "Expected exactly %d confirmed but got %d",
                        TICKET_STOCK, successCount.get());

        // All threads either succeeded or failed — none were lost
        assertThat(successCount.get() + failCount.get())
                .isEqualTo(TOTAL_USERS)
                .withFailMessage("Some threads did not complete");

        // Redis stock must be exactly zero
        // Proves no leaked decrements and no missed decrements
        assertThat(redisService.getStock(testTicketId))
                .isZero()
                .withFailMessage("Redis stock should be 0 but was: %d",
                        redisService.getStock(testTicketId));

        // Postgres sold_count must match exactly
        // Proves Redis and Postgres are consistent
        Ticket updated = ticketRepository
                .findById(testTicketId).orElseThrow();

        assertThat(updated.getSoldCount())
                .isEqualTo(TICKET_STOCK)
                .withFailMessage(
                        "DB sold_count should be %d but was %d",
                        TICKET_STOCK, updated.getSoldCount());

        // Total confirmed orders in DB must match
        long confirmedOrders = orderRepository.findAll()
                .stream()
                .filter(o -> o.getStatus() == Order.OrderStatus.CONFIRMED)
                .count();

        assertThat(confirmedOrders)
                .isEqualTo(TICKET_STOCK)
                .withFailMessage(
                        "Expected %d confirmed orders in DB but found %d",
                        TICKET_STOCK, confirmedOrders);
    }
}

/*

## NOW RUN THE TEST
Stop the running app first. The test starts its own Spring context and needs port 8080 free.
In IntelliJ — click the red Stop button to stop the app.
Then right-click `ConcurrentBookingTest.java` → Run 'ConcurrentBookingTest'.

Or from terminal:
```
./mvnw test
```

Watch the output. The test will take 15–30 seconds because it is starting a full Spring context,
connecting to real Redis and Postgres, and running 100 concurrent threads.
---

## WHAT YOU SHOULD SEE
```
──────────────────────────────────────────
Test setup complete
Ticket id    : 1
Stock in Redis: 50
──────────────────────────────────────────

Starting gate opened — 100 threads released

──────────────────────────────────────────
CONCURRENCY TEST RESULTS
──────────────────────────────────────────
Total users    : 100
Ticket stock   : 50
Confirmed      : 50
Rejected       : 50
Redis stock    : 0
DB sold_count  : 50
──────────────────────────────────────────

Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

---

## VERIFY IN DBEAVER AFTER THE TEST

Open DBeaver → refresh → click the `orders` table → Data tab.

You will see exactly 50 rows, all with status CONFIRMED. Each row has a different userId — all the `user-UUID` strings from the test.
```
┌────┬──────────────────┬──────────────┬──────────┬───────────┐
│ id │ user_id          │ ticket_id    │ quantity │ status    │
├────┼──────────────────┼──────────────┼──────────┼───────────┤
│  1 │ user-a3f2...     │ 1            │ 1        │ CONFIRMED │
│  2 │ user-b7d4...     │ 1            │ 1        │ CONFIRMED │
│  3 │ user-c9e1...     │ 1            │ 1        │ CONFIRMED │
│    │ ... 47 more rows │              │          │           │
│ 50 │ user-z2k8...     │ 1            │ 1        │ CONFIRMED │
└────┴──────────────────┴──────────────┴──────────┴───────────┘
```
 */