package com.example.kernel

import java.time.Instant

class SystemTimeProvider : TimeProvider {
    override fun currentIsoInstant(): String = Instant.now().toString()
}
