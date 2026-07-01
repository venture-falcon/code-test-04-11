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
