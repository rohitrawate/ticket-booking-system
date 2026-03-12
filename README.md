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

Now that both service files exist, here is the complete picture:
```
## 🐳 Docker Commands

| Command | Purpose |
|---------|---------|
| `docker-compose up -d` | Start Postgres + Redis |
| `docker-compose down` | Stop services |
| `docker-compose down -v` | Stop + wipe volumes |
| `docker exec -it <postgres-container> psql -U ticketuser -d ticketdb` | Postgres CLI |
| `docker exec -it <redis-container> redis-cli` | Redis CLI |
| `mvn spring-boot:run -Dspring.profiles.active=staging` | Run app with real DB |

**Container names**:
- Postgres: `high-concurrency-ticket-system-postgres-1`
- Redis: `high-concurrency-ticket-system-redis-1`


```bash 
User sends POST /api/v1/tickets/book
│
▼
BookingService.bookTicket()
│
├─① Redisson.getLock("lock:user:booking:alice:1")
│         └── Redis SETNX — only one thread proceeds
│
├─② orderRepository.findByUserIdAndTicketId()
│         └── SELECT from orders — already booked?
│
├─③ ticketRepository.findById()
│         └── SELECT from tickets — does it exist?
│
├─④ redisService.tryDecrementStock()
│         └── Lua script in Redis — atomic check + DECRBY
│
├─⑤ persistOrder() ← @Transactional here opens Transaction A
│    ├── orderRepository.save()      → INSERT into orders (joins Transaction A)
│    └── ticketRepository.incrementSoldCount()d  → UPDATE tickets needs Transaction A
│         │                                      So added the @Transactional
│         │                                      to join to the existing ()
│         └── @Transactional ends here
│
└─⑥ if ⑤ fails → redisService.compensateStock()
└── Redis INCRBY — seat restored
```