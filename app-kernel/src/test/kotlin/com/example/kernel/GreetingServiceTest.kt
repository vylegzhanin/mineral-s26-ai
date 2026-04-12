package com.example.kernel

import kotlin.test.Test
import kotlin.test.assertTrue

class GreetingServiceTest {
    @Test
    fun `greet returns message`() {
        val service = GreetingService { "2026-04-09T00:00:00Z" }
        assertTrue(service.greet("Alex").contains("Hello, Alex"))
    }
}
