# FIXES

## Bug 1 — Missing JSON content negotiation in Ktor module

**Symptom:** `GET /products?country=...` returned `406 Not Acceptable`; `PUT /products/{id}/discount` threw `SerializationException` when reading the request body.

**Root cause:** `Application.module()` never installed the `ContentNegotiation` plugin, so Ktor had no way to (de)serialize JSON request/response bodies.

**Fix:** Installed `ContentNegotiation { json() }` in `Application.kt`.

---

## Bug 2 — kotlinx.serialization compiler plugin not applied

**Symptom:** After Bug 1, tests still failed with `SerializationException: Serializer for class 'List' is not found` / `Serializer for class 'ApplyDiscountRequest' is not found`. Classes were annotated `@Serializable` but the compiler never generated serializers for them.

**Root cause:** The `org.jetbrains.kotlin.plugin.serialization` Gradle plugin was not applied to the module.

**Fix:** Added the plugin to `gradle/libs.versions.toml` and applied it in `app/build.gradle.kts`.

---

## Bug 3 — Discounts applied additively instead of multiplicatively

**Symptom:** `should calculate final price correctly with multiple discounts` failed: expected `428.40`, got `416.50`. For `basePrice=500, discounts=10%+20%, VAT=19%`:
- Additive (wrong): `500 × (1 - 0.30) × 1.19 = 416.50`
- Multiplicative (correct): `500 × 0.90 × 0.80 × 1.19 = 428.40`

**Root cause:** `ProductService.calculateFinalPrice` summed discount percentages with `discounts.sumOf { it.percent / 100.0 }`, then applied them as a single combined factor. Two stacked 50% discounts would have wiped the price entirely.

**Fix:** Use a multiplicative fold:
```kotlin
val discountMultiplier = discounts.fold(1.0) { acc, d -> acc * (1 - d.percent / 100.0) }
return basePrice * discountMultiplier * (1 + vatRate)
```

---

## Bug 4 — Discount application not concurrency-safe (race condition)

**Symptom:** `should handle concurrent discount applications safely - NO DUPLICATES` failed: after firing 25 concurrent unique-discount applies, only 2 survived in the document (expected 26 = 1 idempotent + 25 unique).

**Root cause:** `ProductRepository.applyDiscount` was a classic read-modify-write:
```kotlin
val product = findById(productId)      // read
val updated = product.copy(discounts = product.discounts + discount)
save(updated)                          // write (full replace)
```
Concurrent callers each read the same baseline, each computed `discounts + theirOwn`, and each clobbered the others with `replaceOne`. The `kotlinx.coroutines.delay(5)` comment was misleadingly labelled "throttle to prevent MongoDB write overload" — it actively widened the race window.

**Fix:** Replaced the read-modify-write with an **atomic conditional `$push`** on the server:
```kotlin
collection.updateOne(
    Filters.and(
        Filters.eq("id", productId),
        Filters.not(Filters.elemMatch("discounts", Filters.eq("discountId", discount.discountId))),
    ),
    Updates.push("discounts", discount),
)
```
- If the discount ID is not already present → MongoDB pushes it atomically.
- If it is already present → the filter doesn't match → the update is a no-op (idempotent).
- Concurrent unique discount IDs all match the filter and each `$push` runs as an atomic document-level operation, so none are lost.

The `delay(5)` was removed.

---

## Bug 5 — Same `discountId` with different `percent` was silently swallowed

**Symptom:** `PUT /products/{id}/discount` with `{discountId:"SUMMER", percent:10}` succeeded; a follow-up `{discountId:"SUMMER", percent:15}` returned `200 OK` but the stored discount remained `10`. The client got a success response for a request that had no effect — the worst kind of silent failure.

**Root cause:** The conditional `$push` filter from Bug 4's fix only checks `discountId`, so any later application of the same `discountId` (regardless of `percent`) was a no-op masquerading as success.

**Fix:** After the atomic `$push`, re-read the document and compare the stored `percent` for that `discountId` against the requested `percent`. If they differ, throw `DiscountConflictException`, mapped by `StatusPages` to **409 Conflict** with body `{"code":"DISCOUNT_CONFLICT", "message":"..."}`.

```kotlin
val existing = product.discounts.firstOrNull { it.discountId == discount.discountId }
if (existing != null && existing.percent != discount.percent) {
    throw DiscountConflictException(
        "Discount '${discount.discountId}' already exists with percent=${existing.percent}; " +
        "refused to overwrite with percent=${discount.percent}"
    )
}
```

Same `discountId` + same `percent` is still idempotent (200 OK, no change). Same `discountId` + different `percent` is now a 409.

---

## Bug 6 — Unknown country silently fell back to 0% VAT on discount apply

**Symptom:** A product whose `country` wasn't in `VatConfig` (e.g. `"UnitedKingdom"`) could still have discounts applied, and the returned `finalPrice` was computed with VAT silently treated as 0. The README explicitly says: *"the system should properly validate and handle requests for countries not in this table."*

**Root cause:** `VatConfig.getVatRate` returned `0.0` for any unknown country (the `?: 0.0` fallback). Nothing distinguished "VAT exempt" from "VAT not configured here."

**Fix:** Added `VatConfig.isKnownCountry`. `ProductService.applyDiscount` now checks the product's country against the known set and throws `UnknownCountryException` if it's not configured, mapped by `StatusPages` to **422 Unprocessable Entity** with code `UNKNOWN_COUNTRY`.

The read path (`GET /products?country=...`) intentionally keeps the existing behaviour — there's a passing test (`should handle products from unknown countries`) that depends on the listing returning `finalPrice == basePrice` for those rows — but write operations now refuse to mutate them.

---

## Hardening — Input validation on `PUT /products/{id}/discount`

The route now rejects malformed discount requests at the boundary, returning **400 Bad Request** with `code: "VALIDATION_ERROR"`:

- `discountId` must not be blank
- `percent` must be finite (not `NaN` / `±Infinity`)
- `percent` must be in the open range `(0, 100)` — `0` and `100` are also rejected as meaningless / dangerous

Also installed a `StatusPages` handler that converts `kotlinx.serialization.SerializationException` (malformed bodies) into **400** with `code: "MALFORMED_REQUEST"` instead of leaking 500s.

---

