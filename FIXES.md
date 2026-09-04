Summary:

The service had 4 real bugs, surfaced by 4 failing tests out of 12. 
Two were about JSON wiring being incomplete, one was a pricing calculation bug,
and one was a concurrency race condition. I diagnosed each from the actual 
test failure message and stack trace before touching code.

------------------------------------------------------


Bug 1 — GET /products returned 406 instead of 200

Failing test: HttpEndpointTests -> GET products endpoint should return JSON

ContentNegotiation was never called, because of which kotlin objects were not
converted to JSON.
I added install(ContentNegotiation) { json() } in Application.kt for the fix.

------------------------------------------------------


Bug 2 — PUT /products/{id}/discount threw a serialization exception

Failing test: HttpEndpointTests -> PUT discount endpoint should accept and return JSON

We needed to add Serialization plugins to make @serializable work.
I added the plugin and its runtime dependency, which made the 
compiler generate real serializers for every @Serializable class.

------------------------------------------------------


Bug 3 — Multiple discounts calculated the wrong final price

Failing test: ProductServiceTests > should calculate final price correctly with multiple discounts

sumOf summed all discount percentages into a single combined percentage (10% + 20% = 30%) 
and applied that once. The business rule requires each discount to compound on the already-discounted
price.
fold(basePrice) { ... } is a running accumulation: start at basePrice, and for each discount in turn,
multiply the current running price by (1 - percent/100). Each discount is applied on top of the result
of the previous one, then VAT is applied once at the end.

------------------------------------------------------


Bug 4 — Concurrent discount applications lost data

Failing test: ProductServiceTests -> should handle concurrent discount applications safely

Here, the shared resource was read and modified multiple times but was not updated in the 
discounts list, because of which we only saw few discounts as none of them had added themselves
but had overridden on top of each other.
I added mutex per product so that only one product discount can be worked upon at once, so that we dont 
update discounts simultaneously.