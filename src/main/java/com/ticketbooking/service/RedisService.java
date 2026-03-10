package com.ticketbooking.service;

import com.ticketbooking.entity.Ticket;
import com.ticketbooking.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisService {

    // StringRedisTemplate is Spring's wrapper around Redis
    // It handles connection pooling, serialization, and error handling
    private final StringRedisTemplate redisTemplate;
    private final TicketRepository ticketRepository;

    // Reads "ticket:stock:" from application.yml
    // The full Redis key becomes "ticket:stock:1" for ticket with id=1
    @Value("${app.ticket.redis-stock-key-prefix}")
    private String stockKeyPrefix;

    // ─────────────────────────────────────────────────────────────
    // THE LUA SCRIPT
    // This is the heart of the entire system.
    //
    // KEYS[1] = the Redis key e.g. "ticket:stock:1"
    // ARGV[1] = how many tickets the user wants to buy e.g. "1"
    //
    // Returns:
    //   1  = success, stock was decremented
    //   0  = not enough stock, purchase rejected
    //  -1  = key does not exist in Redis (cache miss)
    // ─────────────────────────────────────────────────────────────
    private static final DefaultRedisScript<Long> DECR_SCRIPT;

    static {
        DECR_SCRIPT = new DefaultRedisScript<>();
        DECR_SCRIPT.setResultType(Long.class);
        DECR_SCRIPT.setScriptText(
                "local stock = redis.call('GET', KEYS[1])\n" +
                        "if stock == false then\n" +
                        "    return -1\n" +
                        "end\n" +
                        "local stockNum = tonumber(stock)\n" +
                        "local qty = tonumber(ARGV[1])\n" +
                        "if stockNum >= qty then\n" +
                        "    redis.call('DECRBY', KEYS[1], qty)\n" +
                        "    return 1\n" +
                        "else\n" +
                        "    return 0\n" +
                        "end"
        );
    }

    // ─────────────────────────────────────────────────────────────
    // CACHE WARM-UP
    // Fires automatically once after the app fully starts.
    // Loads ticket stock from Postgres into Redis.
    // Uses setIfAbsent (SETNX) so restarts don't overwrite
    // live in-flight counts that Redis already holds.
    // ─────────────────────────────────────────────────────────────
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpCache() {
        log.info("Starting Redis cache warm-up...");
        List<Ticket> tickets = ticketRepository.findAll();

        for (Ticket t : tickets) {
            String key = buildKey(t.getId());
            int available = t.getTotalStock() - t.getSoldCount();

            // SETNX — only sets the value if the key does not exist
            Boolean wasSet = redisTemplate.opsForValue()
                    .setIfAbsent(key, String.valueOf(available));

            log.info("Warm-up: ticketId={} available={} freshlySet={}",
                    t.getId(), available, wasSet);
        }

        log.info("Cache warm-up complete — {} tickets loaded.", tickets.size());
    }

    // ─────────────────────────────────────────────────────────────
    // ATOMIC DECREMENT
    // Executes the Lua script against Redis.
    // Returns true if stock was successfully decremented.
    // Returns false if sold out or key missing.
    // ─────────────────────────────────────────────────────────────
    public boolean tryDecrementStock(Long ticketId, int qty) {
        Long result = redisTemplate.execute(
                DECR_SCRIPT,
                Collections.singletonList(buildKey(ticketId)),
                String.valueOf(qty)
        );

        if (result == null) {
            log.error("Lua script returned null for ticketId={}", ticketId);
            return false;
        }

        return switch (result.intValue()) {
            case  1 -> {
                log.info("Stock decremented for ticketId={} qty={}", ticketId, qty);
                yield true;
            }
            case  0 -> {
                log.warn("Sold out — ticketId={}", ticketId);
                yield false;
            }
            case -1 -> {
                log.warn("Cache miss — ticketId={} not in Redis", ticketId);
                yield false;
            }
            default -> {
                log.error("Unexpected Lua result={} for ticketId={}", result, ticketId);
                yield false;
            }
        };
    }

    // ─────────────────────────────────────────────────────────────
    // COMPENSATION
    // Called when Postgres write fails AFTER Redis was decremented.
    // Adds the quantity back so the next user can buy the seat.
    // ─────────────────────────────────────────────────────────────
    public void compensateStock(Long ticketId, int qty) {
        redisTemplate.opsForValue().increment(buildKey(ticketId), qty);
        log.warn("COMPENSATION APPLIED: ticketId={} stock restored by +{}",
                ticketId, qty);
    }

    // ─────────────────────────────────────────────────────────────
    // GET CURRENT STOCK
    // Used by the controller to expose stock via the API.
    // Also used in tests to verify no overselling occurred.
    // ─────────────────────────────────────────────────────────────
    public Long getStock(Long ticketId) {
        String val = redisTemplate.opsForValue().get(buildKey(ticketId));
        return val == null ? null : Long.parseLong(val);
    }

    // Builds the full Redis key from the prefix + ticketId
    // e.g. "ticket:stock:" + 1 = "ticket:stock:1"
    public String buildKey(Long ticketId) {
        return stockKeyPrefix + ticketId;
    }
}