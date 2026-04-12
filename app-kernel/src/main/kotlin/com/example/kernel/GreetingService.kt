package com.example.kernel

class GreetingService(
    private val timeProvider: TimeProvider = SystemTimeProvider()
) {
    fun greet(name: String?): String {
        val normalized = name?.trim().orEmpty()
        val target = if (normalized.isBlank()) "anonymous user" else normalized
        return "Hello, $target @ ${timeProvider.currentIsoInstant()}"
    }
}
