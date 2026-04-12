package com.example.ui

import com.example.kernel.GreetingService
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.component.upload.Upload
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.streams.UploadHandler

@Route("")
class MainView(
    private val greetingService: GreetingService
) : VerticalLayout() {

    init {
        isPadding = true
        isSpacing = true

        val title = H2("Vaadin OSS + Karibu DSL ready")
        val description = Paragraph(
            "Minimal multi-module example with shared kernel, contracts, Ktor edge, and file upload support."
        )

        val nameField = TextField("Name")
        val helloButton = Button("Say hello") {
            Notification.show(greetingService.greet(nameField.value))
        }

        val upload = Upload(
            UploadHandler.inMemory { metadata, data ->
                Notification.show(
                    "Uploaded ${metadata.fileName()} (${data.size} bytes, ${metadata.contentType()})"
                )
            }
        ).apply {
            setAcceptedFileTypes(".txt", ".json", ".csv", ".png", ".jpg")
            maxFiles = 1
            maxFileSize = 10 * 1024 * 1024
        }

        add(title, description, nameField, helloButton, upload)
    }
}
