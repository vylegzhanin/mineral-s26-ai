package com.example.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class GreetingDtosTest {
    @Test
    fun `serializes response`() {
        val json = Json.encodeToString(GreetingResponse("ok"))
        assertEquals("{\"message\":\"ok\"}", json)
    }
}
