package io.nexure.discount.service

import io.nexure.discount.config.VatConfig
import io.nexure.discount.model.Discount
import io.nexure.discount.model.DiscountResponse
import io.nexure.discount.model.Product
import io.nexure.discount.model.ProductResponse
import io.nexure.discount.repository.ProductRepository

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
        id = id,
        name = name,
        basePrice = basePrice,
        country = country,
        discounts = discounts.map { DiscountResponse(it.discountId, it.percent) },
        finalPrice = calculateFinalPrice()
    )

    private fun Product.calculateFinalPrice(): Double {
        val vatRate = VatConfig.getVatRate(country)
        val discountedPrice = discounts.fold(basePrice) { price, discount ->
            price * (1 - discount.percent / 100.0)
        }

        return discountedPrice * (1 + vatRate)
    }
}
