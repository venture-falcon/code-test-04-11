# 🧩 Country-Based Product API — Technical Assessment

Build a single Kotlin (Ktor) service that manages products and discounts.

## 📦 Problem Description
You’ll build a Product API that:
- Stores a catalog of products.
- Calculates final prices including country VAT and discounts.
- Allows concurrent discount application, but guarantees:
  > “The same discount cannot be applied more than once to the same product — even under heavy concurrent load.”

**Final price formula:**  
`finalPrice = basePrice × (1 - totalDiscount%) × (1 + VAT%)`

---

## 🧱 Data Model

### Product
| Field | Type | Description |
|-------|------|-------------|
| `id` | String | Unique identifier |
| `name` | String | Product name |
| `basePrice` | Double | Price before tax and discount |
| `country` | String | Country name |
| `discounts` | List<Discount> | List of applied discounts |

### Discount
| Field | Type | Description |
|--------|------|-------------|
| `discountId` | String | Unique discount identifier (idempotency key) |
| `percent` | Double | Discount percentage (0–100, exclusive of 0) |

### Country VAT Rules
| Country | VAT |
|----------|-----|
| Sweden | 25% |
| Germany | 19% |
| France | 20% |

---

## 📡 API Endpoints

### `GET /products?country={country}`
Returns all products for the given country, including their **final price**.

### `PUT /products/{id}/discount`
Applies a discount to a product in a manner that is idempotent and not subject to race conditions.

**Expected behavior:**
If multiple clients apply the same discount concurrently:
- Only first successful request will persist changes
- Any identical request that follows should not update state or have any side effects

---

## Requirements
- Use a persistent database for storing products and discounts (e.g. MongoDB or PostgreSQL).
  - In-memory solutions (e.g. ConcurrentHashMap) are not allowed. Concurrency must be enforced at the database level.
- Implement the endpoints described above (GET /products, PUT /products/{id}/discount).
- Store products and applied discounts.
- Calculate finalPrice including VAT and discounts.
- Ensure the same discount cannot be applied more than once per product.
- Demonstrate concurrency safety with a test that simulates multiple simultaneous discount requests via http endpoints.

---

## 🧩 Deliverables

1. **Code** for the service, runnable locally (via Gradle or Docker Compose).
2. **README.md** with:
    - Build and run instructions
    - Example curl commands
3. **ARCHITECTURE.md** with:
    - A short design explanation
    - Description of your concurrency approach
    - At least one **Mermaid sequence diagram** for:
        - `GET /products`
        - `PUT /products/{id}/discount`

---
## 📬 Submission

When you’re done:
1. Push your solution to a **public GitHub repository**.
2. Ensure we can build and run it locally.
3. Include:
    - Code
    - README.md
    - ARCHITECTURE.md (with diagrams)

Then share the repository link.
---
✨ **Good luck, and have fun!**
---
