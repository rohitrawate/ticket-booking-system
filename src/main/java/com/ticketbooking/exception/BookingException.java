package com.ticketbooking.exception;


// ─────────────────────────────────────────────────────────────
// CUSTOM EXCEPTION
// A typed exception so the controller can catch booking
// failures specifically, separate from unexpected errors
// ─────────────────────────────────────────────────────────────
//public static class BookingException extends RuntimeException {
public  class BookingException extends RuntimeException {
    public BookingException(String message) {
        super(message);
    }
}