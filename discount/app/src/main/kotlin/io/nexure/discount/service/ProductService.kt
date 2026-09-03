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

    suspend fun applyDiscount(productId: String, discount: Discount): ProductResponse? =
        repository.applyDiscount(productId, discount)?.toProductResponse()

    private fun Product.toProductResponse(): ProductResponse = ProductResponse(
        id = id,
        name = name,
        basePrice = basePrice,
        country = country,
        discounts = discounts.map { DiscountResponse(it.discountId, it.percent) },
        finalPrice = calculateFinalPrice()
    )
    //Bug: discounts were added on top of another which was causing test failure, we need to add the discounts separately
    // on the base price so that the final price is obtained instead of just adding all the prices at once
    private fun Product.calculateFinalPrice(): Double {
        val vatRate = VatConfig.getVatRate(country)
        val discountedPrice=discounts.fold(basePrice){
            price, discount ->price * (1-discount.percent/100)
        }
        return discountedPrice * (1+vatRate)
    }
}
