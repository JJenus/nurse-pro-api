# AGENTS.md

Backend workspace root: `nurse-pro-api` (Spring Boot 3.5.4, Java 17, Maven wrapper, PostgreSQL via JPA). The frontend is a separate repo at `../nurse-pro`.

## Commands

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
./mvnw clean package -DskipTests   # -> target/nursepro.jar (finalName)
```

- `application.yml` sets `spring.profiles.active: prod`; a bare `spring-boot:run` connects to the live AlwaysData PostgreSQL from `application-prod.yml` and needs `DB_PASSWORD`. Always pass the `dev` profile.
- `dev` expects PostgreSQL at `localhost:5432/nurse_db` (`postgres` / `postgres` by default). The schema is managed by Flyway migrations in `src/main/resources/db/migration` (`ddl-auto: none`). Add a new `V<n>__name.sql` for every schema change; never edit an applied one. `spring.flyway.baseline-on-migrate` baselines databases that predate Flyway at version 0, and V1 is a no-op there.
- `scripts/verify-migrations.sh` rehearses the migrations on a scratch PostgreSQL database with dirty legacy data (see the header for usage). Extend it when you add a migration that cleans or constrains data.
- `docker-compose.yml` overrides the datasource to `db:5432/nursepro_db` (PostgreSQL 17) and sets the `dev` profile.
- Tests: unit tests (validator, generator, reschedule and swap planners, shift times) need no database. `NurseProApiApplicationTests.contextLoads` boots the whole app and needs a reachable PostgreSQL; run it with `SPRING_PROFILES_ACTIVE=dev` (CI runs it against a Postgres service with `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`, which also checks the entities against the migrated schema).

## Architecture

- Controllers are the route truth, not the README. Actual routes live in `controller/NurseController.java` and `controller/ScheduleController.java`.
- Schedule routes: list/detail, `/{id}/conflicts`, `/{id}/validate`, `/{id}/status`, `/{id}/reschedule` (preview by default), `/{id}/changes` (audit log), `/shifts` CRUD plus `/shifts/validate` and `/shifts/move`, `/swap-requests` (list, create, approve, reject, cancel), `/generate` (supports an `Idempotency-Key` header and `replaceDraft`). The overhaul plan and its status are in `docs/SCHEDULE_OVERHAUL_PLAN.md`.
- Responses use `dto/ApiResponse` (`{ data, message, success }`). The export endpoint is the exception and returns raw bytes with `Content-Disposition`.
- Security is effectively open: `SecurityConfig` uses `.anyRequest().permitAll()` and `JwtAuthenticationFilter.isPermittedPath()` hardcodes `return true`. The README's JWT/auth claims are aspirational; don't spend time minting tokens.
- Base package is `com.surf.nursepro.nurse_pro_api` (underscore). Entity IDs are generated UUID strings, not numeric.
- CORS origins come from `app.cors.allowed-origins` in the active profile (`application-dev.yml` / `application-prod.yml`).
- The scheduling rules live in `service/ScheduleValidator.java` (the single source of truth for what is a conflict) and the algorithm in `service/ScheduleGenerator.java` (deterministic, seedable, constraint based); `ReschedulePlanner` and `SwapPlanner` are pure helpers. Keep them free of repositories so they stay unit-testable. A schedule stores the constraints it was generated with (`rules_json`).

## Config

- Swagger UI: `/swagger-ui.html`; OpenAPI JSON: `/api-docs` (not `/v3/api-docs`).
- Actuator exposes `health,metrics,prometheus` under `/actuator`.
- `application-prod.yml` reads `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` from the environment. The password is not in git; on AlwaysData it lives in `~/.config/nursepro/env` (mode 600), loaded by `deploy/alwaysdata-java`.

## Deploy / ops

- Live site: `https://nursepro.alwaysdata.net`; health is `GET /actuator/health`. The AlwaysData user-program command runs `java -jar nurpro.jar --server.address=:: --server.port=$PORT`.
- `deploy/alwaysdata-java` must stay installed at `~/.local/bin/java`. AlwaysData injects `JAVA_TOOL_OPTIONS` with a 192m heap plus 192m metaspace, which OOM-kills startup; the wrapper replaces that profile with a 128m heap and lazy initialization.
- `build-prod.sh` packages, uploads the wrapper and `target/nursepro.jar` to `~/nurpro.jar`, and restarts through the AlwaysData API when `ALWAYSDATA_API_TOKEN` is set. Set `DB_PASSWORD` to refresh `~/.config/nursepro/env`.
- CI is `.github/workflows/deploy-api.yml`: build on PR/push, deploy on `main`. Required repo secrets: `ALWAYSDATA_SSH_KEY` and `DB_PASSWORD`. Optional `ALWAYSDATA_API_TOKEN` enables the restart step; without it the workflow uploads the jar and asks for a manual restart. The site ID is resolved by its `nursepro.alwaysdata.net` address.
- AlwaysData requires an explicit restart after replacing the jar; the supervised process is not visible from the SSH namespace, so `pkill` cannot reach it. Restart from **Web > Sites** unless the API token is set.
- `Dockerfile` builds image `nursepro-api:1.0`. `restart-docker.sh` and `copy-nursepro-image.sh` hardcode `/home/jjenus/workbench/apps/xhar_nurse/nurse-pro-api`, which does not match this checkout. Fix the path or run the equivalent `docker` commands manually.
