package com.ticketbooking.repository;

import com.ticketbooking.entity.Ticket;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    @Modifying
//    @Transactional
    @Query("UPDATE Ticket t SET t.soldCount = t.soldCount + :qty WHERE t.id = :id")
    int incrementSoldCount(@Param("id") Long id, @Param("qty") int qty);
}