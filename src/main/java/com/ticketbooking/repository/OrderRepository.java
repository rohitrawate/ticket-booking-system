package com.ticketbooking.repository;

import com.ticketbooking.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByUserIdAndTicketId(String userId, Long ticketId);
}