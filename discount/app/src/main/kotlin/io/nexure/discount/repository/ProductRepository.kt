package io.nexure.discount.repository

import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.nexure.discount.model.Discount
import io.nexure.discount.model.Product
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList

sealed interface ApplyDiscountResult {
    data class Applied(val product: Product) : ApplyDiscountResult
    data class AlreadyPresent(val product: Product, val existing: Discount) : ApplyDiscountResult
    data object NotFound : ApplyDiscountResult
}

class ProductRepository(mongoClient: MongoClient, databaseName: String = "productdb") {
    private val database: MongoDatabase = mongoClient.getDatabase(databaseName)
    private val collection = database.getCollection<Product>("products")
    
    suspend fun init() {
        collection.createIndex(
            Indexes.ascending("id"),
            IndexOptions().unique(true)
        )
    }
    
    suspend fun save(product: Product): Product {
        val filter = Filters.eq("id", product.id)
        val existing = collection.find(filter).firstOrNull()
        
        if (existing != null) {
            // Update existing product
            collection.replaceOne(filter, product)
        } else {
            // Insert new product
            collection.insertOne(product)
        }
        
        return product
    }
    
    suspend fun findById(id: String): Product? {
        return collection.find(Filters.eq("id", id)).firstOrNull()
    }
    
    suspend fun findByCountry(country: String): List<Product> {
        return collection.find(Filters.eq("country", country)).toList()
    }
    
    suspend fun applyDiscount(productId: String, discount: Discount): ApplyDiscountResult {
        val updated = collection.findOneAndUpdate(
            Filters.and(
                Filters.eq("id", productId),
                Filters.not(Filters.elemMatch("discounts", Filters.eq("discountId", discount.discountId))),
            ),
            Updates.push("discounts", discount),
            FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
        )

        if (updated != null) return ApplyDiscountResult.Applied(updated)

        val current = findById(productId) ?: return ApplyDiscountResult.NotFound
        val existing = current.discounts.first { it.discountId == discount.discountId }
        return ApplyDiscountResult.AlreadyPresent(current, existing)
    }
    
    suspend fun deleteAll() {
        collection.drop()
    }
}
