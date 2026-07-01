# Discount API Bug Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the Ktor/MongoDB discount service so it satisfies the README requirements, passes the existing failing tests, adds validation coverage for business-critical edge cases, documents the fixes, and remains safe under concurrent discount requests.

**Architecture:** Preserve the existing Ktor route -> service -> repository structure. Keep request parsing and HTTP status mapping in `Application.kt`, business validation and price calculation in `ProductService.kt`, country VAT lookup in `VatConfig.kt`, and persistence/concurrency guarantees in `ProductRepository.kt`. Use MongoDB atomic update operators for discount application instead of read-modify-write so concurrent requests append unique discount IDs without overwriting each other.

**Tech Stack:** Kotlin 2.2.20, Ktor 3.1.2, kotlinx.serialization, MongoDB Kotlin coroutine driver 5.2.1, Testcontainers MongoDB 1.20.6, Gradle 9.2.0, JDK 21, Colima Docker runtime on this machine.

---

## Source Specification

- Input spec: `docs/superpowers/plans/2026-06-30-discount-api-bug-fixes.md`
- Original assignment: `README.md`
- Application root: `discount/`
- Key business rules:
  - `GET /products?country={country}` returns JSON product responses for the requested country.
  - Missing `country` query parameter returns HTTP 400.
  - `PUT /products/{id}/discount` accepts JSON and returns JSON or HTTP 404 for missing products.
  - Final price is `basePrice x (1 - d1) x (1 - d2) x ... x (1 + VAT)`, not additive discount summing.
  - Known VAT rates are Sweden 25%, Germany 19%, France 20%.
  - Unknown countries are handled by the current tested behavior: products can be returned and final price equals base price because VAT defaults to `0.0`.
  - Applying the same `discountId` is idempotent.
  - Applying different discounts concurrently must not lose updates.
  - Invalid discount requests must be rejected with clear validation behavior.
  - Add `FIXES.md` explaining bugs found and fixes applied.

## Current Failure Evidence

Plain `./gradlew test` can fail before application logic if Testcontainers cannot discover Docker. On this machine, use Colima-compatible Testcontainers environment variables:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew test --rerun-tasks
```

Known application failures from the existing spec:

```text
12 tests completed, 4 failed
GET products endpoint should return JSON: expected 200 OK but was 406 Not Acceptable
PUT discount endpoint should accept and return JSON: Serializer for class 'ApplyDiscountRequest' is not found
should calculate final price correctly with multiple discounts: expected 428.4, actual 416.5
should handle concurrent discount applications safely - NO DUPLICATES: expected 26 but was 2
```

## File Structure

- Modify `discount/gradle/libs.versions.toml`: add the Kotlin serialization compiler plugin alias that matches the Kotlin JVM plugin version.
- Modify `discount/app/build.gradle.kts`: apply the serialization compiler plugin so existing `@Serializable` DTOs get generated serializers.
- Modify `discount/app/src/main/kotlin/io/nexure/discount/Application.kt`: install server-side Ktor JSON content negotiation, keep endpoint routing unchanged, and map invalid discount requests to HTTP 400.
- Modify `discount/app/src/main/kotlin/io/nexure/discount/service/ProductService.kt`: validate discount input and calculate final prices by applying discounts multiplicatively before VAT.
- Modify `discount/app/src/main/kotlin/io/nexure/discount/repository/ProductRepository.kt`: use a single upsert for `save()` and a single atomic MongoDB update for `applyDiscount()`.
- Modify `discount/app/src/test/kotlin/io/nexure/discount/ProductServiceTests.kt`: add focused service validation tests for blank discount IDs and invalid percentages.
- Modify `discount/app/src/test/kotlin/io/nexure/discount/HttpEndpointTests.kt`: add focused HTTP validation tests for invalid discount JSON payloads.
- Create `FIXES.md`: document each bug, the implementation fix, edge-case decisions, and the verified test command.

---

### Task 0: Prepare the Working Tree and Baseline

**Files:**
- No source changes.

- [ ] **Step 1: Enter the repository root**

Run:

```bash
cd /Users/harsh.kumar01/Documents/personal/Electrolux-Tech/code-test-nexure-elux-2025
pwd
```

Expected:

```text
/Users/harsh.kumar01/Documents/personal/Electrolux-Tech/code-test-nexure-elux-2025
```

- [ ] **Step 2: Remove embedded credentials from the local Git remote if present**

Run:

```bash
git remote get-url origin
```

If the output contains credentials, run:

```bash
git remote set-url origin https://github.com/venture-falcon/code-test-nexure-elux-2025.git
git remote get-url origin
```

Expected:

```text
https://github.com/venture-falcon/code-test-nexure-elux-2025.git
```

- [ ] **Step 3: Confirm the working tree baseline**

Run:

```bash
git status --short
git branch --show-current
```

Expected:

```text
Branch is chore/add-code-test.
Only intentional local files are shown. If there are unrelated user changes, do not revert them.
```

- [ ] **Step 4: Verify Docker can reach the Colima runtime**

Run:

```bash
docker context ls
docker info --format '{{.ServerVersion}}'
```

Expected:

```text
The active Docker context is colima or docker info prints a Docker server version through the current context.
```

- [ ] **Step 5: Run the full failing suite once**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew test --rerun-tasks
```

Expected before implementation:

```text
BUILD FAILED
12 tests completed, 4 failed
```

If Docker is unavailable, stop implementation and record the blocker. Do not change application logic without a runnable verification path.

---

### Task 1: Enable Server and DTO JSON Serialization

**Files:**
- Modify: `discount/gradle/libs.versions.toml`
- Modify: `discount/app/build.gradle.kts`
- Modify: `discount/app/src/main/kotlin/io/nexure/discount/Application.kt`
- Test: `discount/app/src/test/kotlin/io/nexure/discount/HttpEndpointTests.kt`

- [ ] **Step 1: Run the targeted HTTP tests and capture the red state**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :app:test --tests 'io.nexure.discount.HttpEndpointTests' --rerun-tasks
```

Expected before implementation:

```text
GET products endpoint should return JSON fails with 406 Not Acceptable.
PUT discount endpoint should accept and return JSON fails because ApplyDiscountRequest has no registered serializer.
```

- [ ] **Step 2: Add the Kotlin serialization compiler plugin alias**

In `discount/gradle/libs.versions.toml`, replace the `[plugins]` block with:

```toml
[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "jvm" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "jvm" }
```

- [ ] **Step 3: Apply the serialization plugin to the app module**

In `discount/app/build.gradle.kts`, replace the `plugins` block with:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}
```

- [ ] **Step 4: Install server-side JSON content negotiation**

In `discount/app/src/main/kotlin/io/nexure/discount/Application.kt`, add these imports:

```kotlin
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json
```

Then, inside `fun Application.module()`, insert this immediately after the opening brace and before `val mongoConnectionString`:

```kotlin
install(ContentNegotiation) {
    json(
        Json {
            ignoreUnknownKeys = true
        }
    )
}
```

The top of `module()` should become:

```kotlin
fun Application.module() {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
            }
        )
    }

    val mongoConnectionString = environment.config.propertyOrNull("mongodb.uri")?.getString()
        ?: "mongodb://localhost:27017"
```

- [ ] **Step 5: Run the targeted HTTP tests and verify the green state for serialization**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :app:test --tests 'io.nexure.discount.HttpEndpointTests' --rerun-tasks
```

Expected after implementation:

```text
HttpEndpointTests > should return 400 when country parameter is missing() PASSED
HttpEndpointTests > GET products endpoint should return JSON() PASSED
HttpEndpointTests > PUT discount endpoint should accept and return JSON() PASSED
BUILD SUCCESSFUL
```

- [ ] **Step 6: Commit the serialization fix**

Run:

```bash
git add discount/gradle/libs.versions.toml discount/app/build.gradle.kts discount/app/src/main/kotlin/io/nexure/discount/Application.kt
git commit -m "fix: enable ktor json serialization"
```

Expected:

```text
Commit created with only serialization-related files staged.
```

---

### Task 2: Apply Multiple Discounts Multiplicatively

**Files:**
- Modify: `discount/app/src/main/kotlin/io/nexure/discount/service/ProductService.kt`
- Test: `discount/app/src/test/kotlin/io/nexure/discount/ProductServiceTests.kt`

- [ ] **Step 1: Run the targeted price test and capture the red state**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :app:test --tests 'io.nexure.discount.ProductServiceTests.should calculate final price correctly with multiple discounts' --rerun-tasks
```

Expected before implementation:

```text
Expected <428.4> with absolute tolerance <0.01>, actual <416.5>.
```

- [ ] **Step 2: Replace additive discount summing with multiplicative discount folding**

In `discount/app/src/main/kotlin/io/nexure/discount/service/ProductService.kt`, replace the entire `calculateFinalPrice()` function with:

```kotlin
private fun Product.calculateFinalPrice(): Double {
    val vatRate = VatConfig.getVatRate(country)
    val discountedPrice = discounts.fold(basePrice) { price, discount ->
        price * (1 - discount.percent / 100.0)
    }

    return discountedPrice * (1 + vatRate)
}
```

This keeps the existing unknown-country behavior because `VatConfig.getVatRate(country)` already returns `0.0` for unknown countries.

- [ ] **Step 3: Run single, multiple, VAT, and unknown-country price tests**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :app:test \
  --tests 'io.nexure.discount.ProductServiceTests.should calculate final price correctly with single discount' \
  --tests 'io.nexure.discount.ProductServiceTests.should calculate final price correctly with multiple discounts' \
  --tests 'io.nexure.discount.ProductServiceTests.should correctly apply VAT rates for different countries' \
  --tests 'io.nexure.discount.ProductServiceTests.should handle products from unknown countries' \
  --rerun-tasks
```

Expected after implementation:

```text
All four selected ProductServiceTests pass.
```

- [ ] **Step 4: Commit the pricing fix**

Run:

```bash
git add discount/app/src/main/kotlin/io/nexure/discount/service/ProductService.kt
git commit -m "fix: apply discounts multiplicatively"
```

Expected:

```text
Commit created with only ProductService.kt staged.
```

---

### Task 3: Make Discount Persistence Atomic and Idempotent

**Files:**
- Modify: `discount/app/src/main/kotlin/io/nexure/discount/repository/ProductRepository.kt`
- Test: `discount/app/src/test/kotlin/io/nexure/discount/ProductServiceTests.kt`

- [ ] **Step 1: Run the idempotency and concurrency tests and capture the red state**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :app:test \
  --tests 'io.nexure.discount.ProductServiceTests.should be idempotent when applying same discount twice sequentially' \
  --tests 'io.nexure.discount.ProductServiceTests.should handle concurrent discount applications safely - NO DUPLICATES' \
  --rerun-tasks
```

Expected before implementation:

```text
The sequential idempotency test may pass.
The concurrent discount test fails because read-modify-write loses updates and stores fewer than 26 discounts.
```

- [ ] **Step 2: Replace repository imports with the required MongoDB helpers**

In `discount/app/src/main/kotlin/io/nexure/discount/repository/ProductRepository.kt`, replace the current `com.mongodb.client.model` imports with:

```kotlin
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.Updates
```

Keep the existing coroutine-driver and model imports:

```kotlin
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.nexure.discount.model.Discount
import io.nexure.discount.model.Product
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
```

- [ ] **Step 3: Replace `save()` with a single upsert**

In `ProductRepository.kt`, replace the entire `save` method with:

```kotlin
suspend fun save(product: Product): Product {
    collection.replaceOne(
        Filters.eq("id", product.id),
        product,
        ReplaceOptions().upsert(true)
    )

    return product
}
```

This removes the insert race between `find()` and `insertOne()` while preserving the unique `id` index.

- [ ] **Step 4: Replace read-modify-write discount application with an atomic conditional push**

In `ProductRepository.kt`, replace the entire `applyDiscount` method with:

```kotlin
suspend fun applyDiscount(productId: String, discount: Discount): Product? {
    collection.updateOne(
        Filters.and(
            Filters.eq("id", productId),
            Filters.ne("discounts.discountId", discount.discountId)
        ),
        Updates.push("discounts", discount)
    )

    return findById(productId)
}
```

Expected behavior of this method:

```text
If the product does not exist, updateOne matches nothing and findById returns null.
If the same discountId already exists, updateOne matches nothing and findById returns the unchanged product.
If different discountIds arrive concurrently, each update uses MongoDB's atomic array push and no request overwrites another request's discounts.
```

- [ ] **Step 5: Run the idempotency and concurrency tests again**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :app:test \
  --tests 'io.nexure.discount.ProductServiceTests.should be idempotent when applying same discount twice sequentially' \
  --tests 'io.nexure.discount.ProductServiceTests.should handle concurrent discount applications safely - NO DUPLICATES' \
  --rerun-tasks
```

Expected after implementation:

```text
Both selected ProductServiceTests pass.
The stored product has 26 unique discount IDs after the concurrency phase.
```

- [ ] **Step 6: Run apply-discount happy path and missing-product tests**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :app:test \
  --tests 'io.nexure.discount.ProductServiceTests.should apply discount to product correctly' \
  --tests 'io.nexure.discount.ProductServiceTests.should return null when applying discount to non-existent product' \
  --rerun-tasks
```

Expected after implementation:

```text
Both selected ProductServiceTests pass.
```

- [ ] **Step 7: Commit the atomic persistence fix**

Run:

```bash
git add discount/app/src/main/kotlin/io/nexure/discount/repository/ProductRepository.kt
git commit -m "fix: apply discounts atomically"
```

Expected:

```text
Commit created with only ProductRepository.kt staged.
```

---

### Task 4: Add Discount Request Validation and HTTP 400 Mapping

**Files:**
- Modify: `discount/app/src/main/kotlin/io/nexure/discount/service/ProductService.kt`
- Modify: `discount/app/src/main/kotlin/io/nexure/discount/Application.kt`
- Modify: `discount/app/src/test/kotlin/io/nexure/discount/ProductServiceTests.kt`
- Modify: `discount/app/src/test/kotlin/io/nexure/discount/HttpEndpointTests.kt`

- [ ] **Step 1: Add service validation test imports**

In `discount/app/src/test/kotlin/io/nexure/discount/ProductServiceTests.kt`, add this import with the other `kotlin.test` imports:

```kotlin
import kotlin.test.assertFailsWith
```

- [ ] **Step 2: Add service validation tests**

In `ProductServiceTests.kt`, add these test methods inside `class ProductServiceTests`:

```kotlin
@Test
fun `should reject blank discount id`() = runBlocking {
    val error = assertFailsWith<IllegalArgumentException> {
        service.applyDiscount("prod-invalid", Discount("", 10.0))
    }

    assertEquals("discountId must not be blank", error.message)
}

@Test
fun `should reject zero negative and above one hundred discount percentages`() = runBlocking {
    val zeroPercentError = assertFailsWith<IllegalArgumentException> {
        service.applyDiscount("prod-invalid", Discount("ZERO", 0.0))
    }
    assertEquals("percent must be greater than 0 and less than or equal to 100", zeroPercentError.message)

    val negativePercentError = assertFailsWith<IllegalArgumentException> {
        service.applyDiscount("prod-invalid", Discount("NEGATIVE", -1.0))
    }
    assertEquals("percent must be greater than 0 and less than or equal to 100", negativePercentError.message)

    val highPercentError = assertFailsWith<IllegalArgumentException> {
        service.applyDiscount("prod-invalid", Discount("HIGH", 100.1))
    }
    assertEquals("percent must be greater than 0 and less than or equal to 100", highPercentError.message)
}
```

- [ ] **Step 3: Run the service validation tests and verify the red state**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :app:test \
  --tests 'io.nexure.discount.ProductServiceTests.should reject blank discount id' \
  --tests 'io.nexure.discount.ProductServiceTests.should reject zero negative and above one hundred discount percentages' \
  --rerun-tasks
```

Expected before implementation:

```text
Both selected tests fail because ProductService.applyDiscount does not validate discountId or percent.
```

- [ ] **Step 4: Add validation in ProductService before repository persistence**

In `discount/app/src/main/kotlin/io/nexure/discount/service/ProductService.kt`, replace the current `applyDiscount` expression body with this block body:

```kotlin
suspend fun applyDiscount(productId: String, discount: Discount): ProductResponse? {
    validateDiscount(discount)
    return repository.applyDiscount(productId, discount)?.toProductResponse()
}
```

Then add this private method inside `class ProductService`, below `applyDiscount` and above `toProductResponse()`:

```kotlin
private fun validateDiscount(discount: Discount) {
    require(discount.discountId.isNotBlank()) {
        "discountId must not be blank"
    }
    require(discount.percent > 0.0 && discount.percent <= 100.0) {
        "percent must be greater than 0 and less than or equal to 100"
    }
}
```

The beginning of `ProductService` should become:

```kotlin
class ProductService(private val repository: ProductRepository) {

    suspend fun getProductsByCountry(country: String): List<ProductResponse> =
        repository.findByCountry(country).map { it.toProductResponse() }

    suspend fun applyDiscount(productId: String, discount: Discount): ProductResponse? {
        validateDiscount(discount)
        return repository.applyDiscount(productId, discount)?.toProductResponse()
    }

    private fun validateDiscount(discount: Discount) {
        require(discount.discountId.isNotBlank()) {
            "discountId must not be blank"
        }
        require(discount.percent > 0.0 && discount.percent <= 100.0) {
            "percent must be greater than 0 and less than or equal to 100"
        }
    }

    private fun Product.toProductResponse(): ProductResponse = ProductResponse(
```

- [ ] **Step 5: Run service validation tests and verify the green state**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :app:test \
  --tests 'io.nexure.discount.ProductServiceTests.should reject blank discount id' \
  --tests 'io.nexure.discount.ProductServiceTests.should reject zero negative and above one hundred discount percentages' \
  --rerun-tasks
```

Expected after implementation:

```text
Both selected ProductServiceTests pass.
```

- [ ] **Step 6: Add HTTP validation tests**

In `discount/app/src/test/kotlin/io/nexure/discount/HttpEndpointTests.kt`, add these test methods inside `class HttpEndpointTests`:

```kotlin
@Test
fun `PUT discount endpoint should reject blank discount id`() = testApplication {
    environment {
        config = MapApplicationConfig("mongodb.uri" to mongoContainer.connectionString)
    }
    application {
        module()
    }

    val client = createClient {
        install(ContentNegotiation) {
            json()
        }
    }

    val response = client.put("/products/test-id/discount") {
        contentType(ContentType.Application.Json)
        setBody(ApplyDiscountRequest("", 10.0))
    }

    assertEquals(HttpStatusCode.BadRequest, response.status)
}

@Test
fun `PUT discount endpoint should reject invalid discount percent`() = testApplication {
    environment {
        config = MapApplicationConfig("mongodb.uri" to mongoContainer.connectionString)
    }
    application {
        module()
    }

    val client = createClient {
        install(ContentNegotiation) {
            json()
        }
    }

    val response = client.put("/products/test-id/discount") {
        contentType(ContentType.Application.Json)
        setBody(ApplyDiscountRequest("INVALID", -1.0))
    }

    assertEquals(HttpStatusCode.BadRequest, response.status)
}
```

- [ ] **Step 7: Run the HTTP validation tests and verify the red state**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :app:test \
  --tests 'io.nexure.discount.HttpEndpointTests.PUT discount endpoint should reject blank discount id' \
  --tests 'io.nexure.discount.HttpEndpointTests.PUT discount endpoint should reject invalid discount percent' \
  --rerun-tasks
```

Expected before HTTP mapping:

```text
Both selected tests fail because IllegalArgumentException is not translated to HTTP 400 yet.
```

- [ ] **Step 8: Translate service validation errors to HTTP 400**

In `discount/app/src/main/kotlin/io/nexure/discount/Application.kt`, replace the PUT route body from `val request = call.receive<ApplyDiscountRequest>()` through the existing `if (product == null) { ... } else { ... }` block with:

```kotlin
val request = call.receive<ApplyDiscountRequest>()
val product = try {
    service.applyDiscount(productId, Discount(request.discountId, request.percent))
} catch (e: IllegalArgumentException) {
    call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid discount request")
    return@put
}

if (product == null) {
    call.respond(HttpStatusCode.NotFound, "Product not found")
} else {
    call.respond(product)
}
```

The full PUT route should become:

```kotlin
put(PRODUCT_DISCOUNT_ENDPOINT) {
    val productId = call.parameters["id"]
    if (productId == null) {
        call.respond(HttpStatusCode.BadRequest, "Product ID is required")
        return@put
    }

    val request = call.receive<ApplyDiscountRequest>()
    val product = try {
        service.applyDiscount(productId, Discount(request.discountId, request.percent))
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid discount request")
        return@put
    }

    if (product == null) {
        call.respond(HttpStatusCode.NotFound, "Product not found")
    } else {
        call.respond(product)
    }
}
```

- [ ] **Step 9: Run all validation tests and existing PUT endpoint test**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :app:test \
  --tests 'io.nexure.discount.ProductServiceTests.should reject blank discount id' \
  --tests 'io.nexure.discount.ProductServiceTests.should reject zero negative and above one hundred discount percentages' \
  --tests 'io.nexure.discount.HttpEndpointTests.PUT discount endpoint should reject blank discount id' \
  --tests 'io.nexure.discount.HttpEndpointTests.PUT discount endpoint should reject invalid discount percent' \
  --tests 'io.nexure.discount.HttpEndpointTests.PUT discount endpoint should accept and return JSON' \
  --rerun-tasks
```

Expected after implementation:

```text
All selected validation and PUT endpoint tests pass.
```

- [ ] **Step 10: Commit validation and HTTP mapping**

Run:

```bash
git add discount/app/src/main/kotlin/io/nexure/discount/service/ProductService.kt discount/app/src/main/kotlin/io/nexure/discount/Application.kt discount/app/src/test/kotlin/io/nexure/discount/ProductServiceTests.kt discount/app/src/test/kotlin/io/nexure/discount/HttpEndpointTests.kt
git commit -m "fix: validate discount requests"
```

Expected:

```text
Commit created with service validation, HTTP 400 mapping, and validation tests staged.
```

---

### Task 5: Document Bugs, Fixes, and Verification

**Files:**
- Create: `FIXES.md`

- [ ] **Step 1: Create FIXES.md at the repository root**

Create `FIXES.md` with exactly this content:

```markdown
# Fixes

## Bugs Found

1. Ktor JSON response serialization was not installed on the server, so `GET /products?country=Sweden` returned `406 Not Acceptable` when the client requested JSON.
2. The Kotlin serialization compiler plugin was missing, so existing `@Serializable` request and response models did not have generated serializers at runtime.
3. Multiple discounts were added together before applying VAT, but the requirement and tests expect each discount to be applied multiplicatively in sequence.
4. Discount application used read-modify-write persistence, which lost updates when multiple requests applied different discounts concurrently.
5. Discount requests were accepted without validating blank `discountId` values or invalid `percent` values.

## Fixes Applied

1. Added the Kotlin serialization compiler plugin and installed Ktor server `ContentNegotiation` with kotlinx JSON.
2. Changed final price calculation to fold discounts over `basePrice`, then apply the country VAT rate.
3. Replaced repository read-modify-write discount updates with a MongoDB atomic `updateOne` guarded by `discounts.discountId != discountId` and using `$push`.
4. Replaced manual insert-or-replace save logic with `replaceOne(..., upsert = true)`.
5. Added service-level validation for `discountId` and `percent`, and mapped validation failures to HTTP 400 in the PUT endpoint.

## Edge-Case Decisions

1. Unknown countries keep the existing tested behavior: the API can return products for unknown countries, and VAT defaults to `0.0`, so `finalPrice` equals the discounted base price.
2. Applying the same discount ID twice remains idempotent. The second request returns the current product without duplicating the discount.
3. Applying different discount IDs concurrently preserves every unique discount ID because MongoDB performs each conditional array push atomically.

## Verification

On this machine, Testcontainers requires Colima-compatible Docker environment variables:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew test --rerun-tasks
```

Expected final result:

```text
BUILD SUCCESSFUL
```
```

- [ ] **Step 2: Commit documentation**

Run:

```bash
git add FIXES.md
git commit -m "docs: document discount api fixes"
```

Expected:

```text
Commit created with FIXES.md staged.
```

---

### Task 6: Full Regression Verification and Submission Readiness

**Files:**
- No additional source changes.

- [ ] **Step 1: Run the complete test suite**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew test --rerun-tasks
```

Expected:

```text
All ProductServiceTests pass.
All HttpEndpointTests pass.
BUILD SUCCESSFUL.
```

- [ ] **Step 2: Run a final focused HTTP test sweep**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :app:test --tests 'io.nexure.discount.HttpEndpointTests' --rerun-tasks
```

Expected:

```text
All HttpEndpointTests pass.
BUILD SUCCESSFUL.
```

- [ ] **Step 3: Review the final diff for scope control**

Run:

```bash
cd ..
git status --short
git diff --stat HEAD~4..HEAD
```

Expected:

```text
Working tree is clean.
Diff stat includes only discount service source, tests, Gradle config, and FIXES.md.
```

- [ ] **Step 4: Review recent commits**

Run:

```bash
git log --oneline --max-count=5
```

Expected:

```text
Recent commits include:
docs: document discount api fixes
fix: validate discount requests
fix: apply discounts atomically
fix: apply discounts multiplicatively
fix: enable ktor json serialization
```

- [ ] **Step 5: Prepare submission summary**

Use this summary when sharing the branch:

```text
Fixed Ktor JSON serialization, multiplicative discount pricing, atomic/idempotent MongoDB discount persistence, and invalid discount request handling. Added FIXES.md and validation tests. Verified with the full Gradle test suite using the Colima/Testcontainers environment required on this machine.
```

---

## Self-Review

- Spec coverage: The plan covers every README task: run tests, analyze failures, fix implementation code, add missing configuration, fix database concurrency, add validation/error handling, keep unknown-country behavior explicit, verify all tests, commit changes, and write `FIXES.md`.
- Existing test coverage: The plan uses current failing tests for JSON, multiplicative pricing, idempotency, concurrency, VAT rates, unknown countries, missing country parameter, and missing product discount application.
- Added test coverage: The plan adds service tests for blank IDs, zero/negative/over-100 percentages, plus HTTP tests that confirm invalid discount requests return 400.
- Placeholder scan: The plan contains no open-ended implementation steps, no deferred decisions, and no references to undefined methods or files.
- Type consistency: `ApplyDiscountRequest`, `Discount`, `Product`, `ProductResponse`, `ProductService.applyDiscount`, `ProductRepository.applyDiscount`, and `VatConfig.getVatRate` match the current codebase names.
- Verification path: Each behavior change has a red check before code and a green check after code, followed by a full-suite verification.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-30-discount-api-bug-fixes-in-depth.md`. Two execution options:

1. **Subagent-Driven (recommended)** - dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** - execute tasks in this session using executing-plans, with batch execution and checkpoints.
