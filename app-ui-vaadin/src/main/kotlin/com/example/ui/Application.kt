package com.example.ui

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.boot.runApplication
import com.example.kernel.GreetingService

@SpringBootApplication(scanBasePackages = ["com.example"])
class Application {
    @Bean
    fun greetingService(): GreetingService = GreetingService()
}

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
