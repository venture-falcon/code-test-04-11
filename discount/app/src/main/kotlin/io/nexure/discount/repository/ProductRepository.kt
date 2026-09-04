package io.nexure.discount.repository

import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.nexure.discount.model.Discount
import io.nexure.discount.model.Product
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class ProductRepository(mongoClient: MongoClient, databaseName: String = "productdb") {
    private val database: MongoDatabase = mongoClient.getDatabase(databaseName)
    private val collection = database.getCollection<Product>("products")
    private val productLocks= ConcurrentHashMap<String,Mutex>()
    
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

    // we're locking every transaction as per product so that we are locked for that transaction
    // the product and discount part is locked and it used by one at a time
    suspend fun applyDiscount(productId: String, discount: Discount): Product? {
        val mutex= productLocks.computeIfAbsent(productId){Mutex()}
        return mutex.withLock {
            val product=findById(productId)?: return@withLock null
            val hasDiscount=product.discounts.any{it.discountId==discount.discountId}

            if(hasDiscount){
                return@withLock product
            }

            val updatedDiscounts=product.discounts+discount
            val updatedProduct=product.copy(discounts=updatedDiscounts)

            save(updatedProduct)
        }
    }
    
    suspend fun deleteAll() {
        collection.drop()
    }
}
