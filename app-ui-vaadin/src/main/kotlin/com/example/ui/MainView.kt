package com.example.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.grid.GridVariant
import com.vaadin.flow.component.html.*
import com.vaadin.flow.component.orderedlayout.Scroller
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.splitlayout.SplitLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.dom.Style
import com.vaadin.flow.router.Route

@Route("")
class MainView : VerticalLayout() {

    private val projects = demoProjects()

    private val projectGrid = Grid<DatasetProject>()
    private val objectGallery = com.vaadin.flow.component.html.Div()
    private val objectCounter = Span("0 объектов")
    private val selectedObjectTitle = H4("Выберите объект")
    private val propertyGrid = Grid<PropertyItem>()

    private var selectedProject: DatasetProject? = null
    private var selectedObject: DatasetObject? = null

    init {
        setSizeFull()
        isPadding = true
        isSpacing = true

        add(
            H3("Прототип просмотра и анализа датасета"),
            Paragraph("Выбор проекта, просмотр объектов и редактирование свойств в одном экране.")
        )

        configureProjectGrid()
        configureObjectGallery()
        configurePropertyGrid()

        val leftPanel = panel("1) Проекты (как список)", projectGrid)
        val centerPanel = panel(
            "2) Объекты (в стиле sample gallery)",
            VerticalLayout(objectCounter, Scroller(objectGallery).apply { setSizeFull() }).apply {
                setSizeFull()
                isPadding = false
                isSpacing = true
            }
        )
        val rightPanel = panel(
            "3) Свойства выбранного объекта",
            VerticalLayout(selectedObjectTitle, propertyGrid).apply {
                setSizeFull()
                isPadding = false
                isSpacing = true
            }
        )

        // 20% | 60% | 20%: настраивается пользователем через drag splitters
        val centerRightSplit = SplitLayout(centerPanel, rightPanel).apply {
            setSizeFull()
            setSplitterPosition(75.0) // 75% of remaining 80% = 60% of full width
        }
        val rootSplit = SplitLayout(leftPanel, centerRightSplit).apply {
            setSizeFull()
            setSplitterPosition(20.0)
        }

        add(rootSplit)
        expand(rootSplit)

        projectGrid.setItems(projects)
        projects.firstOrNull()?.let { projectGrid.select(it) }
    }

    private fun configureProjectGrid() {
        projectGrid.setSizeFull()
        projectGrid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES)
        projectGrid.addColumn(DatasetProject::name).setHeader("Проект").setAutoWidth(true).setFlexGrow(1)
        projectGrid.addColumn(DatasetProject::type).setHeader("Тип").setAutoWidth(true).setFlexGrow(1)
        projectGrid.addColumn { it.objects.size }.setHeader("Объектов").setAutoWidth(true).setFlexGrow(0)

        projectGrid.asSingleSelect().addValueChangeListener { event ->
            event.value?.let { selectProject(it) }
        }
    }

    private fun configureObjectGallery() {
        objectGallery.style["display"] = "grid"
        objectGallery.style["grid-template-columns"] = "repeat(auto-fill, minmax(180px, 1fr))"
        objectGallery.style["gap"] = "12px"
        objectGallery.style["padding"] = "4px"
        objectGallery.style["box-sizing"] = "border-box"
        objectGallery.setSizeFull()
    }

    private fun configurePropertyGrid() {
        propertyGrid.setSizeFull()
        propertyGrid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES)
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
    }

    private fun selectProject(project: DatasetProject) {
        selectedProject = project
        selectedObject = null
        objectCounter.text = "${project.objects.size} объектов"
        renderObjects(project.objects)
        updateProperties(null)
    }

    private fun selectObject(obj: DatasetObject) {
        selectedObject = obj
        renderObjects(selectedProject?.objects.orEmpty())
        updateProperties(obj)
    }

    private fun renderObjects(objects: List<DatasetObject>) {
        objectGallery.removeAll()

        if (objects.isEmpty()) {
            objectGallery.add(Paragraph("Нет объектов в выбранном проекте."))
            return
        }

        objects.forEach { obj ->
            objectGallery.add(objectCard(obj, obj == selectedObject) { selectObject(obj) })
        }
    }

    private fun objectCard(obj: DatasetObject, selected: Boolean, onClick: () -> Unit): Component {
        val image = Image(obj.previewUrl, obj.name).apply {
            width = "100%"
            height = "140px"
            style["object-fit"] = "cover"
            style["border-radius"] = "8px"
        }

        val name = Span(obj.name).apply {
            style["font-weight"] = "600"
            style["display"] = "block"
        }
        val meta = Span("${obj.category} • ${obj.properties.size} props").apply {
            style["color"] = "var(--lumo-secondary-text-color)"
            style["font-size"] = "var(--lumo-font-size-s)"
            style["display"] = "block"
        }

        return com.vaadin.flow.component.html.Div(image, name, meta).apply {
            style["padding"] = "8px"
            style["border-radius"] = "10px"
            style["background"] = "var(--lumo-base-color)"
            style["cursor"] = "pointer"
            style["box-shadow"] = "var(--lumo-box-shadow-xs)"
            styleSelection(selected, style)
            addClickListener { onClick() }
        }
    }

    private fun styleSelection(selected: Boolean, style: Style) {
        if (selected) {
            style["border"] = "2px solid var(--lumo-primary-color)"
        } else {
            style["border"] = "1px solid var(--lumo-contrast-20pct)"
        }
    }

    private fun updateProperties(obj: DatasetObject?) {
        if (obj == null) {
            selectedObjectTitle.text = "Выберите объект"
            propertyGrid.setItems(emptyList())
            return
        }

        selectedObjectTitle.text = "Свойства: ${obj.name}"
        propertyGrid.setItems(
            obj.properties.entries
                .map { PropertyItem(it.key, it.value) }
                .sortedBy { it.name }
        )
    }

    private fun panel(title: String, content: Component): VerticalLayout =
        VerticalLayout(H4(title), content).apply {
            setSizeFull()
            isPadding = true
            isSpacing = true
            style["border"] = "1px solid var(--lumo-contrast-20pct)"
            style["border-radius"] = "10px"
            style["background"] = "var(--lumo-contrast-5pct)"
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
