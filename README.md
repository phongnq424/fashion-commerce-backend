# Fashion Commerce Backend

Java 21, Spring Boot 4.1.1, Spring Security 7.1.1 (Boot dependency management), Maven, PostgreSQL 18 and Flyway. Existing six migrations define 48 tables. Authentication uses a modular monolith with database-backed platform and shop permissions.

## Run

1. Install/select JDK 21 and set `JAVA_HOME` (IntelliJ Project SDK and Maven Runner JRE: 21).
2. Copy `.env.example` to `.env`, then fill `DB_HOST`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` for your development database. The existing four-variable JDBC configuration and automatic `.env` import are preserved; individual IntelliJ environment entries are unnecessary.
3. Generate a signing key locally. In PowerShell:

   ```powershell
   $keyBytes = [byte[]]::new(32)
   $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
   $random.GetBytes($keyBytes)
   $random.Dispose()
   [Convert]::ToBase64String($keyBytes)
   ```

   Store the output as `JWT_SECRET_BASE64` in `.env`; do not share or commit it. `JWT_ISSUER` defaults to `fashion-commerce`. Access TTL defaults to `15m`, refresh TTL to `14d`. Access TTL is limited to 15 minutes; refresh TTL to 30 days. Both must be at least one second.
4. Migration execution is **off by default** (`DB_MIGRATIONS_ENABLED=false`). Use an already migrated development database, or enable migrations **only after verifying the configured endpoint is a separate development/test branch**. Never enable/run migrations or integration tests against Neon `production`. Existing migrations were not changed and Hibernate remains `ddl-auto: validate`.
5. From the repository root:

   ```powershell
   .\mvnw.cmd test
   .\mvnw.cmd verify
   .\mvnw.cmd spring-boot:run
   ```

   Alternatively run `FashionCommerceBackendApplication` in IntelliJ, with this repository as the working directory so `.env` is found. Do not enable HTTP-body, Hibernate bind-parameter, or token logging. Spring Web DEBUG logging is explicitly suppressed because validation exceptions can contain rejected passwords.

Startup validates mapped entity columns and performs read-only checks for required authentication/RBAC tables, successful Flyway versions `1, 1.1, 2, 3, 4, 5`, failed history entries, unique indexes, composite-scope constraint names and the PLATFORM `CUSTOMER` role. Missing items stop startup with their names. No schema creation, repair, reset or production mutation was performed during implementation. Live database startup validation remains pending a separate test environment.

## API

| Method | Endpoint | Access | Success |
|---|---|---|---|
| POST | `/api/v1/auth/register` | Public | 201, safe user DTO |
| POST | `/api/v1/auth/login` | Public | 200, token DTO |
| POST | `/api/v1/auth/refresh` | Valid opaque refresh token in JSON | 200, rotated token DTO |
| POST | `/api/v1/auth/logout` | Bearer access token and that user's current refresh token | 204 |
| GET | `/api/v1/users/me` | Bearer access token, active account, `account.read_own` | 200, safe user DTO |

Use [docs/auth.http](docs/auth.http) in IntelliJ HTTP Client to register, log in, read the current user, rotate tokens, reject reuse, log out and check revocation. It targets localhost and uses example data; change the email between runs. Full DTOs, validation, errors, policies, SQL relationships and extension examples are in [docs/authentication.md](docs/authentication.md). The complete absolute file inventory and verification results are in [docs/implementation-report.md](docs/implementation-report.md).

Tokens currently travel in JSON and `Authorization: Bearer ...`. This API does not authenticate from cookies, uses no HTTP session, and disables CSRF accordingly. Browser frontend integration must choose secure storage first: prefer an HttpOnly/Secure refresh cookie and add an appropriate CSRF/SameSite policy before enabling cookie authentication. `CORS_ALLOWED_ORIGINS` is a comma-separated list of exact origins; no cross-origin browser access is allowed by default, and credentials are not enabled.

Unverified ACTIVE accounts may log in at this milestone; registration leaves `email_verified_at` null. Email verification is not implemented. Logout revokes refresh capability for one session; issued access JWTs remain usable until expiration, while account status and permissions are checked against the database on subsequent requests. There is no role-management API, default admin account, seller onboarding API, password reset, OAuth login or 2FA.

## PostgreSQL integration tests (no Docker)

Default `mvn test` executes unit/security tests without database access. PostgreSQL integration tests compile but are skipped unless **all** of the following are deliberately supplied in the shell/CI environment:

```powershell
$env:FC_RUN_POSTGRES_TESTS = 'ISOLATED_DATABASE_ONLY'
$env:TEST_DB_HOST = '<separate-test-branch-host>'
$env:TEST_DB_NAME = '<test-database>'
$env:TEST_DB_USERNAME = '<test-user>'
$env:TEST_DB_PASSWORD = '<test-password>'
.\mvnw.cmd '-Dtest=FashionCommerceBackendApplicationTests' test
```

Use dedicated test credentials with no production access. These test variables are intentionally read from the test process environment, **not** ordinary `.env`; placeholders in `.env.example` document their names. The initializer runs before DataSource/Flyway creation, requires every `TEST_DB_*` variable, rejects JDBC parameter injection, and refuses the ordinary DB endpoint from environment or `.env` (including Neon pooled/direct aliases). The explicit isolation marker is your assertion that the separate endpoint is a test branch: the application cannot discover a Neon branch name from arbitrary JDBC credentials.

The suite applies the six existing migrations on that separate database and validates entity mapping. Most tests roll back their transactions; concurrency tests commit isolated records and delete only the users they created. No `clean`, `TRUNCATE`, schema reset or Docker dependency is used. Keep the test database disposable and isolated. Tests cover registration uniqueness/atomic rollback, committed login failure counters, refresh rotation/revocation/expiry, concurrent registration and refresh, database role/permission changes, feature activation, ownership, shop membership/status and cross-shop access.

**Verification:** see [implementation report](docs/implementation-report.md) for actual executed results. PostgreSQL integration tests and live `flyway_schema_history` inspection have not been executed in this session because no isolated endpoint was supplied. Passing unit/security tests does not establish live PostgreSQL compatibility.
