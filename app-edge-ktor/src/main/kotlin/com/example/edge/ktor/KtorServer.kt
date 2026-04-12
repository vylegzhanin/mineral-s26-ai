package com.example.edge.ktor

import com.example.contracts.GreetingRequest
import com.example.contracts.GreetingResponse
import com.example.kernel.GreetingService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8081
    embeddedServer(Netty, port = port, module = Application::module).start(wait = true)
}

fun Application.module() {
    val greetingService = GreetingService()
    val environmentName = System.getenv("KTOR_ENV") ?: "dev"

    install(CallLogging)
    install(ContentNegotiation) {
        json()
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                GreetingResponse("Error: ${cause.message ?: "unknown"}")
            )
        }
    }

    routing {
        get("/health") {
            call.respondText("OK ($environmentName)", ContentType.Text.Plain)
        }
        post("/api/greet") {
            val request = call.receive<GreetingRequest>()
            call.respond(GreetingResponse(greetingService.greet(request.name)))
        }
    }
}
