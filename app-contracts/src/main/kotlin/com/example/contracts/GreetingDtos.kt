package com.example.contracts

import kotlinx.serialization.Serializable

@Serializable
data class GreetingRequest(
    val name: String? = null
)

@Serializable
data class GreetingResponse(
    val message: String
)
