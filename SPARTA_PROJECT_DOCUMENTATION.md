# Sparta E-commerce — Complete Project Documentation

_A full technical reference for the Sparta e-commerce microservices platform: architecture,
services, database design, security, messaging, frontend, deployment, and operations._

---

## Table of contents
1. [System overview](#1-system-overview)
2. [Technology stack](#2-technology-stack)
3. [Architecture](#3-architecture)
4. [Service catalogue](#4-service-catalogue)
5. [Services in detail](#5-services-in-detail)
6. [Frontend](#6-frontend)
7. [Database design](#7-database-design)
8. [Authentication & security](#8-authentication--security)
9. [Messaging (Kafka, event-driven)](#9-messaging-kafka-event-driven)
10. [Inter-service communication](#10-inter-service-communication)
11. [Resilience & fault tolerance](#11-resilience--fault-tolerance)
12. [Configuration & environment](#12-configuration--environment)
13. [API reference](#13-api-reference)
14. [Deployment (Docker)](#14-deployment-docker)
15. [Running & demo guide](#15-running--demo-guide)
16. [Key design decisions](#16-key-design-decisions)
17. [Known limitations & production hardening](#17-known-limitations--production-hardening)
18. [Repository / folder layout](#18-repository--folder-layout)

---

## 1. System overview

Sparta is a **microservices e-commerce application**: customers browse a product catalogue, place
orders, and receive confirmations; admins manage the catalogue and view all orders. It is built with
**Spring Boot 4.1 / Spring Cloud 2025.1.2** on the backend and **React 18 + Vite 5** on the frontend.

The system demonstrates the core building blocks of a cloud-native microservices platform:
- **Service discovery** (Eureka) so services find each other by name.
- **An API gateway** that centralises authentication (JWT), authorization (roles), and routing.
- **Independent services**, each owning its own database (database-per-service pattern).
- **Synchronous inter-service calls** (HTTP via RestClient / OpenFeign) for request/response needs.
- **Asynchronous, event-driven messaging** (Kafka) for order → notification decoupling.
- **Resilience patterns** (circuit breaker, bulkhead, best-effort compensation, a reconciliation sweep).
- **A real identity system**: DB-backed users, BCrypt password hashing, email-OTP registration, roles.
- **Full containerisation**: one `docker compose up` brings up the entire stack including infrastructure.

---

## 2. Technology stack

| Layer | Technology |
|---|---|
| Language / runtime | Java 17 |
| Core framework | Spring Boot 4.1.0 |
| Cloud framework | Spring Cloud 2025.1.2 (Netflix Eureka, Gateway (WebFlux), OpenFeign, LoadBalancer) |
| Security | JWT (JJWT 0.12.6, HS256), Spring Security Crypto (BCrypt) |
| Persistence | Spring Data JPA / Hibernate, PostgreSQL 16, Flyway (order-service) |
| Messaging | Apache Kafka (Confluent `cp-kafka` 7.6.1 + ZooKeeper 7.6.1) |
| Resilience | Resilience4j (circuit breaker, bulkhead) |
| Email | Spring Mail (Gmail SMTP, STARTTLS) |
| API docs | springdoc-openapi (Swagger UI) |
| Frontend | React 18, Vite 5, React Router 6, Axios |
| Frontend serving | nginx (production container) / Vite dev server |
| Containerisation | Docker + Docker Compose |

---

## 3. Architecture

```
                          ┌──────────────────────────────┐
   Browser  ───────────▶  │   Frontend (nginx)   :5173    │   React SPA; reverse-proxies /api/** to the gateway
                          └───────────────┬──────────────┘
                                          │  /api/v1/**  (same-origin, no CORS)
                                          ▼
                          ┌──────────────────────────────┐        ┌───────────────────────┐
                          │   API Gateway        :8083    │◀──────▶│  Eureka (discovery)    │  :8761
                          │   - JWT auth (DB users, OTP)  │ register / discover
                          │   - role-based access         │        └───────────────────────┘
                          │   - routes /api/v1/** by path │
                          └───┬───────────┬───────────┬───┘
              /products/**    │           │ /orders/**│           │ /notifications/**, /templates/**
                              ▼           ▼           ▼
        ┌─────────────────────────┐  ┌─────────────────────────┐  ┌──────────────────────────────┐
        │  product-service  :8082 │  │  order-service    :8080  │  │  notification-service  :8081 │
        │  (Postgres product_svc) │  │  (Postgres order_svc,    │  │  (Postgres notification_svc) │
        │  UUID product IDs       │  │   Flyway migrations)     │  │  email / SMS / in-app        │
        └───────────┬─────────────┘  └───────┬──────────┬───────┘  └───────────────▲──────────────┘
                    │  ◀── REST: reduce/restore stock,  │          │                │
                    │       get product (RestClient)    │          │                │ consumes
                    └───────────────────────────────────┘          │                │ OrderConfirmedEvent
                    ▲  Feign: has-open-orders? ─────────────────────┘                │
                    │                                                                 │
                    │                         order-service ──publishes──▶  Kafka topic "order.confirmed"
                    │                                            (Confluent Kafka + ZooKeeper)
                    └── gateway ──Feign: send OTP email──▶ notification-service (POST /api/v1/notifications/email)

        Shared infrastructure (containers):  PostgreSQL 16  ·  Kafka + ZooKeeper
```

**Communication styles used:**
- **Client → Gateway → Service**: HTTP, JWT-validated, path-routed (load-balanced via Eureka `lb://`).
- **Order → Product** (get product, reduce/restore stock): **synchronous REST** (Spring `RestClient`, circuit-breaker-guarded).
- **Product → Order** (has-open-orders check before discontinuing a product): **OpenFeign** (Eureka-resolved).
- **Gateway → Notification** (send registration OTP email): **OpenFeign**.
- **Order → Notification** (order confirmation): **asynchronous Kafka event** (`order.confirmed`).

---

## 4. Service catalogue

| Service | Folder | Port | Database | Registers in Eureka |
|---|---|---|---|---|
| Eureka Server | `eureka/eureka` | 8761 | — | server |
| API Gateway | `API-Gateway` | 8083 | `gateway_service` | yes |
| Product Service | `sparta-product-service` | 8082 | `product_service` | yes |
| Order Service | `sparta-order-service` | 8080 | `order_service` | yes |
| Notification Service | `Notification` | 8081 | `notification_service` | yes |
| Frontend | `sparta-ecommerce-frontend` | 5173 | — | no (static SPA) |
| PostgreSQL | (infra) | 5433→5432 | all 4 DBs | — |
| Kafka + ZooKeeper | (infra) | 29092→9092 | — | — |

---

## 5. Services in detail

### 5.1 Eureka Server (`eureka/eureka`, :8761)
Netflix Eureka service registry (`@EnableEurekaServer`). Every other backend service registers on
startup and discovers peers by service-id. `register-with-eureka=false`/`fetch-registry=false` (it is
the registry, not a client). The gateway uses it for `lb://` load-balanced routing; product-service
and the gateway use it to resolve Feign clients (`order-service`, `notification-service`).

### 5.2 API Gateway (`API-Gateway`, :8083)
Spring Cloud Gateway (WebFlux) that is also the **identity provider**. Responsibilities:

**Routing** — explicit path routes, load-balanced via Eureka, **no prefix rewriting** (the downstream
services serve the same `/api/v1/...` paths):
- `/api/v1/products/**` → `lb://PRODUCT-SERVICE`
- `/api/v1/orders/**` → `lb://ORDER-SERVICE`
- `/api/v1/notifications/**` and `/api/v1/templates/**` → `lb://NOTIFICATION-SERVICE`
- `/api/v1/auth/**` → handled by the gateway's own `AuthController` (WebFlux handler; bypasses routing).

**Authentication & user management** (`auth` package):
- **Users persisted in Postgres** (`gateway_service.users`) via Spring Data JPA. Entity `User`
  (`Long id`, unique `email`, `password_hash`, `role` [ADMIN|USER], `status` [PENDING_OTP|OTP_VERIFIED|ACTIVE],
  `otp_code`, `otp_expires_at`).
- **Passwords BCrypt-hashed** (`BCryptPasswordEncoder`).
- **Email-OTP registration** (3 steps): `register` (creates a PENDING_OTP user, generates a 6-digit
  OTP valid 10 min, emails it via the notification service) → `verify-otp` (→ OTP_VERIFIED) →
  `set-password` (BCrypt-hash, → ACTIVE).
- **Login**: `POST /api/v1/auth/login` `{email, password}` → validates status ACTIVE + BCrypt match →
  returns a signed **HS256 JWT** (24 h) plus `{email, role, customerId}`. JWT subject = email, claims
  `role` and `customerId` (the user's DB id).
- **Seeded accounts** on startup (`AdminSeeder`, `CustomerSeeder`): `admin@gmail.com` / `1234` (ADMIN),
  `user@gmail.com` / `1234` (USER) — both ACTIVE, so login works without the OTP flow.
- **OTP email** is sent through an OpenFeign client (`NotificationClient` → `POST /api/v1/notifications/email`).
- Blocking JPA/Feign work runs on `Schedulers.boundedElastic()` since the gateway is reactive.

**Authorization** — a `GlobalFilter` (`JwtAuthenticationGlobalFilter`, highest precedence) applies to
every proxied route (never to the local `/api/v1/auth/**`). It classifies each request:
- **PUBLIC**: `GET /api/v1/products/**` (browse without login).
- **ADMIN_ONLY**: product writes (`POST`, `PUT /update`, `PATCH`, `DELETE`), order status change
  (`PATCH /api/v1/orders/*/status`), order hard-delete (`DELETE /api/v1/orders/**`).
- **ANY_AUTHENTICATED** (default): checkout, list/get/cancel own orders, etc.
It **strips** any client-supplied `X-Customer-Id`/`X-User-Role`/`X-User-Email` (anti-spoofing),
validates the Bearer JWT, enforces the admin role where required, and **injects trusted**
`X-Customer-Id`, `X-User-Role`, `X-User-Email` from the token claims for downstream services.

CORS is allowed for `http://localhost:*` (reactive `CorsWebFilter`) for direct browser access; in the
containerised setup the frontend is same-origin via the nginx proxy so CORS isn't exercised.

### 5.3 Product Service (`sparta-product-service`, :8082)
Owns the product catalogue and stock. PostgreSQL (`product_service`), `ddl-auto=update`.
- **Entity** `Product`: `UUID id` (`@UuidGenerator`), `productName`, `description`, `category`,
  `price` (BigDecimal), `stockQuantity`, `status` (ACTIVE|DISCONTINUED), `createdAt`, `updatedAt`,
  `@Version` (optimistic locking).
- **Controller** `ProductController` (`/api/v1/products`): list, create, get by id, update info
  (`PUT /update/{id}`), update price (`PATCH /{id}/price`), check availability
  (`GET /availability/{id}?quantity=`), reduce stock (`PATCH /reduce/stock/{id}`), adjust stock
  (`PATCH /adjust/stock/{id}` — INCREASE/DECREASE/SET), soft-delete (`DELETE /{id}`).
- **Delete guard**: `deleteProduct` calls the order-service (OpenFeign `OrderServiceClient` →
  `GET /api/v1/orders/product/{id}/has-open`); if open orders reference the product it throws
  `ProductHasOpenOrdersException` (409); otherwise it soft-deletes (status → DISCONTINUED).
- **Data seeder** (`DataSeeder`, `CommandLineRunner`): inserts 5 demo products on first startup when the
  table is empty (Wireless Mouse, Mechanical Keyboard, USB-C Cable, Laptop Stand, 1080p Webcam).
  Toggle with `product.seed.enabled` (disabled in tests).

### 5.4 Order Service (`sparta-order-service`, :8080)
Owns orders and the order lifecycle. PostgreSQL (`order_service`) with **Flyway** migrations
(`ddl-auto=validate`). Kafka **producer**.

**Order creation flow** (`POST /api/v1/orders`):
1. Validate no duplicate products in the request.
2. Persist the order (PENDING).
3. For each line item, call **product-service** over REST (`ProductServiceRestClient`, Spring
   `RestClient` using the JDK HttpClient factory so `PATCH` works, circuit-breaker `productService`):
   - `getProduct(uuid)` → price/stock snapshot; if stock is insufficient the order is REJECTED.
   - `reduceStock(uuid, qty, orderId)`.
   - Line totals computed from the fetched price; the product **name/price are snapshotted** onto the
     order item (so the order is immutable even if the catalogue changes later).
4. Order → CONFIRMED, total set.
5. **Publish `OrderConfirmedEvent` to Kafka** topic `order.confirmed` (fire-and-forget). This is the
   single notification trigger — the flow is event-driven.

**Other operations**: get by id (ownership-checked), list (paged, filterable by status/customer),
update status (state-machine enforced), cancel (restores stock for CONFIRMED orders — best-effort,
compensating), hard-delete (admin-only), and an internal `GET /api/v1/orders/product/{productId}/has-open`
used by product-service.

**Product id type**: product-service uses UUIDs, so order-service stores `product_id` as **text**
(`VARCHAR(64)`) — reconciled by Flyway migration `V4` (originally `BIGINT`). Product ids flow as opaque
strings end-to-end.

**Reconciliation sweep** (`ReconciliationSweepService`): a scheduled job (disabled/dry-run by default)
that retries stuck-PENDING orders and failed stock-restores recorded in `order_reconciliation_log`,
with a max-attempts budget before marking `RECONCILIATION_FAILED`.

**Caller identity**: `getOrder`, `cancel`, `delete` read `X-Customer-Id` / `X-User-Role` (injected by
the gateway) into a `CallerContext`; a non-admin can only act on their own orders (cross-customer
access returns 404, indistinguishable from "not found").

### 5.5 Notification Service (`Notification`, :8081)
Delivers notifications over three channels and is the Kafka **consumer**. PostgreSQL
(`notification_service`), `ddl-auto=update`.
- **Channels** (`NotificationChannel`): EMAIL (real SMTP via Spring Mail), SMS (mock sender with a
  configurable failure rate), IN_APP (persisted, queryable per recipient).
- **Entities**: `Notification` (recipient, channel, subject, message, `NotificationStatus`
  [e.g. PENDING/SENT/FAILED/READ], timestamps) and `NotificationTemplate` (named, per-channel templates
  with subject/body, preview, bulk import/duplicate, archive).
- **Controllers**: generic `NotificationController`, `EmailNotificationController`
  (`/api/v1/notifications/email`), `SmsNotificationController`, `InAppNotificationController`,
  history/reporting controllers, and template/catalogue controllers (`/api/v1/templates`).
- **Kafka consumer** (`OrderEventConsumer`, `@KafkaListener` on `order.confirmed`, group
  `notification-service`, `auto-offset-reset=earliest`): binds each record to `OrderPlacedEvent`
  (fields mirror the producer's JSON, including `confirmedAt`), maps it to an
  `OrderConfirmationRequestDto`, and hands it to the shared notification pipeline which formats and
  sends the order-confirmation email.
- **Dependency note**: uses the `spring-boot-starter-kafka` starter (Boot 4 ships the Kafka
  auto-configuration in the starter, not in the plain `spring-kafka` library) plus `@EnableKafka`.

---

## 6. Frontend (`sparta-ecommerce-frontend`, :5173)

**Stack**: React 18, Vite 5, React Router 6, Axios. No Redux — state via React Context. Served by
**nginx** in the container (production build) or the Vite dev server locally.

**Structure**:
```
src/
├── main.jsx                # entry; BrowserRouter + AuthProvider + CartProvider
├── App.jsx                 # routes (public /login, /register; the rest behind RequireAuth)
├── index.css               # the whole design system (glassmorphism theme, light+dark)
├── api/                    # axiosClient (JWT interceptor) + authApi/productApi/orderApi/notificationApi
├── auth/session.js         # token/user in localStorage (shared with the axios client)
├── context/                # AuthContext (who's logged in), CartContext (cart state)
├── components/             # Navbar, Modal, cart/, checkout/, products/, orders/, notifications/
├── pages/                  # LoginPage, RegisterPage, ProductsPage, OrdersPage, NotificationsPage
└── utils/                  # logger, productVisuals (emoji/gradient per product)
```

**Authentication (UI)**:
- **Login** by email + password → gateway `/api/v1/auth/login`; the JWT + `{email, role, customerId}`
  are stored in `localStorage`; the app maps this to a user object (`id = customerId`, `role`, `email`).
- **Registration** is a 3-step flow (`RegisterPage`): email → OTP (emailed) → set password → sign in.
- The Axios request interceptor attaches `Authorization: Bearer <token>`; a 401 clears the session and
  redirects to `/login`. Route protection via `RequireAuth`.

**Role-based UI**:
- **Customer** (USER): Shop, cart/checkout, **My Orders** (their own orders only). No Notifications tab.
- **Admin**: the above plus **catalogue management** (add/edit/delete products, hidden behind a "Manage
  catalog" toggle), **All Orders** (every customer, full details), and the **Notifications** admin view.
  The `/notifications` route is guarded — a customer who navigates there is redirected to the shop.

**API integration / proxy**: the app calls clean `/api/v1/...` paths. In dev the Vite server proxies
`/api` → gateway :8083; in the container nginx reverse-proxies `/api/**` → `api-gateway:8083`. Either
way requests are same-origin, so **no CORS**. Client-side routes fall back to `index.html`.

**Storefront**: product grid with image-style tiles (emoji + gradient), category chips, search, rating,
price, and an "Add to cart" button; a slide-out cart drawer; a checkout modal that collects shipping
details and places the order.

---

## 7. Database design

Database-per-service (four independent PostgreSQL databases in one server). Credentials (local/dev):
user `postgres`, password `Tech@123`.

### 7.1 `gateway_service` (API Gateway) — `ddl-auto=update`
**`users`**

| Column | Type | Notes |
|---|---|---|
| id | BIGINT (identity) | PK |
| email | VARCHAR, unique | login identifier |
| password_hash | VARCHAR | BCrypt; null until set-password |
| role | VARCHAR (enum) | ADMIN \| USER |
| status | VARCHAR (enum) | PENDING_OTP \| OTP_VERIFIED \| ACTIVE |
| otp_code | VARCHAR | transient during registration |
| otp_expires_at | TIMESTAMP | 10-min OTP validity |

### 7.2 `product_service` (Product Service) — `ddl-auto=update`
**`products`**

| Column | Type | Notes |
|---|---|---|
| id | UUID | PK (generated) |
| product_name | VARCHAR(150) | |
| description | VARCHAR(1000) | |
| category | VARCHAR(100) | |
| price | DECIMAL(10,2) | |
| stock_quantity | INT | |
| status | VARCHAR(20) | ACTIVE \| DISCONTINUED |
| created_at / updated_at | TIMESTAMP | lifecycle timestamps |
| version | BIGINT | optimistic lock (`@Version`) |

### 7.3 `order_service` (Order Service) — Flyway, `ddl-auto=validate`
Migrations: `V1__init_schema`, `V2__seed_sample_orders`, `V3__reconciliation_sweep`,
`V4__product_id_to_varchar` (product_id BIGINT → VARCHAR(64) for UUID references).

**`orders`**: `order_id` (BIGINT, sequence), `customer_id`, `customer_name`, `customer_email`,
`shipping_address`, `status` (PENDING|CONFIRMED|REJECTED|CANCELLED|SHIPPED|DELIVERED|RECONCILIATION_FAILED),
`total_amount` DECIMAL(12,2), `created_at`, `updated_at`. Indexed on customer_id, status, created_at.

**`order_items`**: `order_item_id` (BIGINT, sequence), `order_id` (FK → orders, ON DELETE CASCADE),
`product_id` **VARCHAR(64)** (the product UUID), `product_name_snapshot`, `unit_price_snapshot`
DECIMAL(12,2), `quantity`, `subtotal` (generated column = unit_price × quantity). Unique (order_id,
product_id).

**`order_reconciliation_log`**: durable record of reconciliation work (stuck-pending / failed stock
restore) — `reconciliation_log_id`, `order_id`, `event_type` (STUCK_PENDING|RESTORE_FAILED),
`product_id` VARCHAR(64), `quantity`, `log_status` (OPEN|RESOLVED|EXHAUSTED), `attempt_count`,
timestamps.

**`flyway_schema_history`**: Flyway's own migration bookkeeping.

### 7.4 `notification_service` (Notification Service) — `ddl-auto=update`
**`notifications`**: id, recipient, channel (EMAIL|SMS|IN_APP), subject, message, status
(PENDING|SENT|FAILED|READ…), timestamps (and read-state for in-app). **`notification_templates`**:
named per-channel templates (subject/body, archived flag) supporting preview, duplicate, and bulk
import.

---

## 8. Authentication & security

**End-to-end flow:**
1. **Register** (optional): `POST /api/v1/auth/register {email}` → gateway stores a PENDING_OTP user,
   generates a 6-digit OTP (10-min TTL), and emails it via the notification service.
2. `POST /api/v1/auth/verify-otp {email, otp}` → OTP_VERIFIED.
3. `POST /api/v1/auth/set-password {email, password, confirmPassword}` → BCrypt hash, status ACTIVE.
4. **Login** `POST /api/v1/auth/login {email, password}` → HS256 JWT (24 h) + `{email, role, customerId}`.
5. The SPA stores the token and sends `Authorization: Bearer <token>` on every call.
6. The **gateway validates** the JWT on all proxied routes, enforces role rules, strips client-supplied
   identity headers, and injects **trusted** `X-Customer-Id` / `X-User-Role` / `X-User-Email`.
7. Downstream services trust those headers (they are only reachable via the gateway on the public
   network); order-service uses them for per-customer ownership checks.

**Security properties**: BCrypt password hashing; JWT signed with a Base64 HS256 secret
(`app.jwt.secret`, override via `JWT_SECRET`); identity-header spoofing prevented at the gateway;
role-based authorization (public / authenticated / admin-only) centralised in the gateway filter;
row-level ownership enforced in order-service.

---

## 9. Messaging (Kafka, event-driven)

- **Broker**: Confluent `cp-kafka` 7.6.1 with `cp-zookeeper` 7.6.1 (containers). In-cluster address
  `kafka:9092`; host access on `localhost:29092`.
- **Topic**: `order.confirmed` (single partition, single replica — local-dev sizing; auto-created).
- **Producer** (order-service): on order confirmation, publishes `OrderConfirmedEvent`
  `{orderId, customerId, customerName, customerEmail, totalAmount, items[{productName, quantity}],
  confirmedAt}`, keyed by `orderId` (per-order ordering). Fire-and-forget: a broker outage is logged,
  never fails the order transaction.
- **Consumer** (notification-service): `@KafkaListener(topics = "order.confirmed")`, group
  `notification-service`, `auto-offset-reset=earliest`, JSON deserialised to the service's own
  `OrderPlacedEvent` (decoupled copy; services agree only on the JSON contract). It maps the event to
  an order-confirmation and runs it through the notification pipeline (which sends the email).
- **Why events here**: order placement and notification delivery are decoupled — the order succeeds
  and returns immediately; notification happens asynchronously and can be retried without affecting the
  order path.

---

## 10. Inter-service communication

| From → To | Purpose | Mechanism | Notes |
|---|---|---|---|
| Frontend → Gateway | all API calls | HTTP (same-origin via proxy) | JWT in `Authorization` |
| Gateway → services | routing | HTTP, `lb://` (Eureka) | path-based, no prefix strip |
| Order → Product | get product, reduce/restore stock | **REST** (Spring `RestClient`) | circuit-breaker `productService`; JDK HttpClient (PATCH) |
| Product → Order | "has open orders?" before discontinue | **OpenFeign** (`order-service`) | Eureka-resolved |
| Gateway → Notification | send registration OTP email | **OpenFeign** (`notification-service`) | `POST /api/v1/notifications/email` |
| Order → Notification | order confirmation | **Kafka** (`order.confirmed`) | async, event-driven |
| All services → Eureka | register / discover | Eureka client | heartbeats |

---

## 11. Resilience & fault tolerance

- **Circuit breaker** (Resilience4j, order-service, instance `productService`): trips on repeated
  product-service failures and fails fast with 503; business outcomes (product-not-found,
  insufficient-stock) are configured as `ignore-exceptions` so they don't trip the breaker. A
  **bulkhead** limits concurrent product calls.
- **Timeouts**: mandatory connect/read timeouts on the order→product RestClient so a slow downstream
  fails fast rather than exhausting threads.
- **Best-effort compensation**: on order cancel, stock restore is attempted; a failure is recorded in
  `order_reconciliation_log` rather than aborting the cancellation.
- **Reconciliation sweep**: a scheduled retrier (off/dry-run by default) that resolves stuck-pending
  orders and failed restores, with a max-attempts budget → `RECONCILIATION_FAILED` for manual review.
- **Kafka fire-and-forget**: order publishing never fails the order; the consumer's error handler
  retries transient failures.
- **Startup ordering** (Docker): services depend on a **healthy** Postgres/Kafka; Eureka registration
  and gateway route propagation are retry-tolerant (a brief window after start may 503 until routes
  refresh).

---

## 12. Configuration & environment

Key environment variables (wired by `docker-compose.yml`):

| Variable | Services | Meaning |
|---|---|---|
| `EUREKA_URI` | all | `http://eureka:8761/eureka` |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | gateway, product, order, notification | Postgres datasource |
| `KAFKA_BOOTSTRAP_SERVERS` | order, notification | `kafka:9092` |
| `PRODUCT_SERVICE_URL` | order | product-service base URL |
| `SERVER_PORT` | all | service HTTP port |
| `JWT_SECRET` | gateway | Base64 HS256 key (dev default baked in) |
| `OTP_DEV_RETURN` | gateway | `false` (real email); `true` surfaces OTP in the response for dev |
| `SPRING_MAIL_USERNAME` / `SPRING_MAIL_PASSWORD` | notification | Gmail SMTP creds (from a git-ignored `.env`) |

**Email**: `smtp.gmail.com:587`, STARTTLS. Real credentials are provided via a git-ignored `.env` at
the project root that docker compose auto-loads. OTP and order-confirmation emails are delivered for
real; SMS is a mock sender.

**Ports**: gateway 8083, eureka 8761, product 8082, order 8080, notification 8081, frontend 5173,
Postgres host 5433 (→5432), Kafka host 29092 (→9092).

---

## 13. API reference

Base URL through the gateway: `http://localhost:8083`. `GET /api/v1/products/**` and `/api/v1/auth/**`
are public; everything else requires a Bearer token (product writes and order status/delete are ADMIN-only).

**Auth** (`/api/v1/auth`): `POST /register`, `POST /verify-otp`, `POST /set-password`, `POST /login`.

**Products** (`/api/v1/products`): `GET` (list), `POST` (create, admin), `GET /{id}`,
`PUT /update/{id}` (admin), `PATCH /{id}/price` (admin), `GET /availability/{id}?quantity=`,
`PATCH /reduce/stock/{id}`, `PATCH /adjust/stock/{id}` (admin), `DELETE /{id}` (admin).

**Orders** (`/api/v1/orders`): `POST` (create), `GET /{id}`, `GET` (list, paged, `?status=&customerId=&page=&size=`),
`PATCH /{id}/status` (admin), `POST /{id}/cancel`, `GET /product/{productId}/has-open` (internal).

**Notifications** (`/api/v1/notifications`): `GET` (list), `POST /email`, `POST /sms`, `POST /in-app`,
`GET /in-app/user/{recipient}`, `PATCH /in-app/{id}/read`, `GET /history`; templates under `/api/v1/templates`.

Each backend service also exposes Swagger UI (springdoc) for its own endpoints.

---

## 14. Deployment (Docker)

A single **`docker-compose.yml`** (project root) builds and runs the entire stack:

- **Infrastructure**: `postgres` (16; an init script creates the four databases; host port 5433),
  `zookeeper` + `kafka` (Confluent 7.6.1; host port 29092).
- **Services**: `eureka`, `api-gateway`, `product-service`, `order-service`, `notification-service`,
  `frontend` — each built from a multi-stage Dockerfile (Maven build → JRE runtime; the frontend is
  node build → nginx).
- **Wiring**: a shared bridge network (`sparta-net`); services resolve each other by container name;
  `depends_on` gates app startup on healthy Postgres/Kafka; env vars point at container hostnames.
- **Volumes**: a named Postgres data volume; `docker/postgres-init/01-init-databases.sql` seeds the
  four databases on first initialisation.

Build strategy is self-contained: `docker compose up --build` compiles every service from source inside
its image — no host build required.

---

## 15. Running & demo guide

```bash
# from the project root (where docker-compose.yml lives)
docker compose up -d --build          # brings up the whole stack (backend + frontend)
```
- **App**: http://localhost:5173  ·  **Eureka**: http://localhost:8761  ·  **Gateway**: http://localhost:8083
- Cold start takes ~40–60s for the Java services; a brief 502 window is normal until routes propagate.

**Accounts**: `user@gmail.com` / `1234` (customer), `admin@gmail.com` / `1234` (admin). Or **Create
account** to register a new user (the OTP is emailed to the address you enter).

**Demo path**: sign in → browse the shop → add to cart → checkout (enter shipping) → place order →
see it under **My Orders** (customer) or **All Orders** (admin) → an order-confirmation email is sent.
Admins can toggle **Manage catalog** to add/edit/delete products.

**Stop**: `docker compose down` (keep data) or `docker compose down -v` (wipe the DB volume).

---

## 16. Key design decisions

- **Database-per-service**: each service owns its schema; no shared tables — services integrate only
  over APIs/events.
- **Gateway as the single security boundary**: JWT validation, role checks, and identity-header
  injection happen once, at the edge; downstream services stay simple.
- **Event-driven notifications**: order → notification is asynchronous over Kafka so notification
  delivery never blocks or fails order placement (exactly one confirmation per order).
- **Snapshotting** order line items (product name + price at purchase time) keeps historical orders
  correct even as the catalogue changes.
- **Product id as opaque text in order-service**: product-service uses UUIDs; order-service stores the
  UUID as text rather than forcing a shared numeric id — services stay independently evolvable.
- **Real identity system** (DB users, BCrypt, email-OTP, roles) instead of hardcoded credentials.
- **Self-contained Docker**: one command, no pre-build, infrastructure included — reproducible for
  graders/teammates.

---

## 17. Known limitations & production hardening

- **Secrets**: dev credentials (`Tech@123`, the Gmail app password, the Base64 JWT secret) are for local
  use. For production: externalise to a secrets manager, rotate the Gmail app password, set a strong
  `JWT_SECRET`, and never commit `.env`/`application-secrets.properties`.
- **OTP delivery**: relies on Gmail SMTP; a production system would use a transactional email provider
  and rate-limit registration.
- **Order list authorization**: the list endpoint filters by `customerId` but isn't row-level scoped in
  the service (the UI scopes customers to their own id); production should enforce this server-side.
- **Kafka sizing**: single partition/replica for local dev; production needs replication and
  partitioning for throughput/HA.
- **Test coverage**: order/product/notification have unit/slice tests; the gateway auth layer is lightly
  tested — add integration tests for the auth + routing filter.
- **Observability**: add centralised logging, metrics (Micrometer/Prometheus), and tracing across the
  Kafka + HTTP hops for production readiness.

---

## 18. Repository / folder layout

```
Desktop/
├── docker-compose.yml                 # unified stack (infra + all services + frontend)
├── .env                               # SMTP creds (git-ignored) — auto-loaded by compose
├── docker/postgres-init/01-init-databases.sql
├── README.md                          # entry-point run guide
├── PROJECT_SUMMARY_AND_CHANGES.md     # audit + all fixes/changes log
├── SPARTA_PROJECT_DOCUMENTATION.md    # (this document)
│
├── eureka/eureka/                     # Eureka server            (:8761)
├── API-Gateway/                       # gateway + auth/OTP/JWT    (:8083, DB gateway_service)
├── sparta-product-service/            # product catalogue         (:8082, DB product_service)
├── sparta-order-service/              # orders + Kafka producer   (:8080, DB order_service, Flyway)
├── Notification/                      # notifications + Kafka consumer (:8081, DB notification_service)
└── sparta-ecommerce-frontend/         # React + Vite SPA          (:5173, nginx)
```

Each backend service is an independent Maven project (`pom.xml`, `src/main/java`, `src/main/resources`,
tests under `src/test`), containerised via its own `Dockerfile`.

---

_Generated as a complete reference for the Sparta e-commerce project. For the run guide see
`README.md`; for the detailed history of what was audited and fixed see `PROJECT_SUMMARY_AND_CHANGES.md`._
