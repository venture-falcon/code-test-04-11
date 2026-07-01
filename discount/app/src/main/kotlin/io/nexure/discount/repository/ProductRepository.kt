package io.nexure.discount.repository

import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.nexure.discount.model.Discount
import io.nexure.discount.model.Product
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList

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
        collection.replaceOne(
            Filters.eq("id", product.id),
            product,
            ReplaceOptions().upsert(true)
        )

        return product
    }
    
    suspend fun findById(id: String): Product? {
        return collection.find(Filters.eq("id", id)).firstOrNull()
    }
    
    suspend fun findByCountry(country: String): List<Product> {
        return collection.find(Filters.eq("country", country)).toList()
    }
    
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
    
    suspend fun deleteAll() {
        collection.drop()
    }
}
