# High‑Concurrent Ticket Booking System

A Spring Boot 3.4.3 + Java 21 + Redis / Redisson project that demonstrates:
- Redis Lua scripts for atomic stock checks and decrements.
- Idempotent booking with Redisson distributed locks.
- Compensation logic to keep Redis and Postgres in sync.

---

## 00 PREREQ: Setup Commands

Before you start, install these tools.

### macOS

```bash
brew install openjdk@21
brew install --cask docker
brew install --cask intellij-idea
