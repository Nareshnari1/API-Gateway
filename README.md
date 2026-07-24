# Sparta E-commerce — Microservices Platform

A Spring Cloud microservices e-commerce application: a React storefront/ops console backed by
an API gateway with real user authentication (DB-backed accounts, email-OTP registration, BCrypt,
JWT, role-based access), service discovery, independent product / order / notification services,
synchronous service-to-service calls, and asynchronous order events over Kafka.

> **Run the whole thing with one command:** [`docker compose up -d --build`](#quick-start-docker)

---

## Architecture

```
                        ┌─────────────────────────────┐
   Browser  ─────────▶  │  Frontend (nginx)  :5173     │   React SPA + reverse-proxy
                        └──────────────┬──────────────┘
                                       │  /api/v1/*  (same-origin, no CORS)
                                       ▼
                        ┌─────────────────────────────┐
                        │  API Gateway       :8083     │   DB users + OTP + roles; issues/validates
                        │  (Spring Cloud Gateway, DB)  │   JWT; routes /api/v1/** via Eureka
                        └───────┬───────────┬─────────┘
                                │           │
                 registers ◀────┼───────────┼────▶  Eureka (discovery)   :8761
                                │           │
              ┌─────────────────┘           └───────────────────┐
              ▼                                                  ▼
   ┌────────────────────┐   REST: price / reduce-stock  ┌────────────────────┐
   │ order-service :8080 │ ─────────────────────────────▶│ product-service:8082│
   │  (Postgres, Flyway) │◀───── REST: has-open-orders ──│  (Postgres)         │
   └─────────┬──────────┘                                └────────────────────┘
             │  publishes OrderConfirmedEvent
             ▼
        Kafka topic  "order.confirmed"   (ZooKeeper + Kafka)
             │  consumes
             ▼
   ┌────────────────────────┐
   │ notification-service    │   :8081   (Postgres) — email / SMS / in-app
   └────────────────────────┘
```

| Service | Folder | Port | Responsibility |
|---|---|---|---|
| Eureka | `eureka/eureka` | 8761 | Service discovery |
| API Gateway | `API-Gateway` | 8083 | Auth: DB users, email-OTP registration, BCrypt, JWT, role-based routing (Postgres `gateway_service`) |
| Product Service | `sparta-product-service` | 8082 | Product catalog & stock (UUID IDs) |
| Order Service | `sparta-order-service` | 8080 | Orders; calls product-service; publishes Kafka events |
| Notification Service | `Notification` | 8081 | Consumes order events; email/SMS/in-app |
| Frontend | `sparta-ecommerce-frontend` | 5173 | React + Vite SPA (served by nginx) |

Infrastructure (also containerized): **PostgreSQL** (host port `5433`), **Kafka + ZooKeeper** (host port `29092`).

**Tech:** Java 17 · Spring Boot 4.1 · Spring Cloud 2025.1.2 · PostgreSQL 16 · Kafka · React 18 + Vite 5 · Docker Compose.

---

## Quick start (Docker)

**Prerequisites:** Docker Desktop (with Compose v2). Nothing else — the images build from source.

```bash
# from this directory (where docker-compose.yml lives)
docker compose up -d --build
```

Then open **http://localhost:5173** and sign in with email + password (or **Create account** to
register via email-OTP):

| Email | Password | Role |
|---|---|---|
| `user@gmail.com` | `1234` | USER |
| `admin@gmail.com` | `1234` | ADMIN |

(Both accounts are seeded automatically on first start.)

Useful URLs:
- **App (frontend):** http://localhost:5173
- **Eureka dashboard:** http://localhost:8761
- **API Gateway:** http://localhost:8083

> **Cold start:** the Spring services take ~40–60s to boot. The frontend is ready sooner, so API
> calls in that first minute may briefly return **502** until the gateway is up — just refresh.

Stop / reset:
```bash
docker compose down        # stop (keeps the database volume)
docker compose down -v     # stop and wipe the Postgres data
```

The product catalog is seeded with 5 demo products on first start, so you can browse and order
immediately.

---

## How it works

### Authentication (real identity system)
- **Accounts live in Postgres** (gateway `users` table); passwords are **BCrypt**-hashed. Two are
  seeded on startup: `admin@gmail.com`/`1234` (ADMIN) and `user@gmail.com`/`1234` (USER).
- **Registration is email-OTP**, a 3-step flow on the gateway:
  `POST /api/v1/auth/register` (emails a 6-digit OTP) → `POST /api/v1/auth/verify-otp`
  → `POST /api/v1/auth/set-password` (account becomes ACTIVE).
  _Local/dev: Docker uses dummy SMTP, so the OTP is also printed in the gateway logs
  (`docker compose logs api-gateway`)._
- **Login** — `POST /api/v1/auth/login` with `{email, password}` returns a signed **HS256 JWT**
  (24h) plus `{email, role, customerId}`.
- The gateway's global filter classifies each route as **public** (GET products), **admin-only**
  (product writes, order status/delete) or **authenticated** (checkout, own orders). It **strips**
  client-supplied identity headers and **injects trusted** `X-Customer-Id` / `X-User-Role` /
  `X-User-Email` (from the JWT) for downstream services.
- The frontend stores the token in `localStorage`, sends `Authorization: Bearer <token>`
  automatically, and logs out on a 401.

### Routing
The gateway defines explicit path routes (load-balanced via Eureka) and forwards each path
**unchanged** to its owning service: `/api/v1/products/**` → product-service,
`/api/v1/orders/**` → order-service, `/api/v1/notifications/**` & `/api/v1/templates/**` →
notification-service; `/api/v1/auth/**` is served by the gateway itself. The frontend calls these
`/api/v1/...` paths directly — the nginx (and Vite dev) proxy simply forwards `/api/**` to the
gateway, so it's same-origin with no CORS.

### Order flow (synchronous + event-driven)
1. `POST /api/v1/orders` → order-service.
2. For each item, order-service calls **product-service** over REST: fetch price/stock, then
   reduce stock (resilience4j circuit breaker guards the call).
3. Order is saved `CONFIRMED`; order-service **publishes `OrderConfirmedEvent`** to the Kafka
   topic `order.confirmed`.
4. **notification-service** consumes the event and creates/sends the order confirmation.
   (This is the single notification trigger — the flow is event-driven.)

### Roles (what each account sees)
- **Customer** (`USER`): the shop + cart/checkout, and **My Orders** — their own orders only. No Notifications tab.
- **Admin**: everything a customer sees, plus **catalog management** (add/edit/delete products),
  **All Orders** across every customer (with full details), and the **Notifications** admin view.

---

## API at a glance (through the gateway)

Base: `http://localhost:8083`. `GET /api/v1/products/**` and `/api/v1/auth/**` are public; everything
else needs `Authorization: Bearer <token>` (product writes and order status/delete are ADMIN-only).

**Auth** — `POST /api/v1/auth/register` · `POST /api/v1/auth/verify-otp` · `POST /api/v1/auth/set-password` · `POST /api/v1/auth/login`

**Products** — `/api/v1/products`
`GET` (list) · `POST` (create, admin) · `GET /{id}` · `PUT /update/{id}` (admin) · `PATCH /{id}/price` (admin) ·
`PATCH /adjust/stock/{id}` (admin) · `PATCH /reduce/stock/{id}` · `GET /availability/{id}?quantity=` · `DELETE /{id}` (admin)

**Orders** — `/api/v1/orders`
`POST` (create) · `GET /{id}` · `GET` (list, paged) · `PATCH /{id}/status` (admin) · `POST /{id}/cancel`

**Notifications** — `/api/v1/notifications`
`GET` (list) · `GET /in-app/user/{recipient}` · `POST /in-app` · `PATCH /in-app/{id}/read` · `GET /history`

_Example — log in and place an order:_
```bash
TOKEN=$(curl -s -X POST http://localhost:8083/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@gmail.com","password":"1234"}' | jq -r .token)

curl -s http://localhost:8083/api/v1/products -H "Authorization: Bearer $TOKEN"

curl -s -X POST http://localhost:8083/api/v1/orders \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"customerId":1,"customerName":"Demo User","customerEmail":"user@gmail.com",
       "shippingAddress":"1 Test St","items":[{"productId":"<PRODUCT_UUID>","quantity":1}]}'
```

---

## Development

### Frontend hot-reload (instead of the container)
The `frontend` container uses port 5173, so stop it first:
```bash
docker compose stop frontend
cd sparta-ecommerce-frontend
npm install      # first time only
npm run dev      # http://localhost:5173, Vite dev proxy -> gateway :8083
```

### Run a service's tests
Each backend service is a standalone Maven project (tests use H2 / embedded infra — no Docker needed):
```bash
cd sparta-order-service && mvn test      # likewise for the other services
```

### Configuration (env vars used by the compose)
| Variable | Used by | Meaning |
|---|---|---|
| `EUREKA_URI` | all services | Eureka registry URL (`http://eureka:8761/eureka`) |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | gateway, product, order, notification | Postgres datasource |
| `KAFKA_BOOTSTRAP_SERVERS` | order, notification | `kafka:9092` |
| `PRODUCT_SERVICE_URL` | order | product-service base URL |
| `JWT_SECRET` | gateway | JWT signing key — **Base64-encoded**, ≥32 bytes (a dev default is baked in) |
| `SPRING_MAIL_USERNAME` / `SPRING_MAIL_PASSWORD` | notification | SMTP creds (optional; see below) |

Databases (auto-created in the Postgres container): `gateway_service`, `product_service`,
`order_service`, `notification_service` — user `postgres`, password `Tech@123` (local/dev default).

---

## Troubleshooting

- **502s right after `up`** — services still booting; wait ~1 min and refresh.
- **Port already in use** — stop older stacks (`docker compose down` in `Notification/` and
  `kafka/`) or anything on 5173/8080-8083/8761/5433/29092.
- **Emails show status `FAILED`** — expected with the default dummy SMTP creds; the notification
  is still created and the Kafka flow works. For real delivery, export real Gmail creds before `up`:
  ```bash
  export SPRING_MAIL_USERNAME=you@gmail.com SPRING_MAIL_PASSWORD='your-gmail-app-password'
  ```
- **Inspect logs** — `docker compose logs -f order-service` (or any service name).
- **Check Kafka** — `docker exec sparta-kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic order.confirmed --from-beginning --max-messages 1`

---

## Notes

- **Real email is enabled.** SMTP credentials are supplied via a git-ignored **`.env`** at the project
  root (`SPRING_MAIL_USERNAME` / `SPRING_MAIL_PASSWORD`) which docker compose auto-loads, so
  registration OTPs and order confirmations are delivered by email. Because email works,
  `OTP_DEV_RETURN=false` (the OTP is NOT returned in the API response). For a no-email fallback set
  `OTP_DEV_RETURN=true` to surface the code on-screen, or use the seeded accounts (which skip OTP).
  **Rotate the Gmail app password** (in `.env` and `Notification/.../application-secrets.properties`) before sharing.
- The gateway signs JWTs with a **Base64** `app.jwt.secret` (dev default baked in); override via the
  `JWT_SECRET` env var (Base64-encoded, ≥32 bytes) for anything beyond local use.
- For a detailed review of what was audited and fixed to get this running end-to-end, see
  **`PROJECT_SUMMARY_AND_CHANGES.md`**.
