# Fixes

## 1. Multiple discount calculation bug

### Issue
Discounts were being added together before calculation.

Example:
10% + 20% was treated as 30%.

### Fix
Changed calculation to apply discounts sequentially:

basePrice * (1 - discount1) * (1 - discount2)

This matches the expected business rule.


## 2. Concurrent discount application bug

### Issue
The previous implementation:

- Read product
- Checked existing discount
- Added discount
- Saved product

was not concurrency safe.

Multiple parallel requests could add duplicate discounts.

### Fix
Used MongoDB atomic update:

- `Updates.addToSet`
- `findOneAndUpdate`

This guarantees:
- Same discount is applied only once
- Concurrent requests do not create duplicates


## 3. JSON response configuration

### Issue
HTTP endpoints returned Kotlin objects but Ktor did not have JSON serialization configured.

### Fix
Enabled Ktor ContentNegotiation with kotlinx JSON serialization.