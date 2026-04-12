package com.example.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.grid.GridVariant
import com.vaadin.flow.component.html.*
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.splitlayout.SplitLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.dom.Style
import com.vaadin.flow.router.Route

@Route("")
class MainView : VerticalLayout() {

    private val projects = demoProjects()

    private val projectHeader = H4("Проекты")
    private val objectHeader = H4("Объекты")
    private val projectList = com.vaadin.flow.component.html.Div()
    private val objectGallery = com.vaadin.flow.component.html.Div()
    private val selectedObjectTitle = H4("Выберите объект")
    private val propertyGrid = Grid<PropertyItem>()

    private var selectedProject: DatasetProject? = null
    private var selectedObject: DatasetObject? = null

    init {
        setSizeFull()
        isPadding = true
        isSpacing = true

        add(H3("Минерал 26 AI"))

        configureProjectList()
        configureObjectGallery()
        configurePropertyGrid()

        val leftPanel = panel(
            projectHeader,
            VerticalLayout(projectList).apply {
                setSizeFull()
                isPadding = false
                isSpacing = true
                expand(projectList)
            }
        )
        val centerPanel = panel(
            objectHeader,
            VerticalLayout(objectGallery).apply {
                setSizeFull()
                isPadding = false
                isSpacing = true
                expand(objectGallery)
            }
        )
        val rightPanel = panel(
            "Свойства объекта",
            VerticalLayout(selectedObjectTitle, propertyGrid).apply {
                setSizeFull()
                isPadding = false
                isSpacing = true
                expand(propertyGrid)
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

        renderProjects()
        projects.firstOrNull()?.let { selectProject(it) }
    }

    private fun configureProjectList() {
        projectList.style["display"] = "flex"
        projectList.style["flex-direction"] = "column"
        projectList.style["gap"] = "10px"
        projectList.style["padding"] = "4px"
        projectList.style["box-sizing"] = "border-box"
        projectList.style["overflow"] = "auto"
        projectList.setWidthFull()
    }

    private fun configureObjectGallery() {
        objectGallery.style["display"] = "flex"
        objectGallery.style["flex-wrap"] = "wrap"
        objectGallery.style["align-items"] = "flex-start"
        objectGallery.style["gap"] = "12px"
        objectGallery.style["padding"] = "4px"
        objectGallery.style["box-sizing"] = "border-box"
        objectGallery.style["overflow"] = "auto"
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
        renderProjects()
        objectHeader.text = "Объекты (${project.objects.size})"
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

    private fun renderProjects() {
        projectHeader.text = "Проекты (${projects.size})"
        projectList.removeAll()
        projects.forEach { project ->
            projectList.add(projectCard(project, project == selectedProject) { selectProject(project) })
        }
    }

    private fun projectCard(project: DatasetProject, selected: Boolean, onClick: () -> Unit): Component {
        val image = Image(project.previewUrl, project.name).apply {
            width = "96px"
            height = "72px"
            style["object-fit"] = "cover"
            style["border-radius"] = "8px"
            style["flex-shrink"] = "0"
        }

        val title = Span(project.name).apply {
            style["font-weight"] = "600"
            style["display"] = "block"
        }
        val meta = Span("${project.type} • ${project.objects.size} объектов").apply {
            style["color"] = "var(--lumo-secondary-text-color)"
            style["font-size"] = "var(--lumo-font-size-s)"
            style["display"] = "block"
        }

        return com.vaadin.flow.component.html.Div(image, com.vaadin.flow.component.html.Div(title, meta)).apply {
            style["display"] = "flex"
            style["align-items"] = "center"
            style["gap"] = "10px"
            style["padding"] = "8px"
            style["border-radius"] = "10px"
            style["background"] = "var(--lumo-base-color)"
            style["cursor"] = "pointer"
            style["box-shadow"] = "var(--lumo-box-shadow-xs)"
            styleSelection(selected, style)
            addClickListener { onClick() }
        }
    }

    private fun objectCard(obj: DatasetObject, selected: Boolean, onClick: () -> Unit): Component {
        val image = Image(obj.previewUrl, obj.name).apply {
            style["display"] = "block"
            style["width"] = "240px"
            style["height"] = "180px"
            style["object-fit"] = "cover"
            style["border-radius"] = "10px"
        }

        val name = Span(obj.name).apply {
            style["font-weight"] = "600"
            style["display"] = "block"
            style["color"] = "white"
        }

        val overlay = com.vaadin.flow.component.html.Div(name).apply {
            style["position"] = "absolute"
            style["left"] = "0"
            style["right"] = "0"
            style["bottom"] = "0"
            style["padding"] = "8px 10px"
            style["background"] = "linear-gradient(to top, rgba(0,0,0,0.72), rgba(0,0,0,0.08))"
            style["border-radius"] = "0 0 10px 10px"
        }

        return com.vaadin.flow.component.html.Div(image, overlay).apply {
            style["position"] = "relative"
            style["display"] = "inline-block"
            style["line-height"] = "0"
            style["border-radius"] = "10px"
            style["cursor"] = "pointer"
            style["overflow"] = "hidden"
            styleSelection(selected, style)
            addClickListener { onClick() }
        }
    }

    private fun styleSelection(selected: Boolean, style: Style) {
        if (selected) {
            style["border"] = "2px solid var(--lumo-primary-color)"
        } else {
            style["border"] = "2px solid var(--lumo-contrast-20pct)"
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

    private fun panel(title: String, content: Component): VerticalLayout = panel(H4(title), content)

    private fun panel(title: Component, content: Component): VerticalLayout =
        VerticalLayout(title, content).apply {
            setSizeFull()
            isPadding = true
            isSpacing = true
            setAlignItems(FlexComponent.Alignment.STRETCH)
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
        id = "bgok-60-100",
        name = "BGOK 60.+100",
        type = "Шлифы (микроскопия)",
        source = "bgok-lab-60-plus-100",
        previewUrl = "/images/projects/bgok-60-plus-100.svg",
        objects = listOf(
            DatasetObject(
                id = "bgok-grain-001",
                name = "Зерно BGOK #001",
                category = "OreGrain",
                previewUrl = "/images/objects/bgok-grain-001.svg",
                properties = mutableMapOf(
                    "project" to "BGOK 60.+100",
                    "size_fraction" to "60.+100",
                    "grain_class" to "светлое вкрапление",
                    "shape" to "угловатое"
                )
            ),
            DatasetObject(
                id = "bgok-grain-002",
                name = "Зерно BGOK #002",
                category = "OreGrain",
                previewUrl = "/images/objects/bgok-grain-002.svg",
                properties = mutableMapOf(
                    "project" to "BGOK 60.+100",
                    "size_fraction" to "60.+100",
                    "grain_class" to "серое зерно",
                    "texture" to "штриховая"
                )
            ),
            DatasetObject(
                id = "bgok-grain-003",
                name = "Зерно BGOK #003",
                category = "OreGrain",
                previewUrl = "/images/objects/bgok-grain-003.svg",
                properties = mutableMapOf(
                    "project" to "BGOK 60.+100",
                    "size_fraction" to "60.+100",
                    "grain_class" to "сросток",
                    "notes" to "кластер мелких зерен"
                )
            )
        )
    ),
    DatasetProject(
        id = "kgmk-conc",
        name = "KGMK.conc",
        type = "Шлифы (концентрат)",
        source = "kgmk-concentrate-series",
        previewUrl = "/images/projects/kgmk-conc.svg",
        objects = listOf(
            DatasetObject(
                id = "kgmk-grain-001",
                name = "Зерно KGMK #001",
                category = "OreGrain",
                previewUrl = "/images/objects/kgmk-grain-001.svg",
                properties = mutableMapOf(
                    "project" to "KGMK.conc",
                    "material" to "концентрат",
                    "brightness" to "низкая",
                    "fractures" to "выраженные"
                )
            ),
            DatasetObject(
                id = "kgmk-grain-002",
                name = "Зерно KGMK #002",
                category = "OreGrain",
                previewUrl = "/images/objects/kgmk-grain-002.svg",
                properties = mutableMapOf(
                    "project" to "KGMK.conc",
                    "material" to "концентрат",
                    "grain_class" to "светлое зерно",
                    "contrast" to "высокий"
                )
            ),
            DatasetObject(
                id = "kgmk-grain-003",
                name = "Зерно KGMK #003",
                category = "OreGrain",
                previewUrl = "/images/objects/kgmk-grain-003.svg",
                properties = mutableMapOf(
                    "project" to "KGMK.conc",
                    "material" to "концентрат",
                    "grain_class" to "темное зерно",
                    "notes" to "неоднородный край"
                )
            )
        )
    )
)
