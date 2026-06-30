package io.nexure.discount

import kotlinx.serialization.Serializable

class ValidationException(message: String) : IllegalArgumentException(message)
class DiscountConflictException(message: String) : RuntimeException(message)
class UnknownCountryException(message: String) : RuntimeException(message)

@Serializable
data class ErrorResponse(val code: String, val message: String)
