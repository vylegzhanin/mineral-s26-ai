package com.example.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.*
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.splitlayout.SplitLayout
import com.vaadin.flow.component.textfield.NumberField
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.dom.Style
import com.vaadin.flow.router.Route
import java.time.LocalDate

@Route("")
class MainView : VerticalLayout() {

    private val projects = demoProjects()

    private val projectHeader = H4("Проекты")
    private val objectHeader = H4("Объекты")
    private val projectList = com.vaadin.flow.component.html.Div()
    private val objectGallery = com.vaadin.flow.component.html.Div()
    private val selectedObjectTitle = H4("Выберите объект")
    private val propertyEditor = com.vaadin.flow.component.html.Div()

    private var selectedProject: DatasetProject? = null
    private var selectedObject: DatasetObject? = null

    init {
        setSizeFull()
        isPadding = true
        isSpacing = true

        add(H3("Минерал 26 AI"))

        configureProjectList()
        configureObjectGallery()
        configurePropertyEditor()

        val leftPanel = panel(projectHeader, projectList)
        val centerPanel = panel(objectHeader, objectGallery)
        val rightPanel = panel(
            "Свойства объекта",
            VerticalLayout(selectedObjectTitle, propertyEditor).apply {
                setSizeFull()
                isPadding = false
                isSpacing = true
                setFlexGrow(1.0, propertyEditor)
            },
            bodyScrollable = false
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
        projectList.style["align-content"] = "start"
        projectList.setWidthFull()
    }

    private fun configureObjectGallery() {
        objectGallery.style["display"] = "flex"
        objectGallery.style["flex-wrap"] = "wrap"
        objectGallery.style["align-items"] = "flex-start"
        objectGallery.style["gap"] = "12px"
        objectGallery.style["padding"] = "4px"
        objectGallery.style["box-sizing"] = "border-box"
        objectGallery.style["align-content"] = "flex-start"
        objectGallery.setWidthFull()
    }

    private fun configurePropertyEditor() {
        propertyEditor.style["display"] = "flex"
        propertyEditor.style["flex-direction"] = "column"
        propertyEditor.style["gap"] = "12px"
        propertyEditor.style["padding"] = "4px"
        propertyEditor.style["box-sizing"] = "border-box"
        propertyEditor.style["overflow"] = "auto"
        propertyEditor.setSizeFull()
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
            style["line-height"] = "1.25"
        }

        val overlay = com.vaadin.flow.component.html.Div(name).apply {
            style["position"] = "absolute"
            style["left"] = "0"
            style["right"] = "0"
            style["bottom"] = "0"
            style["padding"] = "10px 12px 8px"
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
            styleObjectSelection(selected, style)
            addClickListener { onClick() }
        }
    }

    private fun styleSelection(selected: Boolean, style: Style) {
        if (selected) {
            style["outline"] = "2px solid var(--lumo-primary-color)"
        } else {
            style["outline"] = "2px solid var(--lumo-contrast-20pct)"
        }
        style["outline-offset"] = "0"
    }

    private fun styleObjectSelection(selected: Boolean, style: Style) {
        if (selected) {
            style["outline"] = "2px solid rgba(255,255,255,0.92)"
            style["box-shadow"] = "0 0 0 4px var(--lumo-primary-color)"
        } else {
            style["outline"] = "2px solid var(--lumo-contrast-20pct)"
            style["box-shadow"] = "none"
        }
        style["outline-offset"] = "0"
    }

    private fun updateProperties(obj: DatasetObject?) {
        propertyEditor.removeAll()

        if (obj == null) {
            selectedObjectTitle.text = "Выберите объект"
            propertyEditor.add(Paragraph("Выберите объект, чтобы увидеть и отредактировать его свойства."))
            return
        }

        selectedObjectTitle.text = "Свойства: ${obj.name}"
        propertyEditor.add(
            propertySection(
                "Основные параметры",
                buildPropertyForm(obj)
            ),
            propertySection(
                "Расширенные атрибуты",
                buildAdvancedControls(obj)
            )
        )
    }

    private fun buildPropertyForm(obj: DatasetObject): Component {
        val form = FormLayout().apply {
            setWidthFull()
            responsiveSteps = listOf(
                FormLayout.ResponsiveStep("0", 1),
                FormLayout.ResponsiveStep("480px", 2)
            )
        }

        obj.properties.entries
            .sortedBy { it.key }
            .forEach { (name, value) ->
                form.addFormItem(propertyInput(name, value, obj), prettyLabel(name))
            }

        return form
    }

    private fun propertyInput(name: String, value: String, obj: DatasetObject): Component =
        when (name) {
            "size_fraction" -> comboEditor(name, value, obj, listOf("40.+60", "60.+100", "-100"))
            "grain_class" -> comboEditor(name, value, obj, listOf("светлое зерно", "темное зерно", "сросток", "серое зерно"))
            "shape" -> comboEditor(name, value, obj, listOf("угловатое", "окатанное", "удлиненное"))
            "material" -> comboEditor(name, value, obj, listOf("руда", "концентрат", "шламы"))
            "brightness" -> comboEditor(name, value, obj, listOf("низкая", "средняя", "высокая"))
            "contrast" -> comboEditor(name, value, obj, listOf("низкий", "средний", "высокий"))
            "notes" -> TextArea().apply {
                this.value = value
                isClearButtonVisible = true
                minHeight = "110px"
                setWidthFull()
                addValueChangeListener { obj.properties[name] = it.value }
            }
            else -> TextField().apply {
                this.value = value
                isClearButtonVisible = true
                setWidthFull()
                addValueChangeListener { obj.properties[name] = it.value }
            }
        }

    private fun comboEditor(name: String, value: String, obj: DatasetObject, options: List<String>): ComboBox<String> =
        ComboBox<String>().apply {
            setItems(options)
            isAllowCustomValue = true
            this.value = value
            setWidthFull()
            addValueChangeListener { event -> obj.properties[name] = event.value ?: "" }
            addCustomValueSetListener { event ->
                obj.properties[name] = event.detail
                this.value = event.detail
            }
        }

    private fun buildAdvancedControls(obj: DatasetObject): Component {
        val form = FormLayout().apply {
            setWidthFull()
            responsiveSteps = listOf(
                FormLayout.ResponsiveStep("0", 1),
                FormLayout.ResponsiveStep("640px", 2)
            )
        }

        val status = ComboBox<String>("Статус").apply {
            setItems("Черновик", "На проверке", "Подтвержден", "Отклонен")
            value = obj.properties["meta_status"] ?: "Черновик"
            addValueChangeListener { obj.properties["meta_status"] = it.value ?: "" }
        }

        val confidence = NumberField("Уверенность, %").apply {
            value = obj.properties["meta_confidence"]?.toDoubleOrNull() ?: 85.0
            min = 0.0
            max = 100.0
            step = 1.0
            addValueChangeListener { obj.properties["meta_confidence"] = ((it.value ?: 0.0).toInt()).toString() }
        }

        val analysisDate = DatePicker("Дата анализа").apply {
            value = obj.properties["meta_analysis_date"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: LocalDate.now()
            addValueChangeListener { obj.properties["meta_analysis_date"] = it.value?.toString().orEmpty() }
        }

        val reviewed = Checkbox("Проверено оператором").apply {
            value = obj.properties["meta_reviewed"]?.toBoolean() ?: false
            addValueChangeListener { obj.properties["meta_reviewed"] = it.value.toString() }
        }

        form.add(status, confidence, analysisDate, reviewed)
        return form
    }

    private fun propertySection(title: String, content: Component): Component =
        com.vaadin.flow.component.html.Div(
            H5(title).apply {
                style["margin"] = "0 0 8px 0"
            },
            content
        ).apply {
            style["padding"] = "10px"
            style["border"] = "1px solid var(--lumo-contrast-20pct)"
            style["border-radius"] = "10px"
            style["background"] = "var(--lumo-base-color)"
        }

    private fun prettyLabel(name: String): String = name
        .replace("_", " ")
        .replaceFirstChar { it.uppercase() }

    private fun panel(title: String, content: Component, bodyScrollable: Boolean = true): VerticalLayout =
        panel(H4(title), content, bodyScrollable)

    private fun panel(title: Component, content: Component, bodyScrollable: Boolean = true): VerticalLayout {
        val body = com.vaadin.flow.component.html.Div(content).apply {
            setSizeFull()
            style["min-height"] = "0"
            style["overflow"] = if (bodyScrollable) "auto" else "hidden"
            style["padding-top"] = "8px"
        }

        return VerticalLayout(title, body).apply {
            setSizeFull()
            isPadding = true
            isSpacing = false
            setAlignItems(FlexComponent.Alignment.STRETCH)
            setFlexGrow(1.0, body)
            style["border"] = "1px solid var(--lumo-contrast-20pct)"
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
