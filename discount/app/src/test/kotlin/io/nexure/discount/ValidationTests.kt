package io.nexure.discount

import com.mongodb.kotlin.client.coroutine.MongoClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import io.nexure.discount.model.ApplyDiscountRequest
import io.nexure.discount.model.Discount
import io.nexure.discount.model.Product
import io.nexure.discount.repository.ProductRepository
import kotlinx.coroutines.runBlocking
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Validation tests added during the bug-fix pass:
 *   - reject same-discount-id-with-different-percent (409 Conflict)
 *   - reject discount apply on unknown-country product (422)
 *   - reject percent outside (0, 100) (400)
 *   - reject blank discountId (400)
 *   - reject NaN/Infinity percent (400)
 */
class ValidationTests {

    companion object {
        private val mongoContainer = MongoDBContainer(DockerImageName.parse("mongo:7.0"))
        private val mongoClient: MongoClient

        init {
            mongoContainer.start()
            mongoClient = MongoClient.create(mongoContainer.connectionString)
        }
    }

    private lateinit var repository: ProductRepository

    @BeforeTest
    fun setup() = runBlocking {
        repository = ProductRepository(mongoClient, "productdb")
        repository.deleteAll()
        repository.init()
    }

    @AfterTest
    fun tearDown() = runBlocking {
        repository.deleteAll()
    }

    private fun client(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        environment {
            config = MapApplicationConfig("mongodb.uri" to mongoContainer.connectionString)
        }
        application { module() }
        val c = createClient {
            install(ContentNegotiation) { json() }
        }
        block(c)
    }

    @Test
    fun `same discountId with different percent returns 409 Conflict`() = runBlocking {
        repository.save(Product("p1", "Item", 100.0, "Sweden", emptyList()))

        client { c ->
            val first = c.put("/products/p1/discount") {
                contentType(ContentType.Application.Json)
                setBody(ApplyDiscountRequest("SUMMER", 10.0))
            }
            assertEquals(HttpStatusCode.OK, first.status)

            val second = c.put("/products/p1/discount") {
                contentType(ContentType.Application.Json)
                setBody(ApplyDiscountRequest("SUMMER", 15.0))
            }
            assertEquals(HttpStatusCode.Conflict, second.status)
            val body: ErrorResponse = second.body()
            assertEquals("DISCOUNT_CONFLICT", body.code)
        }

        val stored = repository.findById("p1")!!
        assertEquals(1, stored.discounts.size)
        assertEquals(10.0, stored.discounts[0].percent)
    }

    @Test
    fun `applying discount on unknown-country product returns 422`() = runBlocking {
        repository.save(Product("uk1", "Item", 100.0, "UnitedKingdom", emptyList()))

        client { c ->
            val resp = c.put("/products/uk1/discount") {
                contentType(ContentType.Application.Json)
                setBody(ApplyDiscountRequest("X", 10.0))
            }
            assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
            val body: ErrorResponse = resp.body()
            assertEquals("UNKNOWN_COUNTRY", body.code)
        }
    }

    @Test
    fun `percent outside 0 to 100 is rejected with 400`() = runBlocking {
        repository.save(Product("p2", "Item", 100.0, "Sweden", emptyList()))

        client { c ->
            for (bad in listOf(-1.0, 0.0, 100.0, 150.0)) {
                val resp = c.put("/products/p2/discount") {
                    contentType(ContentType.Application.Json)
                    setBody(ApplyDiscountRequest("D", bad))
                }
                assertEquals(HttpStatusCode.BadRequest, resp.status, "percent=$bad should be rejected")
            }
        }
    }

    @Test
    fun `blank discountId is rejected with 400`() = runBlocking {
        repository.save(Product("p3", "Item", 100.0, "Sweden", emptyList()))

        client { c ->
            val resp = c.put("/products/p3/discount") {
                contentType(ContentType.Application.Json)
                setBody(ApplyDiscountRequest("", 10.0))
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
    }

    @Test
    fun `non-finite percent is rejected with 400`() = runBlocking {
        repository.save(Product("p4", "Item", 100.0, "Sweden", emptyList()))

        client { c ->
            for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                val resp = c.put("/products/p4/discount") {
                    contentType(ContentType.Application.Json)
                    setBody(ApplyDiscountRequest("D", bad))
                }
                assertEquals(HttpStatusCode.BadRequest, resp.status, "percent=$bad should be rejected")
            }
        }
    }
}
