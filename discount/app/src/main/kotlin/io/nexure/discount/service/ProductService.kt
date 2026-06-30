package io.nexure.discount.service

import io.nexure.discount.DiscountConflictException
import io.nexure.discount.UnknownCountryException
import io.nexure.discount.ValidationException
import io.nexure.discount.config.VatConfig
import io.nexure.discount.model.Discount
import io.nexure.discount.model.DiscountResponse
import io.nexure.discount.model.Product
import io.nexure.discount.model.ProductResponse
import io.nexure.discount.repository.ApplyDiscountResult
import io.nexure.discount.repository.ProductRepository

class ProductService(private val repository: ProductRepository) {

    suspend fun getProductsByCountry(country: String): List<ProductResponse> =
        repository.findByCountry(country).map { it.toProductResponse() }

    suspend fun applyDiscount(productId: String, discount: Discount): ProductResponse? {
        validate(discount)

        val product = repository.findById(productId) ?: return null
        if (!VatConfig.isKnownCountry(product.country)) {
            throw UnknownCountryException(
                "Product '$productId' belongs to country '${product.country}' which has no configured VAT rate; refusing to apply discount"
            )
        }

        return when (val result = repository.applyDiscount(productId, discount)) {
            is ApplyDiscountResult.Applied -> result.product.toProductResponse()
            is ApplyDiscountResult.AlreadyPresent -> {
                if (result.existing.percent != discount.percent) {
                    throw DiscountConflictException(
                        "Discount '${discount.discountId}' already exists with percent=${result.existing.percent}; " +
                            "refused to overwrite with percent=${discount.percent}"
                    )
                }
                result.product.toProductResponse()
            }
            ApplyDiscountResult.NotFound -> null
        }
    }

    private fun validate(discount: Discount) {
        if (discount.discountId.isBlank()) {
            throw ValidationException("discountId must not be blank")
        }
        if (!discount.percent.isFinite()) {
            throw ValidationException("percent must be a finite number")
        }
        if (discount.percent <= 0.0 || discount.percent >= 100.0) {
            throw ValidationException("percent must be in the open range (0, 100), got ${discount.percent}")
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
        val discountMultiplier = discounts.fold(1.0) { acc, d -> acc * (1 - d.percent / 100.0) }
        return basePrice * discountMultiplier * (1 + vatRate)
    }
}
