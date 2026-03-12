package com.ticketbooking.config;

import com.ticketbooking.entity.Ticket;
import com.ticketbooking.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

// @Configuration tells Spring: this class contains bean definitions
// Think of it as a factory class for Spring-managed objects
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DataLoader {

    // ─────────────────────────────────────────────────────────────
    // SEED DATA ON STARTUP
    //
    // @Bean          → Spring manages this object's lifecycle
    // @Profile("!test") → skip this entirely when running tests
    //
    // CommandLineRunner.run() is called automatically by Spring Boot
    // after all beans are loaded, before HTTP traffic is accepted
    // ─────────────────────────────────────────────────────────────
    @Bean
    @Profile("!test")
    CommandLineRunner seedData(TicketRepository repo) {
        return args -> {

            // Only seed if the table is completely empty
            // Makes this operation safe to run on every restart
            if (repo.count() == 0) {

                Ticket ticket = Ticket.builder()
                        .name("VIP Pass")
                        .eventName("TechConf 2026")
                        .totalStock(100)   // 100 seats available
                        .soldCount(0)      // none sold yet
                        .price(499.99)
                        .build();

                Ticket saved = repo.save(ticket);

                log.info("──────────────────────────────────────");
                log.info("Seed data inserted successfully");
                log.info("Ticket id    : {}", saved.getId());
                log.info("Ticket name  : {}", saved.getName());
                log.info("Total stock  : {}", saved.getTotalStock());
                log.info("──────────────────────────────────────");

            } else {
                log.info("Tickets table already has data — skipping seed");
            }
        };
    }
}