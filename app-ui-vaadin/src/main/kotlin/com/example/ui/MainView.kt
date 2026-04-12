package com.example.ui

import com.vaadin.flow.component.Text
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.grid.GridVariant
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.H4
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.Scroller
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.Route

@Route("")
class MainView : VerticalLayout() {

    private val projects = demoProjects()
    private val objectColumn = VerticalLayout()
    private val selectedObjectTitle = H4("Выберите объект")
    private val propertyGrid = Grid<PropertyItem>()

    private var selectedProject: DatasetProject? = null
    private var selectedObject: DatasetObject? = null

    init {
        isPadding = true
        isSpacing = true
        setSizeFull()

        add(
            H3("Прототип просмотра и анализа датасета"),
            Paragraph("Выберите проект, затем объект, и отредактируйте свойства выбранного объекта.")
        )

        val content = HorizontalLayout().apply {
            setSizeFull()
            setPadding(false)
            setSpacing(true)

            add(
                buildProjectsPanel(),
                buildObjectsPanel(),
                buildPropertiesPanel()
            )
            setFlexGrow(1.0, getComponentAt(0), getComponentAt(1), getComponentAt(2))
        }

        add(content)
        expand(content)

        if (projects.isNotEmpty()) {
            selectProject(projects.first())
        }
    }

    private fun buildProjectsPanel(): VerticalLayout {
        val projectList = VerticalLayout().apply {
            isPadding = false
            isSpacing = true
        }

        projects.forEach { project ->
            projectList.add(
                card(
                    title = project.name,
                    imageUrl = project.previewUrl,
                    summaryLines = listOf(
                        "Тип: ${project.type}",
                        "Объектов: ${project.objects.size}",
                        "Источник: ${project.source}"
                    ),
                    selectCaption = "Открыть проект"
                ) { selectProject(project) }
            )
        }

        return panel(
            "1) Проекты (датасеты)",
            Scroller(projectList).apply { setSizeFull() }
        )
    }

    private fun buildObjectsPanel(): VerticalLayout {
        objectColumn.isPadding = false
        objectColumn.isSpacing = true

        return panel(
            "2) Объекты выбранного проекта",
            Scroller(objectColumn).apply { setSizeFull() }
        )
    }

    private fun buildPropertiesPanel(): VerticalLayout {
        propertyGrid.addColumn(PropertyItem::name).setHeader("Свойство").setAutoWidth(true).setFlexGrow(0)
        propertyGrid.addColumn(
            ComponentRenderer { item ->
                TextField().apply {
                    value = item.value
                    isClearButtonVisible = true
                    setWidthFull()
                    addValueChangeListener {
                        item.value = it.value
                        selectedObject?.properties?.put(item.name, it.value)
                    }
                }
            }
        ).setHeader("Значение").setFlexGrow(1)

        propertyGrid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES)
        propertyGrid.setSizeFull()

        val wrapper = VerticalLayout(selectedObjectTitle, propertyGrid).apply {
            setSizeFull()
            isPadding = false
            isSpacing = true
        }

        return panel("3) Свойства выбранного объекта", wrapper)
    }

    private fun selectProject(project: DatasetProject) {
        selectedProject = project
        selectedObject = null
        renderObjects(project.objects)
        updateProperties(null)
    }

    private fun selectObject(obj: DatasetObject) {
        selectedObject = obj
        updateProperties(obj)
    }

    private fun renderObjects(objects: List<DatasetObject>) {
        objectColumn.removeAll()

        if (objects.isEmpty()) {
            objectColumn.add(Paragraph("У проекта нет объектов."))
            return
        }

        objects.forEach { obj ->
            objectColumn.add(
                card(
                    title = obj.name,
                    imageUrl = obj.previewUrl,
                    summaryLines = listOf(
                        "ID: ${obj.id}",
                        "Класс: ${obj.category}",
                        "Параметров: ${obj.properties.size}"
                    ),
                    selectCaption = "Выбрать объект"
                ) { selectObject(obj) }
            )
        }
    }

    private fun updateProperties(obj: DatasetObject?) {
        if (obj == null) {
            selectedObjectTitle.text = "Выберите объект"
            propertyGrid.setItems(emptyList())
            return
        }

        selectedObjectTitle.text = "Свойства: ${obj.name}"
        val rows = obj.properties.entries
            .map { PropertyItem(it.key, it.value) }
            .sortedBy { it.name }
        propertyGrid.setItems(rows)
    }

    private fun panel(title: String, content: com.vaadin.flow.component.Component): VerticalLayout =
        VerticalLayout(H4(title), content).apply {
            setSizeFull()
            setPadding(true)
            isSpacing = true
            style["border"] = "1px solid var(--lumo-contrast-20pct)"
            style["border-radius"] = "10px"
            style["background"] = "var(--lumo-base-color)"
        }

    private fun card(
        title: String,
        imageUrl: String,
        summaryLines: List<String>,
        selectCaption: String,
        onSelect: () -> Unit
    ): VerticalLayout {
        val image = Image(imageUrl, title).apply {
            width = "100%"
            height = "120px"
            style["object-fit"] = "cover"
            style["border-radius"] = "8px"
        }

        val details = VerticalLayout().apply {
            isPadding = false
            isSpacing = false
            add(H4(title))
            summaryLines.forEach { add(Text(it), com.vaadin.flow.component.html.Br()) }
        }

        return VerticalLayout(image, details, Button(selectCaption) { onSelect() }).apply {
            width = "100%"
            setPadding(true)
            isSpacing = true
            style["border"] = "1px solid var(--lumo-contrast-10pct)"
            style["border-radius"] = "10px"
            style["background"] = "var(--lumo-contrast-5pct)"
        }
    }
}

private data class DatasetProject(
    val id: String,
    val name: String,
    val type: String,
    val source: String,
    val previewUrl: String,
    val objects: List<DatasetObject>
)

private data class DatasetObject(
    val id: String,
    val name: String,
    val category: String,
    val previewUrl: String,
    val properties: MutableMap<String, String>
)

private data class PropertyItem(
    val name: String,
    var value: String
)

private fun demoProjects(): List<DatasetProject> = listOf(
    DatasetProject(
        id = "city-traffic",
        name = "Городской трафик",
        type = "Изображения + метаданные",
        source = "camera-network-01",
        previewUrl = "https://images.unsplash.com/photo-1465447142348-e9952c393450?auto=format&fit=crop&w=900&q=80",
        objects = listOf(
            DatasetObject(
                id = "car-001",
                name = "Автомобиль #001",
                category = "Car",
                previewUrl = "https://images.unsplash.com/photo-1503376780353-7e6692767b70?auto=format&fit=crop&w=900&q=80",
                properties = mutableMapOf(
                    "speed_kmh" to "53",
                    "color" to "red",
                    "lane" to "2",
                    "detected_at" to "2026-04-12T10:12:00Z"
                )
            ),
            DatasetObject(
                id = "ped-074",
                name = "Пешеход #074",
                category = "Person",
                previewUrl = "https://images.unsplash.com/photo-1529429611278-5db8d3d889e9?auto=format&fit=crop&w=900&q=80",
                properties = mutableMapOf(
                    "direction" to "north",
                    "confidence" to "0.96",
                    "occluded" to "false"
                )
            )
        )
    ),
    DatasetProject(
        id = "warehouse-v2",
        name = "Склад инвентаризации",
        type = "Фото + табличные атрибуты",
        source = "warehouse-scan-b",
        previewUrl = "https://images.unsplash.com/photo-1553413077-190dd305871c?auto=format&fit=crop&w=900&q=80",
        objects = listOf(
            DatasetObject(
                id = "box-330",
                name = "Коробка #330",
                category = "Package",
                previewUrl = "https://images.unsplash.com/photo-1586528116311-ad8dd3c8310d?auto=format&fit=crop&w=900&q=80",
                properties = mutableMapOf(
                    "weight_kg" to "4.2",
                    "fragile" to "true",
                    "zone" to "A-17",
                    "status" to "stored"
                )
            ),
            DatasetObject(
                id = "pallet-012",
                name = "Паллета #012",
                category = "Pallet",
                previewUrl = "https://images.unsplash.com/photo-1581092160607-ee22621dd758?auto=format&fit=crop&w=900&q=80",
                properties = mutableMapOf(
                    "height_cm" to "155",
                    "temperature_c" to "5",
                    "last_check" to "2026-04-11"
                )
            )
        )
    )
)
