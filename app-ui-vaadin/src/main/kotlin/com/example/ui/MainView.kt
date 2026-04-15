package com.example.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.contextmenu.MenuItem
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.*
import com.vaadin.flow.component.menubar.MenuBar
import com.vaadin.flow.component.progressbar.ProgressBar
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.splitlayout.SplitLayout
import com.vaadin.flow.component.textfield.NumberField
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.dom.Style
import com.vaadin.flow.server.StreamResource
import com.vaadin.flow.router.Route
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.ArrayDeque
import java.util.Locale
import java.security.MessageDigest
import java.util.stream.Collectors
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import javax.imageio.ImageIO

@Route("")
class MainView : VerticalLayout() {
    companion object {
        private const val OBJECT_PAGE_SIZE = 200
        private const val MASK_PROCESS_TIMEOUT_MINUTES = 3L
        private const val MULTIPHASE_CLASS_NAME = "Многофазный"
        private val log = LoggerFactory.getLogger(MainView::class.java)
    }

    private val datasetsRoot: Path = Path.of("/siams/images")
    private val cacheRoot: Path = Path.of("/siams/chache")
    private val projects = mutableListOf<DatasetProject>()

    private val projectHeader = H4("Проекты")
    private val objectHeader = H4("Объекты")
    private val projectList = com.vaadin.flow.component.html.Div()
    private val objectGallery = com.vaadin.flow.component.html.Div()
    private val activeFilters = linkedSetOf<ObjectFilter>()
    private val filterMenuItems = mutableMapOf<ObjectFilter, MenuItem>()
    private val filterControls = HorizontalLayout().apply {
        isPadding = false
        isSpacing = true
        style["gap"] = "6px"
        style["flex-wrap"] = "wrap"
        style["justify-content"] = "flex-end"
    }
    private val grainClassFilter = ComboBox<String>().apply {
        placeholder = "Grain"
        isClearButtonVisible = true
        setWidth("240px")
        style["min-width"] = "180px"
        style["max-width"] = "320px"
        setItemLabelGenerator { it }
    }
    private val statusFilter = ComboBox<String>().apply {
        placeholder = "Статус"
        isClearButtonVisible = true
        setWidth("120px")
    }
    private val confidenceFromFilter = NumberField().apply {
        placeholder = "Ув. от"
        min = 0.0
        max = 100.0
        step = 1.0
        setWidth("94px")
    }
    private val confidenceToFilter = NumberField().apply {
        placeholder = "до"
        min = 0.0
        max = 100.0
        step = 1.0
        setWidth("72px")
    }
    private val analysisDateFromFilter = DatePicker().apply {
        placeholder = "Дата с"
        setWidth("122px")
    }
    private val analysisDateToFilter = DatePicker().apply {
        placeholder = "по"
        setWidth("108px")
    }
    private val reviewedFilter = ComboBox<String>().apply {
        placeholder = "Проверено"
        setItems("Да", "Нет")
        isClearButtonVisible = true
        setWidth("110px")
    }
    private val showMasksCheckbox = Checkbox("Маски").apply {
        value = false
        addValueChangeListener {
            refreshObjectGallery(resetPaging = false)
        }
    }
    private val selectedObjectTitle = H4("Выберите объект")
    private val propertyEditor = com.vaadin.flow.component.html.Div()
    private val objectCardsById = mutableMapOf<String, com.vaadin.flow.component.html.Div>()
    private var selectedObjectCard: com.vaadin.flow.component.html.Div? = null
    private var visibleObjectLimit: Int = OBJECT_PAGE_SIZE

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
        initFilterListeners()

        val leftPanel = panel(projectHeaderWithActions(), projectList)
        val centerPanel = panel(objectHeaderWithFilters(), objectGallery).apply {
            style["padding-left"] = "0"
        }
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
        objectGallery.style["justify-content"] = "flex-start"
        objectGallery.style["align-items"] = "flex-start"
        objectGallery.style["gap"] = "12px"
        objectGallery.style["padding"] = "12px"
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
        selectedObjectCard = null
        renderProjects()
        refreshFilterOptions(project.objects)
        refreshObjectGallery(resetPaging = true)
        updateProperties(null)
    }

    private fun selectObject(obj: DatasetObject) {
        selectedObject = obj
        updateObjectSelection(obj)
        updateProperties(obj)
    }

    private fun renderObjects(objects: List<DatasetObject>) {
        objectGallery.removeAll()
        objectCardsById.clear()

        if (objects.isEmpty()) {
            objectGallery.add(Paragraph("Нет объектов в выбранном проекте."))
            return
        }

        val visibleObjects = objects.take(visibleObjectLimit)
        visibleObjects.forEach { obj ->
            val card = objectCard(obj, obj == selectedObject) { selectObject(obj) }
            objectCardsById[obj.id] = card
            if (obj == selectedObject) {
                selectedObjectCard = card
            }
            objectGallery.add(card)
        }

        if (objects.size > visibleObjects.size) {
            val showMoreButton = Button("Показать ещё (${objects.size - visibleObjects.size})") {
                visibleObjectLimit = minOf(visibleObjectLimit + OBJECT_PAGE_SIZE, objects.size)
                renderObjects(objects)
            }
            objectGallery.add(
                com.vaadin.flow.component.html.Div(showMoreButton).apply {
                    style["width"] = "100%"
                    style["padding"] = "8px 4px"
                }
            )
        }
    }

    private fun updateObjectSelection(obj: DatasetObject) {
        selectedObjectCard?.let { styleObjectSelection(false, it.style) }
        val newCard = objectCardsById[obj.id]
        if (newCard != null) {
            styleObjectSelection(true, newCard.style)
            selectedObjectCard = newCard
        }
    }

    private fun renderProjects() {
        projectHeader.text = "Проекты (${projects.size})"
        projectList.removeAll()
        val projectsByBatch = linkedMapOf<String, MutableList<DatasetProject>>()
        projects.forEach { project ->
            projectsByBatch.getOrPut(project.batch.ifBlank { "Без партии" }) { mutableListOf() }.add(project)
        }
        projectsByBatch.forEach { (batchName, groupedProjects) ->
            projectList.add(
                H5("Партия: $batchName").apply {
                    style["margin"] = "4px 4px 0"
                    style["color"] = "var(--lumo-secondary-text-color)"
                }
            )
            groupedProjects.forEach { project ->
                projectList.add(projectCard(project, project == selectedProject) { selectProject(project) })
            }
        }
    }

    private fun objectHeaderWithFilters(): Component =
        HorizontalLayout(
            objectHeader,
            HorizontalLayout(
                filterAddMenu(),
                filterControls,
                showMasksCheckbox,
                Button("Сброс") {
                    clearFilters()
                    refreshObjectGallery(resetPaging = true)
                }
            ).apply {
                isPadding = false
                isSpacing = true
                style["gap"] = "6px"
                style["justify-content"] = "flex-end"
                style["align-items"] = "center"
            }
        ).apply {
            setWidthFull()
            setAlignItems(FlexComponent.Alignment.CENTER)
            justifyContentMode = FlexComponent.JustifyContentMode.BETWEEN
            isPadding = false
            isSpacing = true
            style["padding-left"] = "12px"
        }

    private fun projectHeaderWithActions(): Component =
        HorizontalLayout(projectHeader, Button("Импорт").apply {
            addClickListener { openImportDialog() }
        }).apply {
            setWidthFull()
            setAlignItems(FlexComponent.Alignment.CENTER)
            justifyContentMode = FlexComponent.JustifyContentMode.BETWEEN
            isPadding = false
            isSpacing = true
        }

    private fun filterAddMenu(): MenuBar =
        MenuBar().apply {
            val root = addItem("Фильтр +")
            root.subMenu.addItem("Grain class") { addFilter(ObjectFilter.GRAIN_CLASS) }.also {
                filterMenuItems[ObjectFilter.GRAIN_CLASS] = it
            }
            root.subMenu.addItem("Статус") { addFilter(ObjectFilter.STATUS) }.also {
                filterMenuItems[ObjectFilter.STATUS] = it
            }
            root.subMenu.addItem("Уверенность") { addFilter(ObjectFilter.CONFIDENCE) }.also {
                filterMenuItems[ObjectFilter.CONFIDENCE] = it
            }
            root.subMenu.addItem("Дата анализа") { addFilter(ObjectFilter.ANALYSIS_DATE) }.also {
                filterMenuItems[ObjectFilter.ANALYSIS_DATE] = it
            }
            root.subMenu.addItem("Проверено оператором") { addFilter(ObjectFilter.REVIEWED) }.also {
                filterMenuItems[ObjectFilter.REVIEWED] = it
            }
        }

    private fun addFilter(filter: ObjectFilter) {
        if (!activeFilters.add(filter)) return
        rebuildVisibleFilterControls()
        refreshObjectGallery(resetPaging = true)
    }

    private fun removeFilter(filter: ObjectFilter) {
        if (!activeFilters.remove(filter)) return
        when (filter) {
            ObjectFilter.GRAIN_CLASS -> grainClassFilter.clear()
            ObjectFilter.STATUS -> statusFilter.clear()
            ObjectFilter.CONFIDENCE -> {
                confidenceFromFilter.clear()
                confidenceToFilter.clear()
            }
            ObjectFilter.ANALYSIS_DATE -> {
                analysisDateFromFilter.clear()
                analysisDateToFilter.clear()
            }
            ObjectFilter.REVIEWED -> reviewedFilter.clear()
        }
        rebuildVisibleFilterControls()
        refreshObjectGallery(resetPaging = true)
    }

    private fun rebuildVisibleFilterControls() {
        filterControls.removeAll()
        activeFilters.forEach { filter ->
            filterControls.add(
                when (filter) {
                    ObjectFilter.GRAIN_CLASS -> compactFilterGroup("", grainClassFilter, filter)
                    ObjectFilter.STATUS -> compactFilterGroup("Статус", statusFilter, filter)
                    ObjectFilter.CONFIDENCE -> compactPairFilterGroup("Уверенность", confidenceFromFilter, confidenceToFilter, filter)
                    ObjectFilter.ANALYSIS_DATE -> compactPairFilterGroup("Дата", analysisDateFromFilter, analysisDateToFilter, filter)
                    ObjectFilter.REVIEWED -> compactFilterGroup("Проверено", reviewedFilter, filter)
                }
            )
        }
        filterMenuItems.forEach { (filter, item) -> item.isEnabled = filter !in activeFilters }
    }

    private fun compactFilterGroup(title: String, field: Component, filter: ObjectFilter): Component =
        HorizontalLayout().apply {
            isPadding = false
            isSpacing = true
            style["gap"] = "4px"
            setAlignItems(FlexComponent.Alignment.CENTER)
            if (title.isNotBlank()) {
                add(Span(title))
            }
            add(field, removeFilterButton(filter))
        }

    private fun compactPairFilterGroup(title: String, first: Component, second: Component, filter: ObjectFilter): Component =
        HorizontalLayout(Span(title), first, second, removeFilterButton(filter)).apply {
            isPadding = false
            isSpacing = true
            style["gap"] = "3px"
            setAlignItems(FlexComponent.Alignment.CENTER)
        }

    private fun removeFilterButton(filter: ObjectFilter): Button =
        Button("×") { removeFilter(filter) }.apply {
            element.setAttribute("title", "Убрать фильтр")
            style["min-width"] = "24px"
            style["padding"] = "0 6px"
        }

    private fun clearFilters() {
        activeFilters.clear()
        grainClassFilter.clear()
        statusFilter.clear()
        confidenceFromFilter.clear()
        confidenceToFilter.clear()
        analysisDateFromFilter.clear()
        analysisDateToFilter.clear()
        reviewedFilter.clear()
        rebuildVisibleFilterControls()
    }

    private fun initFilterListeners() {
        grainClassFilter.addValueChangeListener {
            val selectedGrainClass = it.value?.trim().orEmpty()
            applyColorIconToCombo(grainClassFilter, grainClassColorMapForCurrentDataset()[selectedGrainClass])
            if (selectedGrainClass == MULTIPHASE_CLASS_NAME) {
                showMasksCheckbox.value = true
            }
            refreshObjectGallery(resetPaging = true)
        }
        statusFilter.addValueChangeListener { refreshObjectGallery(resetPaging = true) }
        confidenceFromFilter.addValueChangeListener { refreshObjectGallery(resetPaging = true) }
        confidenceToFilter.addValueChangeListener { refreshObjectGallery(resetPaging = true) }
        analysisDateFromFilter.addValueChangeListener { refreshObjectGallery(resetPaging = true) }
        analysisDateToFilter.addValueChangeListener { refreshObjectGallery(resetPaging = true) }
        reviewedFilter.addValueChangeListener { refreshObjectGallery(resetPaging = true) }
    }

    private fun refreshFilterOptions(objects: List<DatasetObject>) {
        val currentGrainClassFilter = grainClassFilter.value?.trim().orEmpty()
        val currentStatusFilter = statusFilter.value?.trim().orEmpty()
        val grainClassColors = objects
            .mapNotNull { candidate ->
                val grainClass = candidate.properties["grain_class"]?.trim().orEmpty()
                val maskColor = normalizeMaskColor(candidate.properties["mask_color_rgb"]).orEmpty()
                if (grainClass.isBlank() || maskColor.isBlank()) return@mapNotNull null
                grainClass to maskColor
            }
            .toMap()

        val grainClassItems = (objects.mapNotNull { it.properties["grain_class"] } + currentGrainClassFilter)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        val statusItems = (objects.mapNotNull { it.properties["meta_status"] } + currentStatusFilter)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        grainClassFilter.setItems(grainClassItems)
        grainClassFilter.setRenderer(ComponentRenderer { grainClass ->
            grainClassOptionView(grainClass, grainClassColors[grainClass])
        })
        val actualGrainClassValue = grainClassFilter.value?.trim().orEmpty()
        applyColorIconToCombo(grainClassFilter, grainClassColors[actualGrainClassValue])
        statusFilter.setItems(statusItems)
    }

    private fun refreshObjectGallery(resetPaging: Boolean = false) {
        val project = selectedProject ?: return
        val filteredObjects = applyFilters(project.objects)
        if (resetPaging) {
            visibleObjectLimit = minOf(OBJECT_PAGE_SIZE, filteredObjects.size)
        } else {
            visibleObjectLimit = minOf(max(visibleObjectLimit, OBJECT_PAGE_SIZE), filteredObjects.size)
        }

        if (selectedObject != null && filteredObjects.none { it.id == selectedObject?.id }) {
            selectedObject = null
            selectedObjectCard = null
            updateProperties(null)
        }

        objectHeader.text = "Объекты (${filteredObjects.size}/${project.objects.size})"
        renderObjects(filteredObjects)
        selectedObject?.let { selected ->
            if (filteredObjects.any { it.id == selected.id }) {
                updateObjectSelection(selected)
                scrollSelectedObjectCardIntoView()
            }
        }
    }

    private fun scrollSelectedObjectCardIntoView() {
        selectedObjectCard?.element?.executeJs(
            "this.scrollIntoView({block:'nearest', inline:'nearest', behavior:'smooth'});"
        )
    }

    private fun applyFilters(objects: List<DatasetObject>): List<DatasetObject> {
        val grainClass = grainClassFilter.value?.trim().orEmpty()
        val status = statusFilter.value?.trim().orEmpty()
        val confidenceFrom = confidenceFromFilter.value
        val confidenceTo = confidenceToFilter.value
        val analysisFrom = analysisDateFromFilter.value
        val analysisTo = analysisDateToFilter.value
        val reviewed = reviewedFilter.value

        return objects.filter { obj ->
            if (ObjectFilter.GRAIN_CLASS in activeFilters && grainClass.isNotEmpty() && obj.properties["grain_class"] != grainClass) return@filter false
            if (ObjectFilter.STATUS in activeFilters && status.isNotEmpty() && obj.properties["meta_status"] != status) return@filter false

            val confidence = obj.properties["meta_confidence"]?.toDoubleOrNull()
            if (ObjectFilter.CONFIDENCE in activeFilters && confidenceFrom != null && (confidence == null || confidence < confidenceFrom)) return@filter false
            if (ObjectFilter.CONFIDENCE in activeFilters && confidenceTo != null && (confidence == null || confidence > confidenceTo)) return@filter false

            val analysisDate = obj.properties["meta_analysis_date"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (ObjectFilter.ANALYSIS_DATE in activeFilters && analysisFrom != null && (analysisDate == null || analysisDate.isBefore(analysisFrom))) return@filter false
            if (ObjectFilter.ANALYSIS_DATE in activeFilters && analysisTo != null && (analysisDate == null || analysisDate.isAfter(analysisTo))) return@filter false

            val reviewedValue = obj.properties["meta_reviewed"]?.toBoolean() ?: false
            if (ObjectFilter.REVIEWED in activeFilters && reviewed == "Да" && !reviewedValue) return@filter false
            if (ObjectFilter.REVIEWED in activeFilters && reviewed == "Нет" && reviewedValue) return@filter false
            true
        }
    }

    private fun openImportDialog() {
        val availableDatasets = listDatasetDirectories()
        if (availableDatasets.isEmpty()) {
            Notification.show("В каталоге $datasetsRoot нет датасетов для импорта.", 3500, Notification.Position.MIDDLE)
                .addThemeVariants(NotificationVariant.LUMO_WARNING)
            return
        }

        val importedDatasets = importedDatasetRelativePaths()
        val datasetOptions = availableDatasets.map { DatasetFolderOption(it, importedDatasets.contains(it)) }

        val datasetSelector = ComboBox<DatasetFolderOption>("Каталог датасета").apply {
            setItems(datasetOptions)
            setItemLabelGenerator { option ->
                if (option.isImported) "${option.relativePath} (уже импортирован)" else option.relativePath
            }
            isAllowCustomValue = false
            isClearButtonVisible = true
            placeholder = "Выберите каталог"
            setWidthFull()
        }
        val batchField = TextField("Партия").apply {
            placeholder = "Введите имя партии"
            setWidthFull()
        }
        datasetSelector.addValueChangeListener { event ->
            val selectedPath = event.value?.relativePath ?: return@addValueChangeListener
            batchField.value = defaultBatchName(selectedPath)
        }

        val dialog = Dialog().apply {
            headerTitle = "Импорт проекта"
            add(VerticalLayout(datasetSelector, batchField).apply {
                isPadding = false
                isSpacing = true
                setWidth("460px")
            })
        }
        val progressText = Paragraph("Ожидание запуска…").apply {
            isVisible = false
            style["margin"] = "0"
        }
        val progressBar = ProgressBar().apply {
            isVisible = false
            isIndeterminate = true
            setWidthFull()
        }
        (dialog.children.findFirst().orElse(null) as? VerticalLayout)?.add(progressText, progressBar)

        val cancelRequested = AtomicBoolean(false)
        var importInProgress = false

        val importButton = Button("Импортировать")
        val closeButton = Button("Закрыть") { dialog.close() }
        val stopButton = Button("Остановить").apply {
            isVisible = false
            addClickListener {
                cancelRequested.set(true)
                isEnabled = false
                progressText.text = "Остановка импорта…"
            }
        }
        importButton.addClickListener {
            val selectedFolder = datasetSelector.value?.relativePath
            if (selectedFolder.isNullOrBlank()) {
                Notification.show("Сначала выберите каталог датасета.", 2500, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_CONTRAST)
                return@addClickListener
            }
            val batchName = batchField.value.trim().ifBlank { defaultBatchName(selectedFolder) }
            if (importInProgress) {
                return@addClickListener
            }

            val currentUi = ui.orElseThrow { IllegalStateException("UI context is not available for import.") }
            importInProgress = true
            cancelRequested.set(false)
            importButton.isEnabled = false
            datasetSelector.isEnabled = false
            progressText.isVisible = true
            progressBar.isVisible = true
            progressText.text = "Подготовка импорта…"
            progressBar.isIndeterminate = true
            stopButton.isVisible = true
            stopButton.isEnabled = true

            thread(name = "dataset-import-$selectedFolder", isDaemon = true) {
                val importedProject = runCatching {
                    importProjectFromDataset(selectedFolder, batchName, cancelRequested) { progress ->
                        runCatching {
                            currentUi.accessSynchronously {
                                progressText.text = progress.message
                                progressBar.isIndeterminate = progress.indeterminate
                                if (!progress.indeterminate && progress.total > 0) {
                                    progressBar.value = progress.current.toDouble() / progress.total.toDouble()
                                }
                            }
                        }.onFailure { updateError ->
                            log.debug("Не удалось обновить прогресс импорта в UI: {}", updateError.message)
                        }
                    }
                }
                currentUi.access {
                    importInProgress = false
                    importButton.isEnabled = true
                    datasetSelector.isEnabled = true
                    stopButton.isVisible = false
                    stopButton.isEnabled = true

                    importedProject.exceptionOrNull()?.let { throwable ->
                        val error = throwable.cause ?: throwable
                        if (error is CancellationException) {
                            Notification.show("Импорт остановлен пользователем.", 2500, Notification.Position.MIDDLE)
                                .addThemeVariants(NotificationVariant.LUMO_CONTRAST)
                            dialog.close()
                            return@access
                        }
                        log.error("Не удалось импортировать датасет {}", selectedFolder, error)
                        Notification.show(
                            "Не удалось импортировать датасет: ${error.message ?: "неизвестная ошибка"}",
                            4500,
                            Notification.Position.MIDDLE
                        ).addThemeVariants(NotificationVariant.LUMO_ERROR)
                        dialog.close()
                        return@access
                    }

                    val project = importedProject.getOrNull() ?: return@access
                    val projectWithResources = resolveProjectResources(project)
                    projects.removeAll { it.id == projectWithResources.id }
                    projects.add(0, projectWithResources)
                    renderProjects()
                    dialog.close()
                    Notification.show(
                        "Импортировано: ${projectWithResources.name}. Откройте проект для просмотра объектов.",
                        3000,
                        Notification.Position.BOTTOM_START
                    )
                }
            }
        }

        dialog.footer.add(
            closeButton,
            stopButton,
            importButton
        )

        dialog.open()
    }

    private fun importedDatasetRelativePaths(): Set<String> =
        projects.mapNotNull { project ->
            runCatching { Path.of(project.source) }.getOrNull()
                ?.takeIf { it.startsWith(datasetsRoot) }
                ?.let { datasetsRoot.relativize(it).toString().replace('\\', '/') }
        }.toSet()

    private fun listDatasetDirectories(): List<String> {
        if (!Files.isDirectory(datasetsRoot)) {
            return emptyList()
        }
        Files.walk(datasetsRoot).use { stream ->
            return stream
                .filter { Files.isDirectory(it) && it != datasetsRoot }
                .filter { dir ->
                    val hasLegend = Files.exists(dir.resolve("legend.json"))
                    val fileNames = Files.list(dir).use { files ->
                        files.map { it.fileName.toString() }.collect(Collectors.toList())
                    }
                    val hasImages = fileNames.any { it.startsWith("img-") && it.endsWith(".png") }
                    val hasMasks = fileNames.any { it.startsWith("msk_rgb-") && it.endsWith(".png") }
                    hasLegend && hasImages && hasMasks
                }
                .sorted()
                .map { datasetsRoot.relativize(it).toString().replace('\\', '/') }
                .collect(Collectors.toList())
        }
    }

    private fun defaultBatchName(datasetDirectoryName: String): String {
        val datasetPath = Path.of(datasetDirectoryName)
        return datasetPath.parent?.fileName?.toString().orEmpty().ifBlank { "Без партии" }
    }

    private fun importProjectFromDataset(
        datasetDirectoryName: String,
        batchName: String,
        cancelRequested: AtomicBoolean = AtomicBoolean(false),
        onProgress: (ImportProgress) -> Unit = {}
    ): DatasetProject {
        throwIfCancelled(cancelRequested)
        onProgress(ImportProgress("Проверка каталога…", 0, 0, true))
        val datasetPath = datasetsRoot.resolve(datasetDirectoryName)
        require(Files.isDirectory(datasetPath)) { "Каталог $datasetPath не найден." }

        throwIfCancelled(cancelRequested)
        onProgress(ImportProgress("Чтение списка файлов…", 0, 0, true))
        val rawImages = Files.list(datasetPath).use { stream ->
            stream.filter { it.fileName.toString().startsWith("img-") && it.fileName.toString().endsWith(".png") }
                .sorted()
                .collect(Collectors.toList())
        }
        val rgbMasks = Files.list(datasetPath).use { stream ->
            stream.filter { it.fileName.toString().startsWith("msk_rgb-") && it.fileName.toString().endsWith(".png") }
                .sorted()
                .collect(Collectors.toList())
        }

        require(rawImages.isNotEmpty()) { "В каталоге нет файлов img-*.png." }
        require(rgbMasks.isNotEmpty()) { "В каталоге нет файлов msk_rgb-*.png." }

        val legendFile = datasetPath.resolve("legend.json")
        val legend = if (Files.exists(legendFile)) {
            parseLegend(legendFile)
        } else {
            emptyMap()
        }

        val rawBySuffix = rawImages.associateBy { it.fileName.toString().removePrefix("img-").removeSuffix(".png") }
        val maskBySuffix = rgbMasks.associateBy { it.fileName.toString().removePrefix("msk_rgb-").removeSuffix(".png") }
        val commonSuffixes = rawBySuffix.keys.intersect(maskBySuffix.keys).sorted()
        require(commonSuffixes.isNotEmpty()) { "Не найдено пар img-*.png и msk_rgb-*.png с одинаковым индексом." }

        val datasetSignature = buildDatasetSignature(
            datasetPath = datasetPath,
            sourceBySuffix = rawBySuffix,
            maskBySuffix = maskBySuffix,
            commonSuffixes = commonSuffixes
        )
        val cacheDir = cacheRoot.resolve("$datasetDirectoryName-$datasetSignature")
        Files.createDirectories(cacheDir)
        val manifestPath = cacheDir.resolve("manifest.json")

        val cachedObjects = if (Files.exists(manifestPath)) loadCachedObjects(manifestPath) else emptyList()
        val resolvedObjects = if (cachedObjects.isNotEmpty() && cachedObjects.all { Files.exists(cacheDir.resolve(it.previewFileName)) }) {
            onProgress(ImportProgress("Загрузка из кэша…", 1, 1, false))
            cachedObjects
        } else {
            val generated = mutableListOf<CachedDatasetObject>()
            commonSuffixes.forEachIndexed { index, suffix ->
                throwIfCancelled(cancelRequested)
                onProgress(ImportProgress("Обработка масок: ${index + 1}/${commonSuffixes.size}", index, commonSuffixes.size, false))
                val sourceImagePath = rawBySuffix.getValue(suffix)
                val maskImagePath = maskBySuffix.getValue(suffix)
                val extractedObjects = runCatching {
                    executeMaskExtractionWithTimeout(
                        datasetDirectoryName = datasetDirectoryName,
                        suffix = suffix,
                        sourceImagePath = sourceImagePath,
                        maskImagePath = maskImagePath,
                        legend = legend,
                        cacheDir = cacheDir,
                        cancelRequested = cancelRequested
                    )
                }.getOrElse { error ->
                    throw IllegalStateException(
                        "Ошибка при обработке пары ${index + 1}/${commonSuffixes.size} " +
                            "(индекс=$suffix, source=${sourceImagePath.fileName}, mask=${maskImagePath.fileName}): " +
                            (error.message ?: "неизвестная ошибка"),
                        error
                    )
                }
                generated += extractedObjects
            }
            onProgress(ImportProgress("Сохранение кэша…", commonSuffixes.size, commonSuffixes.size, false))
            saveCachedObjects(manifestPath, generated)
            generated
        }

        onProgress(ImportProgress("Подготовка проекта…", 1, 1, false))
        val projectPreview = rawBySuffix[commonSuffixes.first()] ?: rgbMasks.first()
        val objectsForUi = resolvedObjects.map { cached ->
            val properties = cached.properties.toMutableMap()
            cached.properties["mask_crop_file"]?.let { maskFile ->
                properties["mask_crop_url"] = cacheDir.resolve(maskFile).toString()
            }
            DatasetObject(
                id = cached.id,
                name = cached.name,
                category = cached.category,
                previewUrl = cacheDir.resolve(cached.previewFileName).toString(),
                properties = properties
            )
        }

        val projectName = Path.of(datasetDirectoryName).fileName?.toString().orEmpty().ifBlank { datasetDirectoryName }
        return DatasetProject(
            id = "dataset-$datasetDirectoryName",
            name = projectName,
            batch = batchName.ifBlank { "Без партии" },
            type = "Импорт из /siams/images",
            source = datasetPath.toString(),
            previewUrl = projectPreview.toString(),
            objects = objectsForUi
        )
    }

    private fun resolveProjectResources(project: DatasetProject): DatasetProject {
        val resolvedPreview = resolvePreviewUrl(project.previewUrl)
        val resolvedObjects = project.objects.map { obj ->
            val resolvedProperties = obj.properties.toMutableMap()
            obj.properties["mask_crop_url"]?.let { resolvedProperties["mask_crop_url"] = resolvePreviewUrl(it) }
            obj.copy(
                previewUrl = resolvePreviewUrl(obj.previewUrl),
                properties = resolvedProperties
            )
        }
        return project.copy(previewUrl = resolvedPreview, objects = resolvedObjects)
    }

    private fun resolvePreviewUrl(rawValue: String): String {
        val path = runCatching { Path.of(rawValue) }.getOrNull()
        if (path != null && Files.exists(path)) {
            return fileResourceUrl(path)
        }
        return rawValue
    }

    private fun extractObjectsFromPairToCache(
        datasetDirectoryName: String,
        suffix: String,
        sourceImagePath: Path,
        maskImagePath: Path,
        legend: Map<Int, String>,
        cacheDir: Path,
        cancelRequested: AtomicBoolean
    ): List<CachedDatasetObject> {
        val source = ImageIO.read(sourceImagePath.toFile())
            ?: throw IllegalArgumentException("Не удалось прочитать изображение ${sourceImagePath.fileName}")
        val mask = ImageIO.read(maskImagePath.toFile())
            ?: throw IllegalArgumentException("Не удалось прочитать маску ${maskImagePath.fileName}")

        require(source.width == mask.width && source.height == mask.height) {
            "Размеры source и mask не совпадают для индекса $suffix"
        }

        val objects = mutableListOf<CachedDatasetObject>()
        val width = mask.width
        val height = mask.height
        val visited = BooleanArray(width * height)
        var grainCounter = 0

        for (y in 0 until height) {
            throwIfCancelled(cancelRequested)
            for (x in 0 until width) {
                val color = mask.rgbNoAlpha(x, y)
                if (color == 0x000000) continue

                val linearIndex = y * width + x
                if (visited[linearIndex]) continue

                val component = collectConnectedComponent(mask, x, y, visited)
                if (component.points.isEmpty()) continue

                grainCounter += 1
                val phaseStatistics = collectPhaseStatistics(mask, component.points)
                val objectClassInfo = resolveObjectClassInfo(phaseStatistics, legend)
                val previewFileName = "grain-$suffix-$grainCounter.png"
                val previewPath = cacheDir.resolve(previewFileName)
                Files.write(previewPath, buildMaskedCrop(source, component))
                val maskPreviewFileName = "grain-mask-$suffix-$grainCounter.png"
                val maskPreviewPath = cacheDir.resolve(maskPreviewFileName)
                Files.write(maskPreviewPath, buildMaskedCrop(mask, component))

                objects += CachedDatasetObject(
                    id = "$datasetDirectoryName-$suffix-$grainCounter",
                    name = objectClassInfo.grainClass,
                    category = "OreGrain",
                    previewFileName = previewFileName,
                    properties = mapOf(
                        "dataset" to datasetDirectoryName,
                        "grain_id" to "$suffix-$grainCounter",
                        "source_image_file" to sourceImagePath.fileName.toString(),
                        "mask_rgb_file" to maskImagePath.fileName.toString(),
                        "grain_class" to objectClassInfo.grainClass,
                        "mask_color_rgb" to objectClassInfo.classColorHex,
                        "object_phase_type" to objectClassInfo.phaseType,
                        "phase_count" to phaseStatistics.size.toString(),
                        "phase_area_shares" to formatPhaseAreaShares(phaseStatistics, legend),
                        "mask_crop_file" to maskPreviewFileName,
                        "crop_width" to (component.maxX - component.minX + 1).toString(),
                        "crop_height" to (component.maxY - component.minY + 1).toString()
                    )
                )
            }
        }

        return objects
    }

    private fun executeMaskExtractionWithTimeout(
        datasetDirectoryName: String,
        suffix: String,
        sourceImagePath: Path,
        maskImagePath: Path,
        legend: Map<Int, String>,
        cacheDir: Path,
        cancelRequested: AtomicBoolean
    ): List<CachedDatasetObject> {
        val extractorExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mask-extract-$suffix").apply { isDaemon = true }
        }
        return try {
            val task = extractorExecutor.submit<List<CachedDatasetObject>> {
                extractObjectsFromPairToCache(
                    datasetDirectoryName = datasetDirectoryName,
                    suffix = suffix,
                    sourceImagePath = sourceImagePath,
                    maskImagePath = maskImagePath,
                    legend = legend,
                    cacheDir = cacheDir,
                    cancelRequested = cancelRequested
                )
            }
            task.get(MASK_PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        } catch (timeout: TimeoutException) {
            cancelRequested.set(true)
            throw IllegalStateException(
                "Превышено время обработки маски для индекса $suffix " +
                    "(>${MASK_PROCESS_TIMEOUT_MINUTES} минут).",
                timeout
            )
        } catch (executionError: ExecutionException) {
            throw (executionError.cause ?: executionError)
        } finally {
            extractorExecutor.shutdownNow()
        }
    }

    private fun collectConnectedComponent(
        mask: BufferedImage,
        startX: Int,
        startY: Int,
        visited: BooleanArray
    ): ConnectedComponent {
        val width = mask.width
        val height = mask.height
        val queue = ArrayDeque<Point>()
        val points = mutableListOf<Point>()

        var minX = startX
        var maxX = startX
        var minY = startY
        var maxY = startY

        fun visit(x: Int, y: Int) {
            if (x < 0 || y < 0 || x >= width || y >= height) return
            val idx = y * width + x
            if (visited[idx]) return
            if (mask.rgbNoAlpha(x, y) == 0x000000) return
            visited[idx] = true
            queue.addLast(Point(x, y))
        }

        visit(startX, startY)
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            points += p

            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y

            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    visit(p.x + dx, p.y + dy)
                }
            }
        }

        return ConnectedComponent(points, minX, minY, maxX, maxY)
    }

    private fun collectPhaseStatistics(
        mask: BufferedImage,
        points: List<Point>
    ): Map<Int, Int> {
        val stats = linkedMapOf<Int, Int>()
        points.forEach { point ->
            val color = mask.rgbNoAlpha(point.x, point.y)
            if (color == 0x000000) return@forEach
            stats[color] = (stats[color] ?: 0) + 1
        }
        return stats
    }

    private fun resolveObjectClassInfo(
        phaseStatistics: Map<Int, Int>,
        legend: Map<Int, String>
    ): ObjectClassInfo {
        if (phaseStatistics.size == 1) {
            val onlyColor = phaseStatistics.keys.first()
            val grainClass = legend[onlyColor] ?: "Unknown(0x${onlyColor.toString(16).padStart(6, '0').uppercase()})"
            return ObjectClassInfo(
                grainClass = grainClass,
                classColorHex = toHexColor(onlyColor),
                phaseType = "single_phase"
            )
        }

        return ObjectClassInfo(
            grainClass = "Многофазный",
            classColorHex = "0xAAAAAA",
            phaseType = "multi_phase"
        )
    }

    private fun formatPhaseAreaShares(
        phaseStatistics: Map<Int, Int>,
        legend: Map<Int, String>
    ): String {
        val total = phaseStatistics.values.sum().takeIf { it > 0 } ?: return "{}"
        val sorted = phaseStatistics.entries.sortedByDescending { it.value }
        return sorted.joinToString(prefix = "{", postfix = "}") { (color, area) ->
            val phaseName = legend[color] ?: "Unknown(${toHexColor(color)})"
            val share = area.toDouble() / total.toDouble()
            "\"$phaseName\":${"%.6f".format(Locale.US, share)}"
        }
    }

    private fun toHexColor(color: Int): String = "0x${color.toString(16).padStart(6, '0').uppercase()}"

    private fun buildMaskedCrop(source: BufferedImage, component: ConnectedComponent): ByteArray {
        val cropWidth = component.maxX - component.minX + 1
        val cropHeight = component.maxY - component.minY + 1
        val crop = BufferedImage(cropWidth, cropHeight, BufferedImage.TYPE_INT_ARGB)

        component.points.forEach { point ->
            val sourceArgb = source.getRGB(point.x, point.y)
            crop.setRGB(point.x - component.minX, point.y - component.minY, sourceArgb or (0xFF shl 24))
        }

        return ByteArrayOutputStream().use { output ->
            ImageIO.write(crop, "png", output)
            output.toByteArray()
        }
    }

    private fun BufferedImage.rgbNoAlpha(x: Int, y: Int): Int = getRGB(x, y) and 0xFFFFFF

    private fun parseLegend(legendFile: Path): Map<Int, String> {
        val rootNode = ObjectMapper().readTree(legendFile.toFile())
        if (!rootNode.isArray) return emptyMap()
        return rootNode
            .mapNotNull { node ->
                val color = node.path("color").takeIf { it.canConvertToInt() }?.asInt() ?: return@mapNotNull null
                val name = node.path("name").takeIf { !it.isMissingNode && !it.isNull }?.asText() ?: return@mapNotNull null
                color to name
            }
            .toMap()
    }

    private fun fileResourceUrl(file: Path): String =
        StreamResource(file.fileName.toString()) { Files.newInputStream(file) }.let { resource ->
            val currentUi = ui.orElseThrow { IllegalStateException("UI context is not available for resource registration.") }
            val uri = currentUi.session.resourceRegistry.registerResource(resource).resourceUri.toString()
            if (uri.startsWith("/")) uri else "/$uri"
        }

    private fun buildDatasetSignature(
        datasetPath: Path,
        sourceBySuffix: Map<String, Path>,
        maskBySuffix: Map<String, Path>,
        commonSuffixes: List<String>
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        commonSuffixes.forEach { suffix ->
            updateDigestWithFile(digest, sourceBySuffix.getValue(suffix))
            updateDigestWithFile(digest, maskBySuffix.getValue(suffix))
        }
        val legend = datasetPath.resolve("legend.json")
        if (Files.exists(legend)) {
            updateDigestWithFile(digest, legend)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun updateDigestWithFile(digest: MessageDigest, file: Path) {
        val attrs = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java)
        digest.update(file.fileName.toString().toByteArray())
        digest.update(attrs.size().toString().toByteArray())
        digest.update(attrs.lastModifiedTime().toMillis().toString().toByteArray())
    }

    private fun throwIfCancelled(cancelRequested: AtomicBoolean) {
        if (cancelRequested.get()) {
            throw CancellationException("Импорт отменён.")
        }
    }

    private fun saveCachedObjects(manifestPath: Path, objects: List<CachedDatasetObject>) {
        val mapper = ObjectMapper()
        val root = mapper.createArrayNode()
        objects.forEach { item ->
            val node = mapper.createObjectNode()
            node.put("id", item.id)
            node.put("name", item.name)
            node.put("category", item.category)
            node.put("previewFileName", item.previewFileName)
            val propertiesNode = mapper.createObjectNode()
            item.properties.forEach { (k, v) -> propertiesNode.put(k, v) }
            node.set<com.fasterxml.jackson.databind.JsonNode>("properties", propertiesNode)
            root.add(node)
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), root)
    }

    private fun loadCachedObjects(manifestPath: Path): List<CachedDatasetObject> {
        val mapper = ObjectMapper()
        val root = mapper.readTree(manifestPath.toFile())
        if (!root.isArray) return emptyList()
        return root.mapNotNull { node ->
            val id = node.path("id").asText(null) ?: return@mapNotNull null
            val name = node.path("name").asText(null) ?: return@mapNotNull null
            val category = node.path("category").asText(null) ?: return@mapNotNull null
            val preview = node.path("previewFileName").asText(null) ?: return@mapNotNull null
            val propertiesNode = node.path("properties")
            val props = mutableMapOf<String, String>()
            if (propertiesNode.isObject) {
                propertiesNode.fields().forEach { (k, v) -> props[k] = v.asText() }
            }
            CachedDatasetObject(id, name, category, preview, props)
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

    private fun objectCard(obj: DatasetObject, selected: Boolean, onClick: () -> Unit): com.vaadin.flow.component.html.Div {
        val titleColor = obj.properties["mask_color_rgb"]
            ?.removePrefix("0x")
            ?.takeIf { it.length == 6 && it.all { ch -> ch.isDigit() || ch.lowercaseChar() in 'a'..'f' } }
            ?.let { "#$it" }
            ?: "white"
        val rawWidth = obj.properties["crop_width"]?.toIntOrNull() ?: 240
        val rawHeight = obj.properties["crop_height"]?.toIntOrNull() ?: 180
        val cardHeight = 180
        val scale = min(4.0, cardHeight.toDouble() / rawHeight.toDouble())
        val imageDisplayWidth = max(1.0, rawWidth * scale).roundToInt()
        val imageDisplayHeight = max(1.0, rawHeight * scale).roundToInt()
        val cardWidth = max(80.0, imageDisplayWidth.toDouble()).roundToInt()

        val image = Image(obj.previewUrl, obj.name).apply {
            style["display"] = "block"
            style["width"] = "${imageDisplayWidth}px"
            style["height"] = "${imageDisplayHeight}px"
            style["object-fit"] = "contain"
            style["border-radius"] = "10px"
            style["image-rendering"] = "pixelated"
        }
        val imageStack = com.vaadin.flow.component.html.Div(image).apply {
            style["position"] = "relative"
            style["width"] = "${imageDisplayWidth}px"
            style["height"] = "${imageDisplayHeight}px"
            style["display"] = "flex"
            style["align-items"] = "center"
            style["justify-content"] = "center"
        }

        if (shouldShowMaskOverlay(obj)) {
            val maskOverlay = Image(obj.properties["mask_crop_url"], "${obj.name} mask").apply {
                style["position"] = "absolute"
                style["left"] = "0"
                style["top"] = "0"
                style["width"] = "${imageDisplayWidth}px"
                style["height"] = "${imageDisplayHeight}px"
                style["object-fit"] = "contain"
                style["opacity"] = "0.42"
                style["image-rendering"] = "pixelated"
                style["pointer-events"] = "none"
            }
            imageStack.add(maskOverlay)
        }

        val cardTitle = Span(obj.properties["grain_class"] ?: obj.name).apply {
            style["font-weight"] = "600"
            style["display"] = "block"
            style["color"] = titleColor
            style["line-height"] = "1.25"
        }

        val titleOverlay = com.vaadin.flow.component.html.Div(cardTitle).apply {
            style["position"] = "absolute"
            style["left"] = "0"
            style["right"] = "0"
            style["top"] = "0"
            style["padding"] = "8px 12px 8px"
            style["background"] = "linear-gradient(to bottom, rgba(0,0,0,0.75), rgba(0,0,0,0.15))"
            style["border-radius"] = "10px 10px 0 0"
        }

        return com.vaadin.flow.component.html.Div(imageStack, titleOverlay).apply {
            style["position"] = "relative"
            style["display"] = "inline-block"
            style["flex"] = "1 0 ${cardWidth}px"
            style["line-height"] = "0"
            style["min-width"] = "${cardWidth}px"
            style["height"] = "${cardHeight}px"
            style["border-radius"] = "10px"
            style["cursor"] = "pointer"
            style["overflow"] = "hidden"
            style["background"] = "black"
            style["display"] = "flex"
            style["align-items"] = "center"
            style["justify-content"] = "center"
            styleObjectSelection(selected, style)
            addClickListener { onClick() }
        }
    }

    private fun shouldShowMaskOverlay(obj: DatasetObject): Boolean {
        if (!showMasksCheckbox.value) return false
        val hasMaskUrl = !obj.properties["mask_crop_url"].isNullOrBlank()
        return hasMaskUrl
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
            style["box-shadow"] = "0 0 0 4px rgba(0,0,0,0.55), 0 0 0 8px var(--lumo-primary-color)"
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
            .filterNot { it.key == "mask_color_rgb" }
            .forEach { (name, value) ->
                form.addFormItem(propertyInput(name, value, obj), prettyLabel(name))
            }

        return form
    }

    private fun propertyInput(name: String, value: String, obj: DatasetObject): Component =
        when (name) {
            "size_fraction" -> comboEditor(name, value, obj, listOf("40.+60", "60.+100", "-100"))
            "grain_class" -> grainClassEditor(value, obj)
            "shape" -> comboEditor(name, value, obj, listOf("угловатое", "окатанное", "удлиненное"))
            "material" -> comboEditor(name, value, obj, listOf("руда", "концентрат", "шламы"))
            "brightness" -> comboEditor(name, value, obj, listOf("низкая", "средняя", "высокая"))
            "contrast" -> comboEditor(name, value, obj, listOf("низкий", "средний", "высокий"))
            "mask_color_rgb" -> colorPreviewEditor(value)
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

    private fun grainClassEditor(value: String, obj: DatasetObject): ComboBox<String> {
        val isMultiphaseObject = obj.properties["object_phase_type"] == "multi_phase" || value.trim() == MULTIPHASE_CLASS_NAME
        val colorByGrainClass = grainClassColorMapForCurrentDataset()
        val editor = ComboBox<String>().apply {
            setItems(
                if (isMultiphaseObject) listOf(MULTIPHASE_CLASS_NAME)
                else grainClassOptionsForCurrentDataset(value)
            )
            isAllowCustomValue = !isMultiphaseObject
            this.value = value
            setWidthFull()
            setItemLabelGenerator { it }
            setRenderer(ComponentRenderer { grainClass ->
                grainClassOptionView(grainClass, colorByGrainClass[grainClass])
            })
            isReadOnly = isMultiphaseObject
        }

        fun applyLinkedColor(selectedGrainClass: String) {
            val normalizedValue = selectedGrainClass.trim()
            val previousValue = obj.properties["grain_class"]?.trim().orEmpty()
            obj.properties["grain_class"] = normalizedValue
            colorByGrainClass[normalizedValue]?.let { nativeMaskColor ->
                obj.properties["mask_color_rgb"] = nativeMaskColor
            }
            applyColorIconToCombo(editor, obj.properties["mask_color_rgb"])
            selectedProject?.let { refreshFilterOptions(it.objects) }
            val activeFilterValue = grainClassFilter.value?.trim().orEmpty()
            val shouldKeepEditedObjectVisible =
                ObjectFilter.GRAIN_CLASS in activeFilters &&
                    activeFilterValue.isNotBlank() &&
                    activeFilterValue == previousValue &&
                    normalizedValue != activeFilterValue

            if (shouldKeepEditedObjectVisible) {
                if (normalizedValue.isBlank()) {
                    grainClassFilter.clear()
                } else {
                    grainClassFilter.value = normalizedValue
                }
            } else {
                refreshObjectGallery(resetPaging = false)
            }
        }

        applyColorIconToCombo(editor, obj.properties["mask_color_rgb"])
        if (!isMultiphaseObject) {
            editor.addValueChangeListener { applyLinkedColor(it.value ?: "") }
            editor.addCustomValueSetListener {
                editor.value = it.detail
                applyLinkedColor(it.detail)
            }
        }
        return editor
    }

    private fun grainClassOptionsForCurrentDataset(currentValue: String): List<String> {
        val datasetOptions = selectedProject
            ?.objects
            ?.mapNotNull { it.properties["grain_class"]?.trim() }
            ?.filter { it.isNotBlank() && it != MULTIPHASE_CLASS_NAME }
            ?.distinct()
            ?.sorted()
            .orEmpty()

        return (datasetOptions + currentValue.trim())
            .filter { it.isNotBlank() }
            .filter { it == currentValue.trim() || it != MULTIPHASE_CLASS_NAME }
            .distinct()
    }

    private fun grainClassColorMapForCurrentDataset(): Map<String, String> =
        selectedProject
            ?.objects
            ?.mapNotNull { candidate ->
                val grainClass = candidate.properties["grain_class"]?.trim().orEmpty()
                val maskColor = normalizeMaskColor(candidate.properties["mask_color_rgb"]).orEmpty()
                if (grainClass.isBlank() || maskColor.isBlank()) return@mapNotNull null
                grainClass to maskColor
            }
            ?.toMap()
            .orEmpty()

    private fun normalizeMaskColor(rawColor: String?): String? {
        val clean = rawColor
            ?.removePrefix("0x")
            ?.removePrefix("#")
            ?.trim()
            ?.lowercase()
            ?: return null
        if (clean.length != 6 || clean.any { !it.isDigit() && it !in 'a'..'f' }) return null
        return "0x${clean.uppercase()}"
    }

    private fun applyColorIconToCombo(editor: ComboBox<String>, rawMaskColor: String?) {
        val maskColor = normalizeMaskColor(rawMaskColor)
        if (maskColor == null) {
            editor.prefixComponent = null
            return
        }
        editor.prefixComponent = colorDot(maskColor)
    }

    private fun grainClassOptionView(grainClass: String, rawMaskColor: String?): Component =
        HorizontalLayout(colorDot(rawMaskColor), Span(grainClass)).apply {
            isPadding = false
            isSpacing = true
            setAlignItems(FlexComponent.Alignment.CENTER)
            style["gap"] = "8px"
        }

    private fun colorDot(rawMaskColor: String?): Component {
        val maskColor = normalizeMaskColor(rawMaskColor)
        val cssHex = if (maskColor == null) "transparent" else "#" + maskColor.removePrefix("0x")
        val borderColor = if (maskColor == null) "var(--lumo-contrast-30pct)" else "rgba(255,255,255,0.35)"
        return com.vaadin.flow.component.html.Div().apply {
            style["width"] = "10px"
            style["height"] = "10px"
            style["border-radius"] = "999px"
            style["background"] = cssHex
            style["border"] = "1px solid $borderColor"
            style["display"] = "inline-block"
            style["flex-shrink"] = "0"
        }
    }

    private fun colorPreviewEditor(value: String): Component {
        val colorHex = value.removePrefix("0x")
            .takeIf { it.length == 6 && it.all { ch -> ch.isDigit() || ch.lowercaseChar() in 'a'..'f' } }
            ?.let { "#$it" }
            ?: "#000000"
        val swatch = com.vaadin.flow.component.html.Div().apply {
            style["width"] = "28px"
            style["height"] = "28px"
            style["border-radius"] = "6px"
            style["border"] = "1px solid var(--lumo-contrast-40pct)"
            style["background"] = colorHex
            style["flex-shrink"] = "0"
        }
        val text = Span(value).apply {
            style["font-family"] = "monospace"
            style["font-size"] = "var(--lumo-font-size-m)"
        }
        return HorizontalLayout(swatch, text).apply {
            isPadding = false
            isSpacing = true
            setWidthFull()
            setAlignItems(FlexComponent.Alignment.CENTER)
        }
    }

    private fun comboEditor(name: String, value: String, obj: DatasetObject, options: List<String>): ComboBox<String> =
        ComboBox<String>().apply {
            setItems(options)
            isAllowCustomValue = true
            this.value = value
            setWidthFull()
            addValueChangeListener { event ->
                obj.properties[name] = event.value ?: ""
                if (name == "grain_class") {
                    selectedProject?.let { refreshFilterOptions(it.objects) }
                    refreshObjectGallery(resetPaging = false)
                }
            }
            addCustomValueSetListener { event ->
                obj.properties[name] = event.detail
                this.value = event.detail
                if (name == "grain_class") {
                    selectedProject?.let { refreshFilterOptions(it.objects) }
                    refreshObjectGallery(resetPaging = false)
                }
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
            addValueChangeListener {
                obj.properties["meta_status"] = it.value ?: ""
                selectedProject?.let { project -> refreshFilterOptions(project.objects) }
                refreshObjectGallery(resetPaging = false)
            }
        }

        val confidence = NumberField("Уверенность, %").apply {
            value = obj.properties["meta_confidence"]?.toDoubleOrNull() ?: 85.0
            min = 0.0
            max = 100.0
            step = 1.0
            addValueChangeListener {
                obj.properties["meta_confidence"] = ((it.value ?: 0.0).toInt()).toString()
                refreshObjectGallery(resetPaging = false)
            }
        }

        val analysisDate = DatePicker("Дата анализа").apply {
            value = obj.properties["meta_analysis_date"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: LocalDate.now()
            addValueChangeListener {
                obj.properties["meta_analysis_date"] = it.value?.toString().orEmpty()
                refreshObjectGallery(resetPaging = false)
            }
        }

        val reviewed = Checkbox("Проверено оператором").apply {
            value = obj.properties["meta_reviewed"]?.toBoolean() ?: false
            addValueChangeListener {
                obj.properties["meta_reviewed"] = it.value.toString()
                refreshObjectGallery(resetPaging = false)
            }
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
    val batch: String,
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

private data class Point(
    val x: Int,
    val y: Int
)

private data class ConnectedComponent(
    val points: List<Point>,
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int
)

private data class CachedDatasetObject(
    val id: String,
    val name: String,
    val category: String,
    val previewFileName: String,
    val properties: Map<String, String>
)

private data class ObjectClassInfo(
    val grainClass: String,
    val classColorHex: String,
    val phaseType: String
)

private data class ImportProgress(
    val message: String,
    val current: Int,
    val total: Int,
    val indeterminate: Boolean
)

private data class DatasetFolderOption(
    val relativePath: String,
    val isImported: Boolean
)

private enum class ObjectFilter {
    GRAIN_CLASS,
    STATUS,
    CONFIDENCE,
    ANALYSIS_DATE,
    REVIEWED
}

private fun demoProjects(): List<DatasetProject> = emptyList()
