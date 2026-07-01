# Discount API Bug Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the Ktor/MongoDB discount service so the README requirements and test suite pass, including JSON serialization, multiplicative pricing, idempotent discount application, and concurrent updates.

**Architecture:** Keep the existing three-layer shape: Ktor routes in `Application.kt`, business rules in `ProductService.kt`, and MongoDB persistence in `ProductRepository.kt`. Use MongoDB atomic update operators for discount application so concurrent requests do not overwrite each other.

**Tech Stack:** Kotlin 2.2.20, Ktor 3.1.2, kotlinx.serialization, MongoDB Kotlin coroutine driver 5.2.1, Testcontainers MongoDB, Gradle 9.2.0, JDK 21.

---

## Current Findings

- Project root: `code-test-nexure-elux-2025`.
- README file read: `README.md`.
- Initial plain `./gradlew test` failed before app logic because Testcontainers could not discover Docker.
- This machine uses Colima. The test command that reaches application logic is:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew test --rerun-tasks
```

- With the Colima env vars, 8 tests pass and 4 fail:
  - `GET products endpoint should return JSON`: expected 200, got 406.
  - `PUT discount endpoint should accept and return JSON`: serializer missing for `ApplyDiscountRequest`.
  - `should calculate final price correctly with multiple discounts`: expected 428.40, got 416.50.
  - `should handle concurrent discount applications safely - NO DUPLICATES`: expected 26 discounts, got 2.

## File Structure

- Modify `discount/gradle/libs.versions.toml`: add a Kotlin serialization compiler plugin alias.
- Modify `discount/app/build.gradle.kts`: apply the Kotlin serialization compiler plugin.
- Modify `discount/app/src/main/kotlin/io/nexure/discount/Application.kt`: install server-side JSON content negotiation and translate invalid discount requests to HTTP 400.
- Modify `discount/app/src/main/kotlin/io/nexure/discount/service/ProductService.kt`: calculate discounts multiplicatively and validate discount input before persistence.
- Modify `discount/app/src/main/kotlin/io/nexure/discount/repository/ProductRepository.kt`: replace read-modify-write discount updates with atomic MongoDB updates.
- Modify `discount/app/src/test/kotlin/io/nexure/discount/ProductServiceTests.kt`: add explicit validation tests for blank discount IDs and invalid percentages.
- Modify `discount/app/src/test/kotlin/io/nexure/discount/HttpEndpointTests.kt`: add an endpoint validation test for invalid discount requests.
- Create `FIXES.md`: document the bugs found, fixes applied, and local Colima/Testcontainers test command.

---

### Task 0: Verify Local Test Runtime

**Files:**
- No source changes.

- [ ] **Step 1: Confirm Docker CLI can reach Colima**

Run:

```bash
docker context ls
docker info --format '{{.ServerVersion}}'
```

Expected:

```text
The active context is `colima`.
`docker info` prints a Docker server version.
```

- [ ] **Step 2: Run the current failing test suite with Colima-compatible Testcontainers env**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew test --rerun-tasks
```

Expected before implementation:

```text
12 tests completed, 4 failed
GET products endpoint should return JSON: expected 200 OK but was 406 Not Acceptable
PUT discount endpoint should accept and return JSON: Serializer for class 'ApplyDiscountRequest' is not found
should calculate final price correctly with multiple discounts: expected 428.4, actual 416.5
should handle concurrent discount applications safely - NO DUPLICATES: expected 26 but was 2
```

---

### Task 1: Enable Ktor JSON Serialization

**Files:**
- Modify: `discount/gradle/libs.versions.toml`
- Modify: `discount/app/build.gradle.kts`
- Modify: `discount/app/src/main/kotlin/io/nexure/discount/Application.kt`
- Test: `discount/app/src/test/kotlin/io/nexure/discount/HttpEndpointTests.kt`

- [ ] **Step 1: Run the existing HTTP tests to verify the failure**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :app:test --tests 'io.nexure.discount.HttpEndpointTests' --rerun-tasks
```

Expected:

```text
GET products endpoint should return JSON fails with 406 Not Acceptable.
PUT discount endpoint should accept and return JSON fails because ApplyDiscountRequest has no registered serializer.
```

- [ ] **Step 2: Add the Kotlin serialization compiler plugin alias**

In `discount/gradle/libs.versions.toml`, update the `[plugins]` block to:

```toml
[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "jvm" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "jvm" }
```

- [ ] **Step 3: Apply the serialization plugin**

In `discount/app/build.gradle.kts`, update the `plugins` block to:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}
```

- [ ] **Step 4: Install server-side JSON content negotiation**

In `discount/app/src/main/kotlin/io/nexure/discount/Application.kt`, add imports:

```kotlin
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json
```

Then add this near the top of `fun Application.module()` before `routing { ... }`:

```kotlin
install(ContentNegotiation) {
    json(
        Json {
            ignoreUnknownKeys = true
        }
    )
}
```

- [ ] **Step 5: Run the HTTP tests**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :app:test --tests 'io.nexure.discount.HttpEndpointTests' --rerun-tasks
```

Expected:

```text
HttpEndpointTests > should return 400 when country parameter is missing() PASSED
HttpEndpointTests > GET products endpoint should return JSON() PASSED
HttpEndpointTests > PUT discount endpoint should accept and return JSON() PASSED
```

- [ ] **Step 6: Commit**

Run:

```bash
git add discount/gradle/libs.versions.toml discount/app/build.gradle.kts discount/app/src/main/kotlin/io/nexure/discount/Application.kt
git commit -m "fix: enable ktor json serialization"
```

---

### Task 2: Apply Multiple Discounts Multiplicatively

**Files:**
- Modify: `discount/app/src/main/kotlin/io/nexure/discount/service/ProductService.kt`
- Test: `discount/app/src/test/kotlin/io/nexure/discount/ProductServiceTests.kt`

- [ ] **Step 1: Run the existing price test to verify the failure**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :app:test --tests 'io.nexure.discount.ProductServiceTests.should calculate final price correctly with multiple discounts' --rerun-tasks
```

Expected:

```text
Expected <428.4> with absolute tolerance <0.01>, actual <416.5>.
```

- [ ] **Step 2: Change final price calculation from additive to multiplicative**

In `discount/app/src/main/kotlin/io/nexure/discount/service/ProductService.kt`, replace `calculateFinalPrice()` with:

```kotlin
private fun Product.calculateFinalPrice(): Double {
    val vatRate = VatConfig.getVatRate(country)
    val discountedPrice = discounts.fold(basePrice) { price, discount ->
        price * (1 - discount.percent / 100.0)
    }

    return discountedPrice * (1 + vatRate)
}
```

- [ ] **Step 3: Run the targeted price tests**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :app:test \
  --tests 'io.nexure.discount.ProductServiceTests.should calculate final price correctly with single discount' \
  --tests 'io.nexure.discount.ProductServiceTests.should calculate final price correctly with multiple discounts' \
  --rerun-tasks
```

Expected:

```text
Both selected ProductServiceTests pass.
```

- [ ] **Step 4: Commit**

Run:

```bash
git add discount/app/src/main/kotlin/io/nexure/discount/service/ProductService.kt
git commit -m "fix: apply discounts multiplicatively"
```

---

### Task 3: Make Discount Application Atomic

**Files:**
- Modify: `discount/app/src/main/kotlin/io/nexure/discount/repository/ProductRepository.kt`
- Test: `discount/app/src/test/kotlin/io/nexure/discount/ProductServiceTests.kt`

- [ ] **Step 1: Run the existing concurrency test to verify the failure**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :app:test --tests 'io.nexure.discount.ProductServiceTests.should handle concurrent discount applications safely - NO DUPLICATES' --rerun-tasks
```

Expected:

```text
Expected <26> but was <2>.
```

- [ ] **Step 2: Update repository imports**

In `discount/app/src/main/kotlin/io/nexure/discount/repository/ProductRepository.kt`, use these Mongo imports:

```kotlin
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.Updates
```

- [ ] **Step 3: Make `save` use a single upsert**

Replace the current `save` method with:

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

- [ ] **Step 4: Replace read-modify-write discount application with an atomic update**

Replace the current `applyDiscount` method with:

```kotlin
suspend fun applyDiscount(productId: String, discount: Discount): Product? {
    val updateResult = collection.updateOne(
        Filters.and(
            Filters.eq("id", productId),
            Filters.ne("discounts.discountId", discount.discountId)
        ),
        Updates.push("discounts", discount)
    )

    return if (updateResult.matchedCount == 0L) {
        findById(productId)
    } else {
        findById(productId)
    }
}
```

Then simplify the return to this equivalent form after verifying it compiles:

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

Expected behavior:

```text
If the product does not exist, findById returns null.
If the same discount already exists, updateOne changes nothing and findById returns the current product.
If concurrent requests apply different discount IDs, each request appends its discount without replacing another request's work.
```

- [ ] **Step 5: Run idempotency and concurrency tests**

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

Expected:

```text
Both selected ProductServiceTests pass.
The concurrency test stores 26 unique discount IDs.
```

- [ ] **Step 6: Commit**

Run:

```bash
git add discount/app/src/main/kotlin/io/nexure/discount/repository/ProductRepository.kt
git commit -m "fix: apply discounts atomically"
```

---

### Task 4: Add Discount Request Validation

**Files:**
- Modify: `discount/app/src/main/kotlin/io/nexure/discount/service/ProductService.kt`
- Modify: `discount/app/src/main/kotlin/io/nexure/discount/Application.kt`
- Modify: `discount/app/src/test/kotlin/io/nexure/discount/ProductServiceTests.kt`
- Modify: `discount/app/src/test/kotlin/io/nexure/discount/HttpEndpointTests.kt`

- [ ] **Step 1: Add service validation tests**

In `discount/app/src/test/kotlin/io/nexure/discount/ProductServiceTests.kt`, add this import:

```kotlin
import kotlin.test.assertFailsWith
```

Add this test method inside `class ProductServiceTests`:

```kotlin
@Test
fun `should reject invalid discounts`() = runBlocking {
    val blankIdError = assertFailsWith<IllegalArgumentException> {
        service.applyDiscount("prod-invalid", Discount("", 10.0))
    }
    assertEquals("discountId must not be blank", blankIdError.message)

    val zeroPercentError = assertFailsWith<IllegalArgumentException> {
        service.applyDiscount("prod-invalid", Discount("ZERO", 0.0))
    }
    assertEquals("percent must be greater than 0 and less than or equal to 100", zeroPercentError.message)

    val highPercentError = assertFailsWith<IllegalArgumentException> {
        service.applyDiscount("prod-invalid", Discount("HIGH", 100.1))
    }
    assertEquals("percent must be greater than 0 and less than or equal to 100", highPercentError.message)
}
```

- [ ] **Step 2: Run the new service validation test and verify it fails**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :app:test --tests 'io.nexure.discount.ProductServiceTests.should reject invalid discounts' --rerun-tasks
```

Expected:

```text
The test fails because ProductService does not validate discountId or percent yet.
```

- [ ] **Step 3: Add validation in ProductService**

In `discount/app/src/main/kotlin/io/nexure/discount/service/ProductService.kt`, replace `applyDiscount` with:

```kotlin
suspend fun applyDiscount(productId: String, discount: Discount): ProductResponse? {
    validateDiscount(discount)
    return repository.applyDiscount(productId, discount)?.toProductResponse()
}
```

Add this private method inside `ProductService`:

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

- [ ] **Step 4: Add an HTTP validation test**

In `discount/app/src/test/kotlin/io/nexure/discount/HttpEndpointTests.kt`, add this test method inside `class HttpEndpointTests`:

```kotlin
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

- [ ] **Step 5: Return HTTP 400 for service validation errors**

In `discount/app/src/main/kotlin/io/nexure/discount/Application.kt`, replace the PUT route body after `val request = call.receive<ApplyDiscountRequest>()` with:

```kotlin
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

- [ ] **Step 6: Run validation tests**

Run:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :app:test \
  --tests 'io.nexure.discount.ProductServiceTests.should reject invalid discounts' \
  --tests 'io.nexure.discount.HttpEndpointTests.PUT discount endpoint should reject invalid discount percent' \
  --rerun-tasks
```

Expected:

```text
Both validation tests pass.
```

- [ ] **Step 7: Commit**

Run:

```bash
git add discount/app/src/main/kotlin/io/nexure/discount/service/ProductService.kt discount/app/src/main/kotlin/io/nexure/discount/Application.kt discount/app/src/test/kotlin/io/nexure/discount/ProductServiceTests.kt discount/app/src/test/kotlin/io/nexure/discount/HttpEndpointTests.kt
git commit -m "test: cover invalid discount requests"
```

---

### Task 5: Document the Fixes

**Files:**
- Create: `FIXES.md`

- [ ] **Step 1: Create FIXES.md**

Create `FIXES.md` at the project root with:

```markdown
# Fixes

## Bugs Found

1. Ktor JSON responses were not configured on the server, causing `GET /products` to return `406 Not Acceptable`.
2. The Kotlin serialization compiler plugin was missing, so `@Serializable` request and response classes had no generated serializers.
3. Multiple discounts were summed before applying them, but the requirement says discounts must be applied multiplicatively.
4. Discount application used a read-modify-write sequence that lost updates under concurrent requests.
5. Discount input validation was missing for blank discount IDs and invalid percentages.

## Fixes Applied

1. Added the Kotlin serialization compiler plugin and installed Ktor `ContentNegotiation` with kotlinx JSON.
2. Changed final price calculation to apply each discount in sequence before VAT.
3. Replaced read-modify-write discount updates with an atomic MongoDB `updateOne` using `discounts.discountId != discountId` and `$push`.
4. Added validation for `discountId` and `percent`, with HTTP 400 responses for invalid discount requests.

## Verification

On this machine, tests require Colima-compatible Testcontainers environment variables:

```bash
cd discount
DOCKER_HOST=unix:///Users/harsh.kumar01/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew test
```
```

- [ ] **Step 2: Commit**

Run:

```bash
git add FIXES.md
git commit -m "docs: document discount api fixes"
```

---

### Task 6: Full Verification

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

- [ ] **Step 2: Review changed files**

Run:

```bash
git status --short
git log --oneline --max-count=6
```

Expected:

```text
Working tree is clean.
Recent commits match the task commits in this plan.
```

- [ ] **Step 3: Prepare submission**

Run:

```bash
git status --short
```

Expected:

```text
No output, meaning there are no uncommitted changes.
```

Submission contents:

```text
Repository link or branch link.
FIXES.md included at project root.
All tests verified with ./gradlew test using the local Docker/Testcontainers environment required by the machine.
```

---

## Self-Review

- Spec coverage: README requirements are covered by JSON endpoint behavior, VAT pricing, multiplicative discounts, idempotent discount application, concurrent discount safety, unknown-country behavior via existing test coverage, validation, full test verification, and `FIXES.md`.
- Placeholder scan: no `TBD`, no open-ended implementation notes, and each code-changing step includes concrete code.
- Type consistency: `Discount`, `ApplyDiscountRequest`, `ProductResponse`, `ProductRepository.applyDiscount`, and `ProductService.applyDiscount` names match the current codebase.
