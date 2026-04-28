@file:Suppress("removal")

package com.example.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.contextmenu.ContextMenu
import com.vaadin.flow.component.contextmenu.MenuItem
import com.vaadin.flow.component.contextmenu.SubMenu
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.dnd.DragSource
import com.vaadin.flow.component.dnd.DropEffect
import com.vaadin.flow.component.dnd.DropTarget
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
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.Tabs
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.dom.Style
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.server.StreamResource
import com.vaadin.flow.router.Route
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mvysny.kaributools.addIconItem
import org.slf4j.LoggerFactory
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
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
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
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
    private val collections = mutableListOf<DatasetCollection>()

    private val projectHeader = H4("Проекты")
    private val objectHeader = H4("Объекты")
    private val projectList = Div()
    private val objectGallery = Div()
    private val activeFilters = linkedSetOf<ObjectFilter>()
    private val filterAddMenuBar = MenuBar()
    private val filterMenuItems = mutableMapOf<ObjectFilter, MenuItem>()
    private val filterControls = HorizontalLayout().apply {
        isPadding = false
        isSpacing = true
        style["gap"] = "6px"
        style["flex-wrap"] = "wrap"
        style["justify-content"] = "flex-end"
    }
    private val addToCollectionButton = Button("В коллекцию").apply {
        addClickListener { openAddToCollectionDialog() }
        element.setAttribute("title", "Добавить отфильтрованные объекты проекта в коллекцию")
    }
    private val showEmbeddingsButton = Button("Embeddings").apply {
        addClickListener { openEmbeddingsDialog() }
        element.setAttribute("title", "Показать график эмбеддингов для выбранных/отфильтрованных объектов")
    }
    private val collectionObjectActionsMenuBar = MenuBar()
    private val grainClassFilterToolbarMenuBar = MenuBar()
    private var availableGrainClassItems: List<String> = emptyList()
    private var grainClassColorsByClass: Map<String, String> = emptyMap()
    private val selectedGrainClasses = linkedSetOf<String>()
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
    private val areaFromFilter = NumberField().apply {
        placeholder = "Пл. от"
        min = 0.0
        step = 1.0
        setWidth("98px")
    }
    private val areaToFilter = NumberField().apply {
        placeholder = "до"
        min = 0.0
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
    private var showMasksOnCards: Boolean = false
    private var cardBackgroundMode: CardBackgroundMode = CardBackgroundMode.MASKED
    private val propertyEditor = Div()
    private val cardFieldsPanelTitle = H4("Поля карточек")
    private val cardFieldsMenuBar = MenuBar()
    private val cardFieldsPanel = Div()
    private val objectCardsById = mutableMapOf<String, Div>()
    private val selectedObjectIds = linkedSetOf<String>()
    private val cardVisibleFields = linkedSetOf("phase_area_shares")
    private val cardFieldOrder = mutableListOf<String>()
    private val jsonMapper = ObjectMapper()
    private var draggedCardField: String? = null
    private var selectedObjectCard: Div? = null
    private var visibleObjectLimit: Int = OBJECT_PAGE_SIZE
    private val maskEditorDialog = ObjectMaskEditorDialog()

    private val projectTab = Tab("Проекты")
    private val collectionTab = Tab("Коллекции")
    private val leftTabs = Tabs(projectTab, collectionTab)
    private val leftActions = HorizontalLayout().apply {
        isPadding = false
        isSpacing = true
    }
    private val createCollectionButton = Button(VaadinIcon.PLUS.create()).apply {
        addClickListener { openCreateCollectionDialog() }
        element.setAttribute("title", "Добавить коллекцию")
    }
    private val renameCollectionButton = Button(VaadinIcon.EDIT.create()).apply {
        addClickListener { openRenameCollectionDialog() }
        element.setAttribute("title", "Переименовать коллекцию")
    }
    private val deleteCollectionButton = Button(VaadinIcon.TRASH.create()).apply {
        addClickListener { deleteSelectedCollection() }
        element.setAttribute("title", "Удалить коллекцию")
    }
    private var leftPanelMode: LeftPanelMode = LeftPanelMode.PROJECTS

    private var selectedProject: DatasetProject? = null
    private var selectedCollection: DatasetCollection? = null
    private var selectedObject: DatasetObject? = null
    private var lastSelectedObjectId: String? = null

    init {
        setSizeFull()
        isPadding = true
        isSpacing = true

        add(H3("Минерал 26 AI"))

        configureProjectList()
        configureObjectGallery()
        configurePropertyEditor()
        configureLeftTabs()
        initFilterListeners()

        val leftPanel = panel(projectHeaderWithActions(), projectList)
        val centerPanel = panel(objectHeaderWithFilters(), objectGallery).apply {
            style["padding-left"] = "0"
        }
        val rightPanelBody = VerticalLayout(propertyEditor, cardFieldsPanel).apply {
            setSizeFull()
            isPadding = false
            isSpacing = true
            setFlexGrow(1.0, propertyEditor, cardFieldsPanel)
        }
        val rightPanel = panel(cardFieldsPanelTitle, rightPanelBody, bodyScrollable = false)

        // 20% | 60% | 20%: настраивается пользователем через drag splitters
        val centerRightSplit = SplitLayout(centerPanel, rightPanel).apply {
            setSizeFull()
            splitterPosition = 75.0 // 75% of remaining 80% = 60% of full width
        }
        val rootSplit = SplitLayout(leftPanel, centerRightSplit).apply {
            setSizeFull()
            splitterPosition = 20.0
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

    private fun configureLeftTabs() {
        leftTabs.setWidthFull()
        rebuildLeftPanelActions()
        leftTabs.addSelectedChangeListener { event ->
            leftPanelMode = if (event.selectedTab == collectionTab) LeftPanelMode.COLLECTIONS else LeftPanelMode.PROJECTS
            refreshCurrentSelection()
        }
    }

    private fun rebuildLeftPanelActions() {
        leftActions.removeAll()
        if (leftPanelMode == LeftPanelMode.PROJECTS) {
            leftActions.add(Button("Импорт").apply { addClickListener { openImportDialog() } })
        } else {
            listOf(createCollectionButton, renameCollectionButton, deleteCollectionButton).forEach { button ->
                button.style["padding"] = "0"
                button.style["min-width"] = "var(--lumo-size-m)"
            }
            leftActions.add(createCollectionButton, renameCollectionButton, deleteCollectionButton)
        }
        updateCollectionActionState()
    }

    private fun updateCollectionActionState() {
        val canEditCollection = leftPanelMode == LeftPanelMode.COLLECTIONS && selectedCollection != null
        renameCollectionButton.isEnabled = canEditCollection
        deleteCollectionButton.isEnabled = canEditCollection
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

        cardFieldsPanel.style["display"] = "flex"
        cardFieldsPanel.style["flex-direction"] = "column"
        cardFieldsPanel.style["overflow"] = "auto"
        cardFieldsPanel.setSizeFull()
    }

    private fun selectProject(project: DatasetProject) {
        selectedProject = project
        leftTabs.selectedTab = projectTab
        leftPanelMode = LeftPanelMode.PROJECTS
        refreshCurrentSelection()
    }

    private fun selectCollection(collection: DatasetCollection) {
        selectedCollection = collection
        leftTabs.selectedTab = collectionTab
        leftPanelMode = LeftPanelMode.COLLECTIONS
        refreshCurrentSelection()
    }

    private fun refreshCurrentSelection() {
        selectedObject = null
        selectedObjectCard = null
        selectedObjectIds.clear()
        lastSelectedObjectId = null
        addToCollectionButton.isVisible = leftPanelMode == LeftPanelMode.PROJECTS
        collectionObjectActionsMenuBar.isVisible = leftPanelMode == LeftPanelMode.COLLECTIONS
        rebuildLeftPanelActions()
        renderProjects()
        refreshFilterOptions(activeObjects())
        refreshObjectGallery(resetPaging = true)
        rebuildCardFieldsMenu(cardFieldsMenuBar)
        updateRightPanel()
    }

    private fun selectObject(obj: DatasetObject) {
        selectedObjectIds.clear()
        selectedObjectIds.add(obj.id)
        lastSelectedObjectId = obj.id
        syncSelectedObjectState()
        updateObjectSelection()
        updateRightPanel()
    }

    private fun clearSelectedObject() {
        selectedObjectIds.clear()
        selectedObject = null
        selectedObjectCard = null
        lastSelectedObjectId = null
        updateObjectSelection()
        updateRightPanel()
    }

    private fun activeObjects(): List<DatasetObject> = when (leftPanelMode) {
        LeftPanelMode.PROJECTS -> selectedProject?.objects.orEmpty()
        LeftPanelMode.COLLECTIONS -> selectedCollection?.objects.orEmpty()
    }

    private fun activeSelectionTitle(): String = when (leftPanelMode) {
        LeftPanelMode.PROJECTS -> selectedProject?.name.orEmpty()
        LeftPanelMode.COLLECTIONS -> selectedCollection?.name.orEmpty()
    }

    private fun renderObjects(objects: List<DatasetObject>) {
        objectGallery.removeAll()
        objectCardsById.clear()

        if (objects.isEmpty()) {
            objectGallery.add(Paragraph("Нет объектов в выбранном наборе."))
            return
        }

        val visibleObjects = objects.take(visibleObjectLimit)
        val visibleCardFields = orderedVisibleCardFields()
        val grainClassColors = grainClassColorMapForCurrentDataset()
        visibleObjects.forEach { obj ->
            val card = objectCard(obj, obj.id in selectedObjectIds, visibleCardFields, grainClassColors) { clickEvent ->
                handleObjectCardClick(obj, objects, clickEvent.isCtrlKey, clickEvent.isShiftKey)
            }
            objectCardsById[obj.id] = card
            if (obj.id in selectedObjectIds) {
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
                Div(showMoreButton).apply {
                    style["width"] = "100%"
                    style["padding"] = "8px 4px"
                }
            )
        }
    }

    private fun handleObjectCardClick(obj: DatasetObject, visibleObjects: List<DatasetObject>, ctrlKey: Boolean, shiftKey: Boolean) {
        val updated = selectedObjectIds.toMutableSet()
        if (shiftKey && lastSelectedObjectId != null) {
            val start = visibleObjects.indexOfFirst { it.id == lastSelectedObjectId }
            val end = visibleObjects.indexOfFirst { it.id == obj.id }
            if (start >= 0 && end >= 0) {
                val range = if (start <= end) visibleObjects.subList(start, end + 1) else visibleObjects.subList(end, start + 1)
                if (!ctrlKey) updated.clear()
                updated.addAll(range.map { it.id })
            } else {
                if (!ctrlKey) updated.clear()
                updated.add(obj.id)
            }
        } else if (ctrlKey) {
            if (!updated.add(obj.id)) updated.remove(obj.id)
            lastSelectedObjectId = obj.id
        } else {
            if (updated.size == 1 && obj.id in updated) {
                updated.clear()
                lastSelectedObjectId = null
            } else {
                updated.clear()
                updated.add(obj.id)
                lastSelectedObjectId = obj.id
            }
        }
        selectedObjectIds.clear()
        selectedObjectIds.addAll(updated)
        syncSelectedObjectState()
        updateObjectSelection()
        updateRightPanel()
    }

    private fun syncSelectedObjectState() {
        selectedObject = if (selectedObjectIds.size == 1) {
            activeObjects().firstOrNull { it.id == selectedObjectIds.first() }
        } else {
            null
        }
    }

    private fun updateObjectSelection() {
        objectCardsById.forEach { (id, card) ->
            styleObjectSelection(id in selectedObjectIds, card.style)
        }
        selectedObjectCard = selectedObjectIds.firstOrNull()?.let { objectCardsById[it] }
    }

    private fun renderProjects() {
        projectHeader.text = if (leftPanelMode == LeftPanelMode.PROJECTS) {
            "Проекты (${projects.size})"
        } else {
            "Коллекции (${collections.size})"
        }
        projectList.removeAll()
        if (leftPanelMode == LeftPanelMode.COLLECTIONS) {
            collections.forEach { collection ->
                projectList.add(collectionCard(collection, collection == selectedCollection) { selectCollection(collection) })
            }
            return
        }
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

    private fun openCreateCollectionDialog() {
        val nameField = TextField("Название коллекции").apply {
            isClearButtonVisible = true
            setWidthFull()
        }
        val dialog = Dialog().apply {
            headerTitle = "Новая коллекция"
            add(VerticalLayout(nameField).apply {
                isPadding = false
                isSpacing = true
            })
        }
        val createButton = Button("Создать") {
            val rawName = nameField.value.trim()
            if (rawName.isBlank()) {
                showError("Имя коллекции не может быть пустым.")
                return@Button
            }
            if (collections.any { it.name.equals(rawName, ignoreCase = true) }) {
                showError("Коллекция с именем \"$rawName\" уже существует.")
                return@Button
            }
            val collection = DatasetCollection(
                id = "collection-${System.currentTimeMillis()}",
                name = rawName,
                objects = mutableListOf(),
                classColors = mutableMapOf()
            )
            collections.add(0, collection)
            dialog.close()
            selectCollection(collection)
        }
        dialog.footer.add(Button("Отмена") { dialog.close() }, createButton)
        dialog.open()
    }

    private fun openRenameCollectionDialog() {
        val collection = selectedCollection
        if (collection == null) {
            showError("Выберите коллекцию для переименования.")
            return
        }
        val nameField = TextField("Название коллекции").apply {
            value = collection.name
            setWidthFull()
            isClearButtonVisible = true
        }
        val dialog = Dialog().apply {
            headerTitle = "Переименовать коллекцию"
            add(nameField)
        }
        val saveButton = Button("Сохранить") {
            val newName = nameField.value.trim()
            if (newName.isBlank()) {
                showError("Имя коллекции не может быть пустым.")
                return@Button
            }
            if (collections.any { it.id != collection.id && it.name.equals(newName, ignoreCase = true) }) {
                showError("Коллекция с именем \"$newName\" уже существует.")
                return@Button
            }
            collection.name = newName
            dialog.close()
            renderProjects()
            refreshObjectGallery(resetPaging = false)
        }
        dialog.footer.add(Button("Отмена") { dialog.close() }, saveButton)
        dialog.open()
    }

    private fun deleteSelectedCollection() {
        val collection = selectedCollection
        if (collection == null) {
            showError("Выберите коллекцию для удаления.")
            return
        }
        collections.removeIf { it.id == collection.id }
        selectedCollection = collections.firstOrNull()
        refreshCurrentSelection()
    }

    private fun openAddToCollectionDialog() {
        val project = selectedProject
        if (project == null) {
            showError("Сначала выберите проект.")
            return
        }
        val filteredObjects = applyFilters(project.objects)
        val selectedCount = selectedObjectsIn(filteredObjects).size
        val objectsForAdd = objectsForAction(filteredObjects)
        if (objectsForAdd.isEmpty()) {
            showError("Нет объектов, подходящих под текущие фильтры.")
            return
        }
        val hasCollections = collections.isNotEmpty()
        val collectionPicker = ComboBox<DatasetCollection>("Коллекция").apply {
            setItems(collections)
            setItemLabelGenerator { it.name }
            if (hasCollections) {
                value = selectedCollection ?: collections.first()
            }
            setWidthFull()
            isVisible = hasCollections
        }
        val autoCollectionHint = Paragraph("Коллекция будет создана автоматически после подтверждения: \"${generateNextCollectionName()}\".").apply {
            isVisible = !hasCollections
        }
        val dialog = Dialog().apply {
            headerTitle = "Добавить в коллекцию"
            add(
                VerticalLayout(
                    H5(actionScopeMessage(selectedCount, filteredObjects.size, project.objects.size, "проекте \"${project.name}\"")),
                    Paragraph("Будут добавлены ${objectsForAdd.size} объектов из проекта \"${project.name}\"."),
                    collectionPicker,
                    autoCollectionHint
                ).apply {
                    isPadding = false
                    isSpacing = true
                }
            )
        }
        val addButton = Button("Добавить") {
            val targetCollection = collectionPicker.value ?: DatasetCollection(
                id = "collection-${System.currentTimeMillis()}",
                name = generateNextCollectionName(),
                objects = mutableListOf(),
                classColors = mutableMapOf()
            )
            if (hasCollections && collectionPicker.value == null) {
                showError("Выберите коллекцию.")
                return@Button
            }
            val sourceProjectClassColors = classColorMap(project.objects)
            val sourceClassToColor = classNamesInObjects(objectsForAdd)
                .mapNotNull { className ->
                    val color = sourceProjectClassColors[className] ?: return@mapNotNull null
                    className to color
                }
                .toMap()
            val targetClassToColor = targetCollection.classColors + classColorMap(targetCollection.objects)
            val conflicts = detectClassColorConflicts(sourceClassToColor, targetClassToColor)

            fun executeMerge(resolvedSourceColors: Map<String, String>) {
                val mergeResult = mergeProjectObjectsIntoCollection(project, targetCollection, objectsForAdd, resolvedSourceColors)
                if (!mergeResult.success) {
                    showError(mergeResult.message)
                    return
                }
                if (targetCollection.id !in collections.map { it.id }) {
                    collections.add(0, targetCollection)
                    selectedCollection = targetCollection
                }
                dialog.close()
                Notification.show(mergeResult.message, 3000, Notification.Position.BOTTOM_START)
                if (leftPanelMode == LeftPanelMode.COLLECTIONS && selectedCollection?.id == targetCollection.id) {
                    refreshCurrentSelection()
                } else {
                    renderProjects()
                }
            }

            if (conflicts.isEmpty()) {
                executeMerge(sourceClassToColor)
            } else {
                openConflictResolutionDialog(
                    conflicts = conflicts,
                    sourceTitle = "проекта \"${project.name}\"",
                    targetTitle = "коллекции \"${targetCollection.name}\""
                ) { resolvedOverrides ->
                    val resolvedColors = sourceClassToColor.toMutableMap().apply { putAll(resolvedOverrides) }
                    executeMerge(resolvedColors)
                }
            }
        }
        dialog.footer.add(Button("Отмена") { dialog.close() }, addButton)
        dialog.open()
    }

    private fun openEmbeddingsDialog() {
        val filteredObjects = when (leftPanelMode) {
            LeftPanelMode.PROJECTS -> selectedProject?.let { applyFilters(it.objects) }.orEmpty()
            LeftPanelMode.COLLECTIONS -> selectedCollection?.let { applyFilters(it.objects) }.orEmpty()
        }
        val objectsForPlot = objectsForAction(filteredObjects)
        if (objectsForPlot.isEmpty()) {
            showError("Нет объектов для визуализации.")
            return
        }
        val withEmbeddings = objectsForPlot.filter { it.embeddings.isNotEmpty() }
        if (withEmbeddings.isEmpty()) {
            showError("У выбранных объектов нет embeddings.")
            return
        }
        val embeddingColumnNames = withEmbeddings
            .map { it.embeddingColumnNames }
            .maxByOrNull { it.size }
            .orEmpty()
        val allValues = withEmbeddings.asSequence().flatMap { it.embeddings.asSequence() }.toList()
        val minValue = allValues.minOrNull() ?: 0.0
        val maxValue = allValues.maxOrNull() ?: 0.0
        val denominator = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
        val chartHeight = 300

        fun normalizedY(value: Double): Int {
            val normalized = ((value - minValue) / denominator).coerceIn(0.0, 1.0)
            return (chartHeight - (normalized * (chartHeight - 1))).roundToInt().coerceIn(0, chartHeight - 1)
        }
        val chartBytes = renderEmbeddingsPlotPng(
            objects = withEmbeddings,
            embeddingColumnNames = embeddingColumnNames,
            normalizedY = ::normalizedY
        )
        val chartResource = StreamResource("embeddings-${System.currentTimeMillis()}.png") {
            ByteArrayInputStream(chartBytes)
        }
        val phaseCounts = withEmbeddings
            .groupingBy { obj -> obj.properties["grain_class"]?.trim().orEmpty().ifBlank { obj.name } }
            .eachCount()
            .toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        val phaseColorByName = withEmbeddings
            .asSequence()
            .mapNotNull { obj ->
                val phaseName = obj.properties["grain_class"]?.trim().orEmpty().ifBlank { obj.name }
                val color = normalizeMaskColor(obj.properties["mask_color_rgb"]).orEmpty().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                phaseName to color
            }
            .distinct()
            .toMap()
        val legend = VerticalLayout().apply {
            isPadding = false
            isSpacing = false
            style["gap"] = "4px"
            add(H5("Распределение по фазам"))
            phaseCounts.forEach { (phaseName, count) ->
                add(
                    HorizontalLayout(
                        colorDot(phaseColorByName[phaseName]),
                        Span("$phaseName — $count")
                    ).apply {
                        isPadding = false
                        isSpacing = true
                        alignItems = FlexComponent.Alignment.CENTER
                    }
                )
            }
        }
        val dialog = Dialog().apply {
            headerTitle = "Embeddings (${withEmbeddings.size} объектов)"
            width = "min(95vw, 1200px)"
            height = "min(90vh, 760px)"
            add(
                VerticalLayout(
                    Paragraph("Нормализация по текущей выборке: min=${"%.6f".format(Locale.US, minValue)}, max=${"%.6f".format(Locale.US, maxValue)}."),
                    Div().apply {
                        style["overflow"] = "auto"
                        style["border"] = "1px solid var(--lumo-contrast-20pct)"
                        style["border-radius"] = "8px"
                        style["padding"] = "8px"
                        style["background"] = "white"
                        setWidthFull()
                        val chartImage = Image(chartResource, "Embeddings").apply {
                            setWidthFull()
                            height = "${chartHeight}px"
                            style["display"] = "block"
                            style["cursor"] = "zoom-in"
                        }
                        val chartLink = Anchor(chartResource, "").apply {
                            setTarget("_blank")
                            element.setAttribute("title", "Открыть график в новой вкладке")
                            style["display"] = "block"
                            setWidthFull()
                            add(chartImage)
                        }
                        add(
                            chartLink
                        )
                    },
                    legend
                ).apply {
                    isPadding = false
                    isSpacing = true
                    setWidthFull()
                }
            )
        }
        dialog.footer.add(Button("Закрыть") { dialog.close() })
        dialog.open()
    }

    private fun renderEmbeddingsPlotPng(
        objects: List<DatasetObject>,
        embeddingColumnNames: List<String>,
        normalizedY: (Double) -> Int
    ): ByteArray {
        val plotHeight = 300
        val font = Font("SansSerif", Font.PLAIN, 14)
        val labelImage = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val labelGraphics = labelImage.createGraphics()
        labelGraphics.font = font
        val maxLabelWidth = embeddingColumnNames.maxOfOrNull { labelGraphics.fontMetrics.stringWidth(it) } ?: 0
        labelGraphics.dispose()

        val columnWidth = (maxLabelWidth / 8).coerceIn(3, 10)
        val valueCount = maxOf(embeddingColumnNames.size, objects.maxOfOrNull { it.embeddings.size } ?: 0)
        val leftPadding = 2
        val rightPadding = 2
        val chessShift = (maxLabelWidth - columnWidth).coerceAtLeast(font.size * 2).coerceAtMost(maxLabelWidth + font.size)
        val bottomPadding = (maxLabelWidth + chessShift + 4).coerceAtLeast(18)
        val width = (leftPadding + rightPadding + (valueCount.coerceAtLeast(1) * columnWidth)).coerceAtLeast(1)
        val height = plotHeight + bottomPadding
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.color = Color(208, 208, 208)
            graphics.drawLine(leftPadding, plotHeight - 1, width - rightPadding, plotHeight - 1)
            graphics.drawLine(leftPadding, 0, leftPadding, plotHeight)

            val densityAlpha = (1.6f / objects.size.coerceAtLeast(1)).coerceIn(0.12f, 0.85f)
            objects.forEach { obj ->
                graphics.color = colorForPhase(obj.properties["mask_color_rgb"])
                graphics.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, densityAlpha)
                val pointSize = columnWidth.coerceAtLeast(2)
                obj.embeddings.forEachIndexed { pointIndex, value ->
                    val x = (leftPadding + pointIndex * columnWidth + columnWidth / 2).coerceIn(0, width - 1)
                    val y = normalizedY(value)
                    graphics.fillRect(
                        (x - pointSize / 2).coerceAtLeast(0),
                        (y - pointSize / 2).coerceAtLeast(0),
                        pointSize,
                        pointSize
                    )
                }
            }
            graphics.composite = AlphaComposite.SrcOver
            graphics.color = Color(90, 90, 90)
            graphics.font = font
            embeddingColumnNames.forEachIndexed { index, rawLabel ->
                val x = leftPadding + index * columnWidth + columnWidth / 2
                val y = plotHeight + maxLabelWidth + 2 + if (index % 2 == 0) 0 else chessShift
                graphics.color = Color(190, 190, 190)
                graphics.drawLine(x, plotHeight - 1, x, y - maxLabelWidth - 2)
                graphics.color = Color(90, 90, 90)
                val originalTransform = graphics.transform
                graphics.rotate(-Math.PI / 2, x.toDouble(), y.toDouble())
                graphics.drawString(rawLabel, x.toFloat(), y.toFloat())
                graphics.transform = originalTransform
            }
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }
    }

    private fun colorForPhase(rawMaskColor: String?): Color {
        val normalized = normalizeMaskColor(rawMaskColor)?.removePrefix("0x") ?: return Color(58, 115, 193)
        val rgb = normalized.toIntOrNull(16) ?: return Color(58, 115, 193)
        return Color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    }

    private fun generateNextCollectionName(): String {
        val prefix = "Новая "
        val used = collections.mapNotNull { item ->
            item.name.removePrefix(prefix).trim().toIntOrNull()
        }.toSet()
        val next = generateSequence(1) { it + 1 }.first { it !in used }
        return "$prefix$next"
    }

    private fun objectsForAction(filteredObjects: List<DatasetObject>): List<DatasetObject> {
        val selectedObjects = selectedObjectsIn(filteredObjects)
        return if (selectedObjects.isNotEmpty()) selectedObjects else filteredObjects
    }

    private fun selectedObjectsIn(filteredObjects: List<DatasetObject>): List<DatasetObject> =
        filteredObjects.filter { it.id in selectedObjectIds }

    private fun actionScopeMessage(
        selectedCount: Int,
        filteredCount: Int,
        totalCount: Int,
        sourceLabel: String
    ): String = when {
        selectedCount > 0 -> "Действие применится к выбранным объектам: $selectedCount."
        filteredCount < totalCount -> "⚠️ Действие применится ко всем объектам по текущему фильтру в $sourceLabel: $filteredCount из $totalCount."
        else -> "⚠️ Действие применится ко всем объектам в $sourceLabel: $totalCount."
    }

    private fun openCollectionTransferDialog(move: Boolean) {
        val sourceCollection = selectedCollection ?: run {
            showError("Выберите коллекцию.")
            return
        }
        val filteredObjects = applyFilters(sourceCollection.objects)
        val selectedCount = selectedObjectsIn(filteredObjects).size
        val objectsForTransfer = objectsForAction(filteredObjects)
        if (objectsForTransfer.isEmpty()) {
            showError("Нет объектов для операции.")
            return
        }
        val targetCollections = collections.filter { it.id != sourceCollection.id }
        if (targetCollections.isEmpty()) {
            showError("Нет другой коллекции для ${if (move) "переноса" else "копирования"}.")
            return
        }

        val targetPicker = ComboBox<DatasetCollection>("Коллекция назначения").apply {
            setItems(targetCollections)
            setItemLabelGenerator { it.name }
            value = targetCollections.first()
            setWidthFull()
        }
        val dialog = Dialog().apply {
            headerTitle = if (move) "Переместить объекты" else "Копировать объекты"
            add(
                VerticalLayout(
                    H5(actionScopeMessage(selectedCount, filteredObjects.size, sourceCollection.objects.size, "коллекции \"${sourceCollection.name}\"")),
                    Paragraph("Будет обработано ${objectsForTransfer.size} объектов."),
                    targetPicker
                ).apply {
                    isPadding = false
                    isSpacing = true
                }
            )
        }
        val confirmButton = Button(if (move) "Подтвердить перенос" else "Подтвердить копирование") {
            val targetCollection = targetPicker.value ?: return@Button
            executeCollectionTransfer(sourceCollection, targetCollection, objectsForTransfer, move)
            dialog.close()
        }
        dialog.footer.add(Button("Отмена") { dialog.close() }, confirmButton)
        dialog.open()
    }

    private fun executeCollectionTransfer(
        sourceCollection: DatasetCollection,
        targetCollection: DatasetCollection,
        objectsForTransfer: List<DatasetObject>,
        move: Boolean
    ) {
        val sourceClassColors = (sourceCollection.classColors + classColorMap(sourceCollection.objects))
        val transferClassColors = classNamesInObjects(objectsForTransfer)
            .mapNotNull { className ->
                val color = sourceClassColors[className] ?: return@mapNotNull null
                className to color
            }
            .toMap()
        val targetClassToColor = targetCollection.classColors + classColorMap(targetCollection.objects)
        val conflicts = detectClassColorConflicts(transferClassColors, targetClassToColor)

        fun runTransfer(resolvedColors: Map<String, String>) {
            val sourceAsProject = DatasetProject(
                id = sourceCollection.id,
                name = sourceCollection.name,
                batch = "",
                type = "Коллекция",
                imageCount = 0,
                source = sourceCollection.name,
                previewUrl = "",
                objects = sourceCollection.objects
            )
            val result = mergeProjectObjectsIntoCollection(sourceAsProject, targetCollection, objectsForTransfer, resolvedColors)
            if (move) {
                val movedIds = objectsForTransfer.map { it.id }.toSet()
                sourceCollection.objects.removeIf { it.id in movedIds }
                selectedObjectIds.removeAll(movedIds)
                syncSelectedObjectState()
            }
            refreshCurrentSelection()
            Notification.show(result.message, 3500, Notification.Position.BOTTOM_START)
        }

        if (conflicts.isEmpty()) {
            runTransfer(transferClassColors)
        } else {
            openConflictResolutionDialog(
                conflicts = conflicts,
                sourceTitle = "коллекции \"${sourceCollection.name}\"",
                targetTitle = "коллекции \"${targetCollection.name}\""
            ) { resolved ->
                val resolvedColors = transferClassColors.toMutableMap().apply { putAll(resolved) }
                runTransfer(resolvedColors)
            }
        }
    }

    private fun openDeleteCollectionObjectsDialog() {
        val sourceCollection = selectedCollection ?: run {
            showError("Выберите коллекцию.")
            return
        }
        val filteredObjects = applyFilters(sourceCollection.objects)
        val selectedCount = selectedObjectsIn(filteredObjects).size
        val objectsForDelete = objectsForAction(filteredObjects)
        if (objectsForDelete.isEmpty()) {
            showError("Нет объектов для удаления.")
            return
        }
        val dialog = Dialog().apply {
            headerTitle = "Удалить объекты"
            add(
                VerticalLayout(
                    H5(actionScopeMessage(selectedCount, filteredObjects.size, sourceCollection.objects.size, "коллекции \"${sourceCollection.name}\"")),
                    Paragraph("Удалить ${objectsForDelete.size} объектов из коллекции \"${sourceCollection.name}\"?")
                ).apply {
                    isPadding = false
                    isSpacing = true
                }
            )
        }
        val confirmButton = Button("Подтвердить удаление") {
            val idsToDelete = objectsForDelete.map { it.id }.toSet()
            sourceCollection.objects.removeIf { it.id in idsToDelete }
            selectedObjectIds.removeAll(idsToDelete)
            syncSelectedObjectState()
            dialog.close()
            refreshCurrentSelection()
            Notification.show("Удалено ${idsToDelete.size} объектов.", 3000, Notification.Position.BOTTOM_START)
        }
        dialog.footer.add(Button("Отмена") { dialog.close() }, confirmButton)
        dialog.open()
    }

    private fun detectClassColorConflicts(
        sourceClassToColor: Map<String, String>,
        targetClassToColor: Map<String, String>
    ): List<ClassColorConflict> =
        sourceClassToColor.mapNotNull { (grainClass, sourceColor) ->
            val targetColor = targetClassToColor[grainClass] ?: return@mapNotNull null
            if (targetColor == sourceColor) return@mapNotNull null
            ClassColorConflict(grainClass, sourceColor, targetColor)
        }

    private fun openConflictResolutionDialog(
        conflicts: List<ClassColorConflict>,
        sourceTitle: String,
        targetTitle: String,
        onResolved: (Map<String, String>) -> Unit
    ) {
        val optionLabels = mapOf(
            ConflictResolutionOption.KEEP_TARGET to "Использовать цвет $targetTitle",
            ConflictResolutionOption.KEEP_SOURCE to "Использовать цвет $sourceTitle"
        )
        val optionByClass = mutableMapOf<String, ComboBox<ConflictResolutionOption>>()

        val dialog = Dialog().apply {
            headerTitle = "Конфликт цветов классов"
        }
        val content = VerticalLayout().apply {
            isPadding = false
            isSpacing = true
        }
        conflicts.forEach { conflict ->
            fun optionColor(option: ConflictResolutionOption?): String =
                when (option) {
                    ConflictResolutionOption.KEEP_SOURCE -> conflict.sourceColor
                    else -> conflict.targetColor
                }

            val options = ComboBox<ConflictResolutionOption>("Решение").apply {
                setItems(
                    ConflictResolutionOption.KEEP_TARGET,
                    ConflictResolutionOption.KEEP_SOURCE
                )
                setItemLabelGenerator { optionLabels[it].orEmpty() }
                value = ConflictResolutionOption.KEEP_TARGET
                isClearButtonVisible = false
                setWidthFull()
                setRenderer(
                    ComponentRenderer { option ->
                        HorizontalLayout(colorDot(optionColor(option)), Span(optionLabels[option].orEmpty())).apply {
                            isPadding = false
                            isSpacing = true
                            alignItems = FlexComponent.Alignment.CENTER
                            style["gap"] = "8px"
                        }
                    }
                )
                prefixComponent = colorDot(optionColor(value))
                addValueChangeListener { event ->
                    prefixComponent = colorDot(optionColor(event.value))
                }
            }
            optionByClass[conflict.grainClass] = options
            val colorMeta = HorizontalLayout(
                Span("Цвет в $targetTitle:"),
                colorDot(conflict.targetColor),
                Span("Цвет в $sourceTitle:"),
                colorDot(conflict.sourceColor)
            ).apply {
                isPadding = false
                isSpacing = true
                alignItems = FlexComponent.Alignment.CENTER
                style["gap"] = "8px"
            }
            content.add(
                Div(
                    H5("Класс: ${conflict.grainClass}"),
                    colorMeta,
                    options
                ).apply {
                    style["padding"] = "10px"
                    style["border"] = "1px solid var(--lumo-contrast-20pct)"
                    style["border-radius"] = "10px"
                }
            )
        }
        dialog.add(content)
        val applyButton = Button("Применить") {
            val resolved = mutableMapOf<String, String>()
            conflicts.forEach { conflict ->
                val selectedOption = optionByClass[conflict.grainClass]?.value ?: ConflictResolutionOption.KEEP_TARGET
                resolved[conflict.grainClass] = when (selectedOption) {
                    ConflictResolutionOption.KEEP_TARGET -> conflict.targetColor
                    ConflictResolutionOption.KEEP_SOURCE -> conflict.sourceColor
                }
            }
            dialog.close()
            onResolved(resolved)
        }
        dialog.footer.add(Button("Отмена") { dialog.close() }, applyButton)
        dialog.open()
    }

    private fun objectHeaderWithFilters(): Component =
        HorizontalLayout(
            objectHeader,
            HorizontalLayout(
                addToCollectionButton,
                showEmbeddingsButton,
                collectionActionsMenu(),
                cardFieldsMenu(),
                filterAddMenu(),
                grainClassToolbarMenu(),
                filterControls,
                Button(VaadinIcon.REFRESH.create()) {
                    clearFilters()
                    refreshObjectGallery(resetPaging = true)
                }.apply {
                    element.setAttribute("title", "Сбросить фильтры")
                    style["padding"] = "0"
                    style["min-width"] = "var(--lumo-size-m)"
                    style["background"] = "transparent"
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
            alignItems = FlexComponent.Alignment.CENTER
            justifyContentMode = FlexComponent.JustifyContentMode.BETWEEN
            isPadding = false
            isSpacing = true
            style["padding-left"] = "12px"
        }

    private fun projectHeaderWithActions(): Component =
        VerticalLayout(
            leftTabs,
            HorizontalLayout(
                projectHeader,
                leftActions
            ).apply {
                setWidthFull()
                alignItems = FlexComponent.Alignment.CENTER
                justifyContentMode = FlexComponent.JustifyContentMode.BETWEEN
                isPadding = false
                isSpacing = true
            }
        ).apply {
            setWidthFull()
            isPadding = false
            isSpacing = true
        }

    private fun collectionActionsMenu(): MenuBar =
        collectionObjectActionsMenuBar.apply {
            removeAll()
            val root = addIconItem(VaadinIcon.SHARE.create())
            root.element.setProperty("title", "Действия с объектами коллекции")
            root.subMenu.addItem(menuItemWithIcon(VaadinIcon.COPY_O, "Копировать в другую коллекцию…")) { openCollectionTransferDialog(move = false) }
            root.subMenu.addItem(menuItemWithIcon(VaadinIcon.ARROW_FORWARD, "Переместить в другую коллекцию…")) { openCollectionTransferDialog(move = true) }
            root.subMenu.addItem(menuItemWithIcon(VaadinIcon.TRASH, "Удалить объекты…")) { openDeleteCollectionObjectsDialog() }
            isVisible = leftPanelMode == LeftPanelMode.COLLECTIONS
            styleToolbarMenu(this, root)
        }

    private fun menuItemWithIcon(icon: VaadinIcon, text: String): Component =
        HorizontalLayout(icon.create(), Span(text)).apply {
            isPadding = false
            isSpacing = true
            alignItems = FlexComponent.Alignment.CENTER
            style["gap"] = "8px"
        }

    private fun filterAddMenu(): MenuBar =
        filterAddMenuBar.apply {
            rebuildFilterAddMenu()
        }

    private fun grainClassToolbarMenu(): MenuBar =
        grainClassFilterToolbarMenuBar.apply {
            rebuildGrainClassToolbarMenu()
        }

    private fun rebuildGrainClassToolbarMenu() {
        grainClassFilterToolbarMenuBar.removeAll()
        val selected = selectedGrainClasses.toList().sorted()
        if (selected.isEmpty()) {
            grainClassFilterToolbarMenuBar.isVisible = false
            return
        }
        grainClassFilterToolbarMenuBar.isVisible = true
        grainClassFilterToolbarMenuBar.style.set("--vaadin-button-min-width", "0")
        val rootContent = HorizontalLayout().apply {
            isPadding = false
            isSpacing = false
            alignItems = FlexComponent.Alignment.CENTER
            style["display"] = "inline-flex"
            style["gap"] = "4px"
            style["width"] = "auto"
            style["min-width"] = "auto"
            style["max-width"] = "none"
            style["height"] = "14px"
            style["padding"] = "0 16px"
            style["white-space"] = "nowrap"
            style["overflow"] = "visible"
            selected.forEach { grainClass -> add(toolbarColorDot(grainClassColorsByClass[grainClass])) }
        }

        val root = grainClassFilterToolbarMenuBar.addItem(rootContent)
        root.element.setProperty("title", "Фильтр по классам")
        root.element.style["padding"] = "0"
        root.element.style["min-width"] = "auto"
        root.element.style["width"] = "auto"
        root.element.style["height"] = "28px"
        root.element.style["overflow"] = "visible"
        populateGrainClassMenu(root.subMenu)
        styleToolbarMenu(grainClassFilterToolbarMenuBar, root)
    }

    private fun rebuildFilterAddMenu() {
        filterAddMenuBar.removeAll()
        filterMenuItems.clear()

        val root = filterAddMenuBar.addIconItem(VaadinIcon.FILTER.create())
        val grainClassRoot = root.subMenu.addItem("Grain class")
        populateGrainClassMenu(grainClassRoot.subMenu)

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
        root.subMenu.addItem("Площадь") { addFilter(ObjectFilter.AREA) }.also {
            filterMenuItems[ObjectFilter.AREA] = it
        }
        root.element.setProperty("title", "Фильтры")
        styleToolbarMenu(filterAddMenuBar, root)
    }

    private fun applyGrainClassQuickFilter(grainClass: String) {
        activeFilters.add(ObjectFilter.GRAIN_CLASS)
        val selected = selectedGrainClasses.toMutableSet()
        if (grainClass in selected) selected.remove(grainClass) else selected.add(grainClass)
        setGrainClassFilterSelection(selected)
        rebuildVisibleFilterControls()
    }

    private fun populateGrainClassMenu(menu: SubMenu) {
        val multiphaseCombos = multiphasePhaseCombinationsForCurrentDataset()
        if (availableGrainClassItems.isEmpty() && multiphaseCombos.isEmpty()) {
            menu.addItem("Нет классов").apply { isEnabled = false }
            return
        }

        if (MULTIPHASE_CLASS_NAME in availableGrainClassItems || multiphaseCombos.isNotEmpty()) {
            val multiphaseSelected = MULTIPHASE_CLASS_NAME in selectedGrainClasses
            val multiphaseRoot = menu.addItem(
                HorizontalLayout(
                    multiphaseCompositeIndicator(),
                    Span(MULTIPHASE_CLASS_NAME)
                ).apply {
                    isPadding = false
                    isSpacing = true
                    alignItems = FlexComponent.Alignment.CENTER
                    style["gap"] = "8px"
                }
            )
            multiphaseRoot.subMenu.addItem(grainClassOptionView("Любой Многофазный", grainClassColorsByClass[MULTIPHASE_CLASS_NAME])) {
                applyGrainClassQuickFilter(MULTIPHASE_CLASS_NAME)
                rebuildFilterAddMenu()
                rebuildGrainClassToolbarMenu()
            }.apply {
                isCheckable = true
                isChecked = MULTIPHASE_CLASS_NAME in selectedGrainClasses
            }
            if (multiphaseCombos.isNotEmpty()) {
                multiphaseRoot.subMenu.addSeparator()
            }
            multiphaseCombos.forEach { phases ->
                val label = phases.joinToString(" + ")
                multiphaseRoot.subMenu.addItem(phaseCombinationOptionView(phases)) {
                    activeFilters.add(ObjectFilter.GRAIN_CLASS)
                    val groupedSelection = linkedSetOf(MULTIPHASE_CLASS_NAME).apply { addAll(phases) }
                    val nextSelection =
                        if (selectedGrainClasses == groupedSelection) {
                            linkedSetOf(MULTIPHASE_CLASS_NAME)
                        } else {
                            groupedSelection
                        }
                    setGrainClassFilterSelection(nextSelection)
                    rebuildFilterAddMenu()
                    rebuildGrainClassToolbarMenu()
                }.apply {
                    isCheckable = true
                    isChecked = selectedGrainClasses == (linkedSetOf(MULTIPHASE_CLASS_NAME).apply { addAll(phases) })
                    element.setProperty("title", label)
                }
            }
        }

        availableGrainClassItems.filter { it != MULTIPHASE_CLASS_NAME }.forEach { grainClass ->
            menu.addItem(grainClassOptionView(grainClass, grainClassColorsByClass[grainClass])) {
                applyGrainClassQuickFilter(grainClass)
                rebuildFilterAddMenu()
                rebuildGrainClassToolbarMenu()
            }.apply {
                isCheckable = true
                isChecked = grainClass in selectedGrainClasses
            }
        }
    }

    private fun multiphasePhaseCombinationsForCurrentDataset(): List<List<String>> =
        activeObjects()
            .asSequence()
            .filter { isMultiphaseObject(it) }
            .mapNotNull { obj ->
                val rawJson = obj.properties["phase_area_shares"].orEmpty().trim()
                if (rawJson.isBlank()) return@mapNotNull null
                val root = runCatching { jsonMapper.readTree(rawJson) }.getOrNull() ?: return@mapNotNull null
                if (!root.isObject) return@mapNotNull null
                root.properties()
                    .asSequence()
                    .map { it.key.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
                    .sorted()
                    .takeIf { it.isNotEmpty() }
            }
            .distinct()
            .sortedBy { it.joinToString(" + ") }
            .toList()

    private fun phaseCombinationOptionView(phases: List<String>): Component =
        HorizontalLayout(
            HorizontalLayout().apply {
                isPadding = false
                isSpacing = false
                style["gap"] = "4px"
                phases.forEach { phase -> add(colorDot(grainClassColorsByClass[phase])) }
            },
            Span(phases.joinToString(" + "))
        ).apply {
            isPadding = false
            isSpacing = true
            alignItems = FlexComponent.Alignment.CENTER
            style["gap"] = "8px"
        }

    private fun multiphaseCompositeIndicator(): Component =
        HorizontalLayout().apply {
            isPadding = false
            isSpacing = false
            alignItems = FlexComponent.Alignment.CENTER
            style["gap"] = "4px"
            add(colorDot(grainClassColorsByClass[MULTIPHASE_CLASS_NAME]))
            if (MULTIPHASE_CLASS_NAME in selectedGrainClasses) {
                selectedGrainClasses
                    .filter { it != MULTIPHASE_CLASS_NAME }
                    .sorted()
                    .forEach { phase -> add(colorDot(grainClassColorsByClass[phase])) }
            }
        }

    private fun cardFieldsMenu(): MenuBar =
        cardFieldsMenuBar.apply {
            rebuildCardFieldsMenu(this)
        }

    private fun rebuildCardFieldsMenu(menuBar: MenuBar) {
        menuBar.removeAll()
        val root = menuBar.addIconItem(VaadinIcon.EYE.create())
        root.element.setProperty("title", "Показывать на карточке")
        val subMenu = root.subMenu
        styleToolbarMenu(menuBar, root)

        subMenu.addItem("Маски") {
            showMasksOnCards = !showMasksOnCards
            refreshObjectGallery(resetPaging = false)
            rebuildCardFieldsMenu(menuBar)
        }.apply {
            isCheckable = true
            isChecked = showMasksOnCards
        }
        subMenu.addSeparator()
        subMenu.addItem("Фон: по маске") {
            cardBackgroundMode = CardBackgroundMode.MASKED
            refreshObjectGallery(resetPaging = false)
            rebuildCardFieldsMenu(menuBar)
        }.apply {
            isCheckable = true
            isChecked = cardBackgroundMode == CardBackgroundMode.MASKED
        }
        subMenu.addItem("Фон: исходный") {
            cardBackgroundMode = CardBackgroundMode.ORIGINAL_CROP
            refreshObjectGallery(resetPaging = false)
            rebuildCardFieldsMenu(menuBar)
        }.apply {
            isCheckable = true
            isChecked = cardBackgroundMode == CardBackgroundMode.ORIGINAL_CROP
        }
        subMenu.addSeparator()

        syncCardFieldOrder(availableCardFieldsForPanel())
        reorderCardFieldOrderBySelection()

        val addFieldItem = subMenu.addItem("Поля")
        cardFieldOrder.forEach { field ->
            addFieldItem.subMenu.addItem(prettyLabel(field)) {
                if (field in cardVisibleFields) {
                    cardVisibleFields.remove(field)
                } else {
                    cardVisibleFields.add(field)
                    reorderCardFieldOrderBySelection()
                }
                refreshObjectGallery(resetPaging = false)
                rebuildCardFieldsMenu(menuBar)
            }.apply {
                isCheckable = true
                isChecked = field in cardVisibleFields
            }
        }
    }

    private fun addFilter(filter: ObjectFilter) {
        if (!activeFilters.add(filter)) return
        rebuildVisibleFilterControls()
        refreshObjectGallery(resetPaging = true)
    }

    private fun styleToolbarMenu(menuBar: MenuBar, root: MenuItem) {
        menuBar.style["padding"] = "0"
        menuBar.style["background"] = "transparent"
        root.element.style["padding"] = "0"
        root.element.style["background"] = "transparent"
    }

    private fun removeFilter(filter: ObjectFilter) {
        if (!activeFilters.remove(filter)) return
        when (filter) {
            ObjectFilter.GRAIN_CLASS -> setGrainClassFilterSelection(emptySet())
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
            ObjectFilter.AREA -> {
                areaFromFilter.clear()
                areaToFilter.clear()
            }
        }
        rebuildVisibleFilterControls()
        refreshObjectGallery(resetPaging = true)
    }

    private fun rebuildVisibleFilterControls() {
        filterControls.removeAll()
        activeFilters.forEach { filter ->
            val control = when (filter) {
                ObjectFilter.GRAIN_CLASS -> null
                ObjectFilter.STATUS -> compactFilterGroup("Статус", statusFilter, filter)
                ObjectFilter.CONFIDENCE -> compactPairFilterGroup("Уверенность", confidenceFromFilter, confidenceToFilter, filter)
                ObjectFilter.ANALYSIS_DATE -> compactPairFilterGroup("Дата", analysisDateFromFilter, analysisDateToFilter, filter)
                ObjectFilter.REVIEWED -> compactFilterGroup("Проверено", reviewedFilter, filter)
                ObjectFilter.AREA -> compactPairFilterGroup("Площадь", areaFromFilter, areaToFilter, filter)
            }
            if (control != null) {
                filterControls.add(control)
            }
        }
        filterMenuItems.forEach { (filter, item) -> item.isEnabled = filter !in activeFilters }
    }

    private fun compactFilterGroup(title: String, field: Component, filter: ObjectFilter): Component =
        HorizontalLayout().apply {
            isPadding = false
            isSpacing = true
            style["gap"] = "4px"
            alignItems = FlexComponent.Alignment.CENTER
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
            alignItems = FlexComponent.Alignment.CENTER
        }

    private fun removeFilterButton(filter: ObjectFilter): Button =
        Button("×") { removeFilter(filter) }.apply {
            element.setAttribute("title", "Убрать фильтр")
            style["min-width"] = "24px"
            style["padding"] = "0 6px"
        }

    private fun clearFilters() {
        activeFilters.clear()
        setGrainClassFilterSelection(emptySet())
        statusFilter.clear()
        confidenceFromFilter.clear()
        confidenceToFilter.clear()
        areaFromFilter.clear()
        areaToFilter.clear()
        analysisDateFromFilter.clear()
        analysisDateToFilter.clear()
        reviewedFilter.clear()
        rebuildVisibleFilterControls()
    }

    private fun initFilterListeners() {
        statusFilter.addValueChangeListener { refreshObjectGallery(resetPaging = true) }
        confidenceFromFilter.addValueChangeListener { refreshObjectGallery(resetPaging = true) }
        confidenceToFilter.addValueChangeListener { refreshObjectGallery(resetPaging = true) }
        areaFromFilter.addValueChangeListener { refreshObjectGallery(resetPaging = true) }
        areaToFilter.addValueChangeListener { refreshObjectGallery(resetPaging = true) }
        analysisDateFromFilter.addValueChangeListener { refreshObjectGallery(resetPaging = true) }
        analysisDateToFilter.addValueChangeListener { refreshObjectGallery(resetPaging = true) }
        reviewedFilter.addValueChangeListener { refreshObjectGallery(resetPaging = true) }
    }

    private fun refreshFilterOptions(objects: List<DatasetObject>) {
        val currentGrainClassFilter = selectedGrainClasses.toSet()
        val currentStatusFilter = statusFilter.value?.trim().orEmpty()
        val objectClassColors = objects
            .mapNotNull { candidate ->
                val grainClass = candidate.properties["grain_class"]?.trim().orEmpty()
                val maskColor = normalizeMaskColor(candidate.properties["mask_color_rgb"]).orEmpty()
                if (grainClass.isBlank() || maskColor.isBlank()) return@mapNotNull null
                grainClass to maskColor
            }
            .toMap()
        val collectionClassColors = selectedCollection?.classColors.orEmpty()
        val grainClassColors = collectionClassColors + objectClassColors

        val grainClassItems = (objects.mapNotNull { it.properties["grain_class"] } + currentGrainClassFilter + grainClassColors.keys)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        val statusItems = (objects.mapNotNull { it.properties["meta_status"] } + currentStatusFilter)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        availableGrainClassItems = grainClassItems
        grainClassColorsByClass = grainClassColors
        setGrainClassFilterSelection(currentGrainClassFilter.filter { it in grainClassItems }.toSet())
        statusFilter.setItems(statusItems)
        rebuildFilterAddMenu()
        rebuildGrainClassToolbarMenu()
    }

    private fun refreshObjectGallery(resetPaging: Boolean = false) {
        val allObjects = activeObjects()
        if (activeSelectionTitle().isBlank()) {
            objectHeader.text = "Объекты"
            objectGallery.removeAll()
            objectGallery.add(Paragraph("Выберите проект или коллекцию."))
            return
        }
        val filteredObjects = applyFilters(allObjects)
        if (resetPaging) {
            visibleObjectLimit = minOf(OBJECT_PAGE_SIZE, filteredObjects.size)
        } else {
            visibleObjectLimit = minOf(max(visibleObjectLimit, OBJECT_PAGE_SIZE), filteredObjects.size)
        }

        selectedObjectIds.retainAll(filteredObjects.map { it.id }.toSet())
        syncSelectedObjectState()
        if (selectedObjectIds.isEmpty()) {
            selectedObjectCard = null
            updateRightPanel()
        }

        objectHeader.text = "Объекты (${filteredObjects.size}/${allObjects.size}) • ${activeSelectionTitle()}"
        renderObjects(filteredObjects)
        if (selectedObjectIds.isNotEmpty()) {
            updateObjectSelection()
            if (selectedObjectIds.size == 1) {
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
        val selectedClassFilters = selectedGrainClasses.toSet()
        val status = statusFilter.value?.trim().orEmpty()
        val confidenceFrom = confidenceFromFilter.value
        val confidenceTo = confidenceToFilter.value
        val areaFrom = areaFromFilter.value
        val areaTo = areaToFilter.value
        val analysisFrom = analysisDateFromFilter.value
        val analysisTo = analysisDateToFilter.value
        val reviewed = reviewedFilter.value

        return objects.filter { obj ->
            if (ObjectFilter.GRAIN_CLASS in activeFilters && selectedClassFilters.isNotEmpty()) {
                val includesMultiphase = MULTIPHASE_CLASS_NAME in selectedClassFilters
                val classTerms = selectedClassFilters - MULTIPHASE_CLASS_NAME
                val objectIsMultiphase = isMultiphaseObject(obj)

                if (includesMultiphase) {
                    if (!objectIsMultiphase) return@filter false
                    if (classTerms.isNotEmpty() && !objectHasAllPhaseClasses(obj, classTerms)) return@filter false
                } else {
                    val matchesClass = obj.properties["grain_class"]?.trim() in classTerms
                    val matchesPhaseClass = classTerms.isNotEmpty() && objectHasAnyPhaseClass(obj, classTerms)
                    if (!matchesClass && !matchesPhaseClass) return@filter false
                }
            }
            if (ObjectFilter.STATUS in activeFilters && status.isNotEmpty() && obj.properties["meta_status"] != status) return@filter false

            val confidence = obj.properties["meta_confidence"]?.toDoubleOrNull()
            if (ObjectFilter.CONFIDENCE in activeFilters && confidenceFrom != null && (confidence == null || confidence < confidenceFrom)) return@filter false
            if (ObjectFilter.CONFIDENCE in activeFilters && confidenceTo != null && (confidence == null || confidence > confidenceTo)) return@filter false

            val area = obj.properties["area_px"]?.toDoubleOrNull()
            if (ObjectFilter.AREA in activeFilters && areaFrom != null && (area == null || area < areaFrom)) return@filter false
            if (ObjectFilter.AREA in activeFilters && areaTo != null && (area == null || area > areaTo)) return@filter false

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
            batchField.isEnabled = false
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
                    batchField.isEnabled = true
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
        val datasetName = datasetPath.fileName?.toString().orEmpty()
        val embeddingsCsvPath = datasetPath.resolve("$datasetName.csv")
        val parsedEmbeddingsCsv = if (Files.exists(embeddingsCsvPath)) {
            parseEmbeddingsCsv(embeddingsCsvPath)
        } else {
            ParsedEmbeddingsCsv.EMPTY
        }

        val rawBySuffix = rawImages.associateBy { it.fileName.toString().removePrefix("img-").removeSuffix(".png") }
        val maskBySuffix = rgbMasks.associateBy { it.fileName.toString().removePrefix("msk_rgb-").removeSuffix(".png") }
        val commonSuffixes = rawBySuffix.keys.intersect(maskBySuffix.keys).sorted()
        require(commonSuffixes.isNotEmpty()) { "Не найдено пар img-*.png и msk_rgb-*.png с одинаковым индексом." }

        val datasetSignature = buildDatasetSignature(
            datasetPath = datasetPath,
            sourceBySuffix = rawBySuffix,
            maskBySuffix = maskBySuffix,
            commonSuffixes = commonSuffixes,
            embeddingsCsvPath = embeddingsCsvPath.takeIf { Files.exists(it) }
        )
        val cacheDir = cacheRoot.resolve("$datasetDirectoryName-$datasetSignature")
        Files.createDirectories(cacheDir)
        val manifestPath = cacheDir.resolve("manifest.json")

        val cachedObjects = if (Files.exists(manifestPath)) loadCachedObjects(manifestPath) else emptyList()
        val resolvedObjects = if (
            cachedObjects.isNotEmpty() &&
                cachedObjects.all { cached ->
                    val hasMaskedPreview =
                        cached.previewFileName.isNotBlank() &&
                            Files.isRegularFile(cacheDir.resolve(cached.previewFileName))
                    val hasCropPreview = cached.properties["crop_preview_file"]
                        ?.takeIf { it.isNotBlank() }
                        ?.let { cropPreviewFile -> Files.isRegularFile(cacheDir.resolve(cropPreviewFile)) }
                        ?: false
                    hasMaskedPreview && hasCropPreview
                }
        ) {
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
                        csvRowsBySourceImage = parsedEmbeddingsCsv.rowsBySourceImage,
                        embeddingColumnNames = parsedEmbeddingsCsv.embeddingColumnNames,
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
            cached.properties["crop_preview_file"]?.let { cropFile ->
                properties["crop_preview_url"] = cacheDir.resolve(cropFile).toString()
            }
            DatasetObject(
                id = cached.id,
                name = cached.name,
                category = cached.category,
                previewUrl = cacheDir.resolve(cached.previewFileName).toString(),
                embeddings = cached.embeddings,
                embeddingColumnNames = cached.embeddingColumnNames,
                properties = properties
            )
        }

        val projectName = Path.of(datasetDirectoryName).fileName?.toString().orEmpty().ifBlank { datasetDirectoryName }
        return DatasetProject(
            id = "dataset-$datasetDirectoryName",
            name = projectName,
            batch = batchName.ifBlank { "Без партии" },
            type = "Импорт из /siams/images",
            imageCount = commonSuffixes.size,
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
            obj.properties["crop_preview_url"]?.let { resolvedProperties["crop_preview_url"] = resolvePreviewUrl(it) }
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
        csvRowsBySourceImage: Map<String, List<CsvEmbeddingRow>>,
        embeddingColumnNames: List<String>,
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
        val sourceImageName = sourceImagePath.fileName.toString()
        val pendingEmbeddingRows = csvRowsBySourceImage[sourceImageName]?.toMutableList() ?: mutableListOf()
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
                val embeddings = if (objectClassInfo.phaseType == "single_phase") {
                    findAndConsumeEmbeddingsForComponent(component, pendingEmbeddingRows)
                } else {
                    doubleArrayOf()
                }
                val phaseBoundaryStats = collectPhaseBoundaryStatistics(mask, component.points, legend)
                val previewFileName = "grain-$suffix-$grainCounter.png"
                val previewPath = cacheDir.resolve(previewFileName)
                Files.write(previewPath, buildMaskedCrop(source, component))
                val cropPreviewFileName = "grain-crop-$suffix-$grainCounter.png"
                val cropPreviewPath = cacheDir.resolve(cropPreviewFileName)
                Files.write(cropPreviewPath, buildUnmaskedCrop(source, component))
                val maskPreviewFileName = "grain-mask-$suffix-$grainCounter.png"
                val maskPreviewPath = cacheDir.resolve(maskPreviewFileName)
                Files.write(maskPreviewPath, buildMaskedCrop(mask, component))

                objects += CachedDatasetObject(
                    id = "$datasetDirectoryName-$suffix-$grainCounter",
                    name = objectClassInfo.grainClass,
                    category = "OreGrain",
                    previewFileName = previewFileName,
                    embeddings = embeddings,
                    embeddingColumnNames = embeddingColumnNames,
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
                        "phase_boundary_px" to phaseBoundaryStats.totalBoundaryEdges.toString(),
                        "phase_boundary_density" to "%.6f".format(Locale.US, phaseBoundaryStats.boundaryDensity),
                        "phase_boundary_pair_shares" to phaseBoundaryStats.pairSharesJson,
                        "phase_boundary_dominant_pair" to phaseBoundaryStats.dominantPair,
                        "phase_boundary_entropy" to "%.6f".format(Locale.US, phaseBoundaryStats.boundaryEntropy),
                        "area_px" to phaseStatistics.values.sum().toString(),
                        "mask_crop_file" to maskPreviewFileName,
                        "crop_preview_file" to cropPreviewFileName,
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
        csvRowsBySourceImage: Map<String, List<CsvEmbeddingRow>>,
        embeddingColumnNames: List<String>,
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
                    csvRowsBySourceImage = csvRowsBySourceImage,
                    embeddingColumnNames = embeddingColumnNames,
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

    private fun collectPhaseBoundaryStatistics(
        mask: BufferedImage,
        points: List<Point>,
        legend: Map<Int, String>
    ): PhaseBoundaryStats {
        if (points.isEmpty()) return PhaseBoundaryStats.EMPTY
        val pointSet = points.asSequence().map { it.x to it.y }.toHashSet()
        val boundaryByPair = linkedMapOf<String, Int>()
        var totalBoundaryEdges = 0

        points.forEach { point ->
            val color = mask.rgbNoAlpha(point.x, point.y)
            if (color == 0x000000) return@forEach
            listOf(point.x + 1 to point.y, point.x to point.y + 1).forEach { (nx, ny) ->
                if ((nx to ny) !in pointSet) return@forEach
                val neighborColor = mask.rgbNoAlpha(nx, ny)
                if (neighborColor == color || neighborColor == 0x000000) return@forEach
                totalBoundaryEdges += 1
                val pair = listOf(
                    legend[color] ?: "Unknown(${toHexColor(color)})",
                    legend[neighborColor] ?: "Unknown(${toHexColor(neighborColor)})"
                ).sorted().joinToString(" | ")
                boundaryByPair[pair] = (boundaryByPair[pair] ?: 0) + 1
            }
        }

        if (totalBoundaryEdges == 0) return PhaseBoundaryStats.EMPTY

        val pairShares = boundaryByPair.entries.sortedByDescending { it.value }.associate { (pair, count) ->
            pair to count.toDouble() / totalBoundaryEdges.toDouble()
        }
        val pairSharesJson = pairShares.entries.joinToString(prefix = "{", postfix = "}") { (pair, share) ->
            "\"$pair\":${"%.6f".format(Locale.US, share)}"
        }
        val dominantPair = boundaryByPair.maxByOrNull { it.value }?.key.orEmpty()
        val boundaryEntropy = pairShares.values.fold(0.0) { acc, share ->
            if (share <= 0.0) acc else acc - share * (kotlin.math.ln(share) / kotlin.math.ln(2.0))
        }
        return PhaseBoundaryStats(
            totalBoundaryEdges = totalBoundaryEdges,
            boundaryDensity = totalBoundaryEdges.toDouble() / points.size.toDouble(),
            pairSharesJson = pairSharesJson,
            dominantPair = dominantPair,
            boundaryEntropy = boundaryEntropy
        )
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

    private fun buildUnmaskedCrop(source: BufferedImage, component: ConnectedComponent): ByteArray {
        val cropWidth = component.maxX - component.minX + 1
        val cropHeight = component.maxY - component.minY + 1
        val crop = BufferedImage(cropWidth, cropHeight, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until cropHeight) {
            for (x in 0 until cropWidth) {
                crop.setRGB(x, y, source.getRGB(component.minX + x, component.minY + y))
            }
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
        commonSuffixes: List<String>,
        embeddingsCsvPath: Path?
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
        if (embeddingsCsvPath != null && Files.exists(embeddingsCsvPath)) {
            updateDigestWithFile(digest, embeddingsCsvPath)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun parseEmbeddingsCsv(csvPath: Path): ParsedEmbeddingsCsv {
        val rowsBySourceImage = linkedMapOf<String, MutableList<CsvEmbeddingRow>>()
        Files.newBufferedReader(csvPath).use { reader ->
            val header = reader.readLine() ?: return ParsedEmbeddingsCsv.EMPTY
            val columns = header.split(",")
            val imageIndex = columns.indexOf("image")
            val xIndex = columns.indexOf("x₀").takeIf { it >= 0 } ?: columns.indexOf("x0")
            val yIndex = columns.indexOf("y₀").takeIf { it >= 0 } ?: columns.indexOf("y0")
            val embeddingStartIndex = columns.indexOf("L")
            if (imageIndex < 0 || xIndex < 0 || yIndex < 0 || embeddingStartIndex < 0) {
                log.warn("CSV {} пропущен: отсутствуют обязательные столбцы image/x₀/y₀/L.", csvPath.fileName)
                return ParsedEmbeddingsCsv.EMPTY
            }
            val embeddingColumnNames = columns.subList(embeddingStartIndex, columns.size).map { it.trim() }

            reader.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                val values = line.split(",")
                if (values.size <= embeddingStartIndex) return@forEach
                val imageName = values.getOrNull(imageIndex)?.trim().orEmpty()
                val x = values.getOrNull(xIndex)?.trim()?.toIntOrNull() ?: return@forEach
                val y = values.getOrNull(yIndex)?.trim()?.toIntOrNull() ?: return@forEach
                val sourceImageName = sourceImageNameFromCsvImage(imageName) ?: return@forEach
                val embeddings = values.subList(embeddingStartIndex, values.size)
                    .map { it.trim().toDoubleOrNull() ?: return@forEach }
                    .toDoubleArray()
                val row = CsvEmbeddingRow(x = x, y = y, embeddings = embeddings)
                rowsBySourceImage.getOrPut(sourceImageName) { mutableListOf() }.add(row)
            }
            return ParsedEmbeddingsCsv(
                rowsBySourceImage = rowsBySourceImage.mapValues { (_, rows) -> rows.toList() },
                embeddingColumnNames = embeddingColumnNames
            )
        }
        return ParsedEmbeddingsCsv.EMPTY
    }

    private fun sourceImageNameFromCsvImage(imageName: String): String? {
        val normalized = imageName.substringAfterLast('/').substringAfterLast('\\').trim()
        if (!normalized.endsWith(".png")) return null
        val prefix = normalized.substringBefore("_p", missingDelimiterValue = normalized.removeSuffix(".png"))
        if (prefix.isBlank()) return null
        return "$prefix.png"
    }

    private fun findAndConsumeEmbeddingsForComponent(
        component: ConnectedComponent,
        pendingRows: MutableList<CsvEmbeddingRow>
    ): DoubleArray {
        if (pendingRows.isEmpty()) return doubleArrayOf()
        val rowIndex = pendingRows.indexOfFirst { row ->
            row.x in component.minX..component.maxX &&
                row.y in component.minY..component.maxY &&
                component.points.any { point -> point.x == row.x && point.y == row.y }
        }
        if (rowIndex < 0) return doubleArrayOf()
        return pendingRows.removeAt(rowIndex).embeddings
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
            val embeddingsNode = mapper.createArrayNode()
            item.embeddings.forEach { embeddingsNode.add(it) }
            node.set<com.fasterxml.jackson.databind.JsonNode>("embeddings", embeddingsNode)
            val embeddingColumnsNode = mapper.createArrayNode()
            item.embeddingColumnNames.forEach { embeddingColumnsNode.add(it) }
            node.set<com.fasterxml.jackson.databind.JsonNode>("embeddingColumnNames", embeddingColumnsNode)
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
            val embeddings = node.path("embeddings")
                .takeIf { it.isArray }
                ?.mapNotNull { value -> if (value.isNumber) value.asDouble() else null }
                ?.toDoubleArray()
                ?: doubleArrayOf()
            val embeddingColumnNames = node.path("embeddingColumnNames")
                .takeIf { it.isArray }
                ?.mapNotNull { value -> value.asText(null) }
                ?: emptyList()
            val propertiesNode = node.path("properties")
            val props = mutableMapOf<String, String>()
            if (propertiesNode.isObject) {
                propertiesNode.properties().forEach { (k, v) -> props[k] = v.asText() }
            }
            CachedDatasetObject(id, name, category, preview, embeddings, embeddingColumnNames, props)
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
        val meta = Span("Изображений: ${project.imageCount} • Объектов: ${project.objects.size}").apply {
            style["color"] = "var(--lumo-secondary-text-color)"
            style["font-size"] = "var(--lumo-font-size-s)"
            style["display"] = "block"
        }

        return Div(image, Div(title, meta)).apply {
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

    private fun collectionCard(collection: DatasetCollection, selected: Boolean, onClick: () -> Unit): Component {
        val title = Span(collection.name).apply {
            style["font-weight"] = "600"
            style["display"] = "block"
        }
        val meta = Span("Объектов: ${collection.objects.size}").apply {
            style["color"] = "var(--lumo-secondary-text-color)"
            style["font-size"] = "var(--lumo-font-size-s)"
            style["display"] = "block"
        }

        return Div(VaadinIcon.ARCHIVES.create(), Div(title, meta)).apply {
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

    private fun mergeProjectObjectsIntoCollection(
        sourceProject: DatasetProject,
        targetCollection: DatasetCollection,
        sourceObjects: List<DatasetObject>,
        resolvedSourceClassToColor: Map<String, String>
    ): MergeResult {
        val sourceClassToColor = resolvedSourceClassToColor
        val existingIds = targetCollection.objects.map { it.id }.toMutableSet()
        val newObjects = sourceObjects
            .filter { it.id !in existingIds }
            .map { obj ->
                val copiedProperties = obj.properties.toMutableMap()
                copiedProperties.putIfAbsent("source_project_id", obj.sourceProjectId ?: sourceProject.id)
                copiedProperties.putIfAbsent("source_project_name", obj.sourceProjectName ?: sourceProject.name)
                obj.copy(
                    properties = copiedProperties,
                    sourceProjectId = obj.sourceProjectId ?: sourceProject.id,
                    sourceProjectName = obj.sourceProjectName ?: sourceProject.name
                )
            }
        val skippedCount = sourceObjects.size - newObjects.size
        targetCollection.objects.addAll(newObjects)
        targetCollection.classColors.putAll(sourceClassToColor)
        return MergeResult(
            success = true,
            message = buildString {
                append("Добавлено ${newObjects.size} из ${sourceObjects.size} объектов в коллекцию \"${targetCollection.name}\".")
                if (skippedCount > 0) {
                    append(" Пропущено дублей: $skippedCount.")
                }
            }
        )
    }

    private fun classColorMap(objects: List<DatasetObject>): Map<String, String> =
        objects
            .mapNotNull { obj ->
                val grainClass = obj.properties["grain_class"]?.trim().orEmpty()
                val color = normalizeMaskColor(obj.properties["mask_color_rgb"]).orEmpty()
                if (grainClass.isBlank() || color.isBlank()) return@mapNotNull null
                grainClass to color
            }
            .toMap()

    private fun classNamesInObjects(objects: List<DatasetObject>): Set<String> {
        val directClasses = objects
            .mapNotNull { it.properties["grain_class"]?.trim() }
            .filter { it.isNotBlank() }
            .toMutableSet()

        objects.forEach { obj ->
            val rawJson = obj.properties["phase_area_shares"].orEmpty().trim()
            if (rawJson.isBlank()) return@forEach
            val root = runCatching { jsonMapper.readTree(rawJson) }.getOrNull() ?: return@forEach
            if (!root.isObject) return@forEach
            root.properties().forEach { (phaseName, _) ->
                val clean = phaseName.trim()
                if (clean.isNotBlank()) directClasses.add(clean)
            }
        }
        directClasses.remove(MULTIPHASE_CLASS_NAME)
        return directClasses
    }

    private fun showError(message: String) {
        Notification.show(message, 4500, Notification.Position.MIDDLE).addThemeVariants(NotificationVariant.LUMO_ERROR)
    }

    private fun objectCard(
        obj: DatasetObject,
        selected: Boolean,
        visibleFields: List<String>,
        grainClassColors: Map<String, String>,
        onClick: (com.vaadin.flow.component.ClickEvent<Div>) -> Unit
    ): Div {
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

        val previewUrl = if (cardBackgroundMode == CardBackgroundMode.ORIGINAL_CROP) {
            obj.properties["crop_preview_url"] ?: obj.previewUrl
        } else {
            obj.previewUrl
        }
        val image = Image(previewUrl, obj.name).apply {
            style["display"] = "block"
            style["width"] = "${imageDisplayWidth}px"
            style["height"] = "${imageDisplayHeight}px"
            style["object-fit"] = "contain"
            style["image-rendering"] = "pixelated"
        }
        val imageStack = Div(image).apply {
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

        val titleOverlay = Div().apply {
            style["position"] = "absolute"
            style["left"] = "0"
            style["right"] = "0"
            style["top"] = "0"
            style["padding"] = "8px 10px"
            style["display"] = "flex"
            style["flex-direction"] = "column"
            style["align-items"] = "flex-start"
            style["gap"] = "4px"
            style["pointer-events"] = "none"
        }

        fun overlayText(value: String, color: String): Span = Span(value).apply {
            style["font-weight"] = "600"
            style["display"] = "block"
            style["color"] = color
            style["line-height"] = "1.2"
            style["font-size"] = "var(--lumo-font-size-xs)"
            style["text-shadow"] = "0 0 2px rgba(0,0,0,0.98), 0 0 4px rgba(0,0,0,0.9), 1px 1px 0 rgba(0,0,0,0.95), -1px -1px 0 rgba(0,0,0,0.95)"
            style["-webkit-text-stroke"] = "0.4px rgba(0,0,0,0.95)"
        }

        overlayLinesForCard(obj, titleColor, visibleFields, grainClassColors).forEach { line ->
            titleOverlay.add(overlayText(line.text, line.color))
        }

        return Div(imageStack, titleOverlay).apply {
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
            addClickListener { event -> onClick(event) }
            addDoubleClickListener {
                openMaskEditor(obj)
            }
        }
    }

    private fun openMaskEditor(obj: DatasetObject) {
        val sourceImageUrl = obj.properties["crop_preview_url"] ?: obj.previewUrl
        val maskImageUrl = obj.properties["mask_crop_url"]
        val colorByPhase = grainClassColorMapForCurrentDataset()
        val objectColor = normalizeMaskColor(obj.properties["mask_color_rgb"])
        val objectPhase = obj.properties["grain_class"]?.trim().orEmpty()
        val phasesInObject = linkedSetOf<String>().apply {
            if (objectPhase.isNotBlank() && objectPhase != MULTIPHASE_CLASS_NAME) add(objectPhase)
            val phaseJson = obj.properties["phase_area_shares"].orEmpty().trim()
            if (phaseJson.isNotBlank()) {
                val root = runCatching { jsonMapper.readTree(phaseJson) }.getOrNull()
                if (root != null && root.isObject) {
                    root.properties().forEach { (phaseName, _) ->
                        val clean = phaseName.trim()
                        if (clean.isNotBlank() && clean != MULTIPHASE_CLASS_NAME) add(clean)
                    }
                }
            }
        }
        val resolvedColors = phasesInObject
            .mapNotNull { phaseName ->
                val resolvedColor = if (phaseName == objectPhase && !objectColor.isNullOrBlank()) {
                    objectColor
                } else {
                    colorByPhase[phaseName]
                }
                val normalized = normalizeMaskColor(resolvedColor).orEmpty()
                if (normalized.isBlank()) null else phaseName to normalized
            }
            .toMap()

        maskEditorDialog.openEditor(
            objectName = obj.name,
            sourceImageUrl = sourceImageUrl,
            maskImageUrl = maskImageUrl,
            phaseColors = resolvedColors
        ) { editedMaskDataUrl ->
            obj.properties["mask_crop_url"] = editedMaskDataUrl
            refreshCurrentSelection()
        }
    }

    private fun shouldShowMaskOverlay(obj: DatasetObject): Boolean {
        if (!showMasksOnCards) return false
        val hasMaskUrl = !obj.properties["mask_crop_url"].isNullOrBlank()
        return hasMaskUrl
    }

    private fun overlayLinesForCard(
        obj: DatasetObject,
        grainClassColor: String,
        visibleFields: List<String>,
        grainClassColors: Map<String, String>
    ): List<OverlayLine> =
        visibleFields
            .asSequence()
            .flatMap { field ->
                if (field == "phase_area_shares") {
                    val rawJson = obj.properties[field].orEmpty()
                    phaseAreaShareOverlayLines(obj, rawJson, grainClassColors).asSequence()
                } else {
                    val value = obj.properties[field]?.trim().orEmpty()
                    if (value.isBlank()) {
                        emptySequence()
                    } else {
                        sequenceOf(
                            OverlayLine(
                                text = value,
                                color = if (field == "grain_class") grainClassColor else "white"
                            )
                        )
                    }
                }
            }
            .toList()

    private fun phaseAreaShareOverlayLines(
        obj: DatasetObject,
        rawJson: String,
        grainClassColors: Map<String, String>
    ): List<OverlayLine> {
        if (rawJson.isBlank()) return emptyList()
        val root = runCatching { jsonMapper.readTree(rawJson) }.getOrNull() ?: return emptyList()
        if (!root.isObject) return emptyList()
        val isSinglePhaseObject = obj.properties["object_phase_type"]?.trim()?.lowercase() != "multi_phase"

        return root.properties().asSequence()
            .mapNotNull { (phaseName, rawValueNode) ->
                val value = rawValueNode.asDouble(Double.NaN)
                if (value.isNaN()) return@mapNotNull null
                val percentValue = if (value <= 1.0) value * 100.0 else value
                val roundedPercent = String.format(Locale.US, "%.0f%%", percentValue)
                val phaseColor = grainClassColors[phaseName]
                    ?.let { normalizeMaskColor(it) }
                    ?.let { "#" + it.removePrefix("0x") }
                    ?: "white"
                OverlayLine(
                    text = if (isSinglePhaseObject) phaseName else "$phaseName: $roundedPercent",
                    color = phaseColor
                )
            }
            .toList()
    }

    private fun orderedVisibleCardFields(): List<String> {
        if (cardFieldOrder.isEmpty()) {
            syncCardFieldOrder(availableCardFieldsForPanel())
        }
        return cardFieldOrder.filter { it in cardVisibleFields }
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

    private fun updateRightPanel() {
        propertyEditor.removeAll()
        val obj = selectedObject
        propertyEditor.isVisible = true
        cardFieldsPanel.isVisible = false
        if (selectedObjectIds.size > 1) {
            cardFieldsPanelTitle.text = "Свойства объекта"
            propertyEditor.add(Paragraph("Выбрано объектов: ${selectedObjectIds.size}. Свойства доступны только для одиночного выбора."))
            return
        }
        if (obj == null) {
            cardFieldsPanelTitle.text = "Статистика выборки"
            propertyEditor.add(
                buildStatisticsPrototypePanel(),
                Paragraph("Выберите объект, чтобы увидеть и отредактировать его свойства.")
            )
            return
        }
        cardFieldsPanelTitle.text = "Свойства объекта"
        propertyEditor.add(
            propertySection("Основные параметры", buildPropertyForm(obj)),
            propertySection("Расширенные атрибуты", buildAdvancedControls(obj))
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
                val editor = propertyInput(name, value, obj)
                form.addFormItem(editor, prettyLabel(name))
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
                addValueChangeListener {
                    obj.properties[name] = it.value
                    refreshCardsIfOverlayDependsOn(name)
                }
            }
            else -> TextField().apply {
                this.value = value
                isClearButtonVisible = true
                setWidthFull()
                addValueChangeListener {
                    obj.properties[name] = it.value
                    refreshCardsIfOverlayDependsOn(name)
                }
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
            val phaseAreaSharesChanged = syncSinglePhaseAreaSharesWithGrainClass(obj, normalizedValue)
            colorByGrainClass[normalizedValue]?.let { nativeMaskColor ->
                obj.properties["mask_color_rgb"] = nativeMaskColor
            }
            applyColorIconToCombo(editor, obj.properties["mask_color_rgb"])
            refreshFilterOptions(activeObjects())
            val activeFilterValues = selectedGrainClasses.toMutableSet()
            val shouldKeepEditedObjectVisible =
                ObjectFilter.GRAIN_CLASS in activeFilters &&
                    previousValue.isNotBlank() &&
                    previousValue in activeFilterValues &&
                    normalizedValue != previousValue

            if (shouldKeepEditedObjectVisible) {
                activeFilterValues.remove(previousValue)
                if (normalizedValue.isNotBlank()) activeFilterValues.add(normalizedValue)
                setGrainClassFilterSelection(activeFilterValues)
            } else if ("grain_class" in cardVisibleFields || (phaseAreaSharesChanged && "phase_area_shares" in cardVisibleFields)) {
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

    private fun syncSinglePhaseAreaSharesWithGrainClass(obj: DatasetObject, grainClass: String): Boolean {
        if (grainClass.isBlank()) return false
        val isMultiPhaseObject = obj.properties["object_phase_type"]?.trim()?.lowercase() == "multi_phase"
        if (isMultiPhaseObject) return false

        val rawJson = obj.properties["phase_area_shares"].orEmpty().trim()
        if (rawJson.isBlank()) return false
        val root = runCatching { jsonMapper.readTree(rawJson) }.getOrNull() ?: return false
        if (!root.isObject || root.size() != 1) return false

        val currentEntry = root.properties().firstOrNull() ?: return false
        if (currentEntry.key == grainClass) return false

        val updatedJson = jsonMapper
            .createObjectNode()
            .set<com.fasterxml.jackson.databind.JsonNode>(grainClass, currentEntry.value)
            .toString()
        obj.properties["phase_area_shares"] = updatedJson
        return true
    }

    private fun grainClassOptionsForCurrentDataset(currentValue: String): List<String> {
        val datasetOptions = activeObjects()
            .mapNotNull { it.properties["grain_class"]?.trim() }
            .filter { it.isNotBlank() && it != MULTIPHASE_CLASS_NAME }
            .distinct()
            .sorted()
        val collectionOptions = selectedCollection?.classColors?.keys.orEmpty()

        return (datasetOptions + collectionOptions + currentValue.trim())
            .filter { it.isNotBlank() }
            .filter { it == currentValue.trim() || it != MULTIPHASE_CLASS_NAME }
            .distinct()
    }

    private fun grainClassColorMapForCurrentDataset(): Map<String, String> =
        (selectedCollection?.classColors.orEmpty() + activeObjects()
            .mapNotNull { candidate ->
                val grainClass = candidate.properties["grain_class"]?.trim().orEmpty()
                val maskColor = normalizeMaskColor(candidate.properties["mask_color_rgb"]).orEmpty()
                if (grainClass.isBlank() || maskColor.isBlank()) return@mapNotNull null
                grainClass to maskColor
            }
            .toMap())

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

    private fun setGrainClassFilterSelection(values: Set<String>) {
        val current = selectedGrainClasses.toSet()
        if (current == values) return
        selectedGrainClasses.clear()
        selectedGrainClasses.addAll(values)
        if (MULTIPHASE_CLASS_NAME in selectedGrainClasses) {
            showMasksOnCards = true
            rebuildCardFieldsMenu(cardFieldsMenuBar)
        }
        rebuildFilterAddMenu()
        rebuildGrainClassToolbarMenu()
        refreshObjectGallery(resetPaging = true)
    }

    private fun isMultiphaseObject(obj: DatasetObject): Boolean =
        obj.properties["object_phase_type"]?.trim()?.lowercase() == "multi_phase" ||
            obj.properties["grain_class"]?.trim() == MULTIPHASE_CLASS_NAME

    private fun objectHasAnyPhaseClass(obj: DatasetObject, classes: Set<String>): Boolean {
        if (classes.isEmpty()) return false
        val rawJson = obj.properties["phase_area_shares"].orEmpty().trim()
        if (rawJson.isBlank()) return false
        val root = runCatching { jsonMapper.readTree(rawJson) }.getOrNull() ?: return false
        if (!root.isObject) return false
        return root.properties().asSequence().any { (phaseName, _) -> phaseName in classes }
    }

    private fun objectHasAllPhaseClasses(obj: DatasetObject, classes: Set<String>): Boolean {
        if (classes.isEmpty()) return true
        val rawJson = obj.properties["phase_area_shares"].orEmpty().trim()
        if (rawJson.isBlank()) return false
        val root = runCatching { jsonMapper.readTree(rawJson) }.getOrNull() ?: return false
        if (!root.isObject) return false
        val phases = root.properties().asSequence().map { it.key }.toSet()
        return classes.all { it in phases }
    }

    private fun grainClassOptionView(grainClass: String, rawMaskColor: String?): Component =
        HorizontalLayout(colorDot(rawMaskColor), Span(grainClass)).apply {
            isPadding = false
            isSpacing = true
            alignItems = FlexComponent.Alignment.CENTER
            style["gap"] = "8px"
        }

    private fun colorDot(rawMaskColor: String?): Component {
        val maskColor = normalizeMaskColor(rawMaskColor)
        val cssHex = if (maskColor == null) "transparent" else "#" + maskColor.removePrefix("0x")
        return Div().apply {
            style["width"] = "12px"
            style["height"] = "12px"
            style["border-radius"] = "999px"
            style["background"] = cssHex
            style["border"] = "1px solid rgba(0,0,0,0.85)"
            style["display"] = "inline-block"
            style["flex-shrink"] = "0"
        }
    }

    private fun toolbarColorDot(rawMaskColor: String?): Component {
        val maskColor = normalizeMaskColor(rawMaskColor)
        return Div().apply {
            style["width"] = "14px"
            style["height"] = "14px"
            style["border-radius"] = "999px"
            style["display"] = "inline-block"
            style["flex-shrink"] = "0"
            style["background"] = if (maskColor == null) "var(--lumo-primary-color-50pct)" else "#" + maskColor.removePrefix("0x")
            style["border"] = "1px solid rgba(0,0,0,0.85)"
        }
    }

    private fun colorPreviewEditor(value: String): Component {
        val colorHex = value.removePrefix("0x")
            .takeIf { it.length == 6 && it.all { ch -> ch.isDigit() || ch.lowercaseChar() in 'a'..'f' } }
            ?.let { "#$it" }
            ?: "#000000"
        val swatch = Div().apply {
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
            alignItems = FlexComponent.Alignment.CENTER
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
                    refreshFilterOptions(activeObjects())
                }
                refreshCardsIfOverlayDependsOn(name)
            }
            addCustomValueSetListener { event ->
                obj.properties[name] = event.detail
                this.value = event.detail
                if (name == "grain_class") {
                    refreshFilterOptions(activeObjects())
                }
                refreshCardsIfOverlayDependsOn(name)
            }
        }

    private fun refreshCardsIfOverlayDependsOn(fieldName: String) {
        if (fieldName in cardVisibleFields) {
            refreshObjectGallery(resetPaging = false)
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
                refreshFilterOptions(activeObjects())
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

        obj.properties.putIfAbsent("meta_status", status.value ?: "Черновик")
        obj.properties.putIfAbsent("meta_confidence", ((confidence.value ?: 85.0).toInt()).toString())
        obj.properties.putIfAbsent("meta_analysis_date", analysisDate.value?.toString().orEmpty())
        obj.properties.putIfAbsent("meta_reviewed", reviewed.value.toString())

        form.add(status, confidence, analysisDate, reviewed)
        return form
    }

    private fun buildCardFieldsPanel(): Component {
        val fields = availableCardFieldsForPanel()
        syncCardFieldOrder(fields)
        reorderCardFieldOrderBySelection()
        val list = VerticalLayout().apply {
            isPadding = false
            isSpacing = true
            setWidthFull()
        }
        cardFieldOrder.forEach { field ->
            val toggle = Checkbox().apply {
                value = field in cardVisibleFields
                addValueChangeListener {
                    if (it.value) cardVisibleFields.add(field) else cardVisibleFields.remove(field)
                    reorderCardFieldOrderBySelection()
                    refreshCardFieldsPanelView()
                    refreshObjectGallery(resetPaging = false)
                }
            }
            val dragHandle = Span("⋮⋮").apply {
                style["cursor"] = "grab"
                style["user-select"] = "none"
                style["color"] = "var(--lumo-secondary-text-color)"
                style["font-size"] = "var(--lumo-font-size-s)"
                element.setProperty("title", "Перетащите для изменения порядка")
            }

            val row = HorizontalLayout(
                HorizontalLayout(toggle, Span(prettyLabel(field))).apply {
                    isPadding = false
                    isSpacing = true
                    alignItems = FlexComponent.Alignment.BASELINE
                    style["gap"] = "8px"
                },
                dragHandle
            ).apply {
                setWidthFull()
                isPadding = false
                isSpacing = true
                alignItems = FlexComponent.Alignment.CENTER
                style["justify-content"] = "space-between"
                style["padding"] = "2px 0"
                style["border-radius"] = "6px"
            }

            DragSource.create(row).apply {
                setDraggable(true)
                addDragStartListener {
                    draggedCardField = field
                    row.style["background"] = "var(--lumo-contrast-10pct)"
                }
                addDragEndListener {
                    draggedCardField = null
                    row.style.remove("background")
                }
            }
            DropTarget.create(row).apply {
                dropEffect = DropEffect.MOVE
                addDropListener {
                    val dragged = draggedCardField ?: return@addDropListener
                    if (dragged == field) return@addDropListener
                    val fromIndex = cardFieldOrder.indexOf(dragged)
                    val targetIndex = cardFieldOrder.indexOf(field)
                    if (fromIndex == -1 || targetIndex == -1) return@addDropListener
                    cardFieldOrder.removeAt(fromIndex)
                    val insertIndex = if (fromIndex < targetIndex) targetIndex - 1 else targetIndex
                    cardFieldOrder.add(insertIndex, dragged)
                    reorderCardFieldOrderBySelection()
                    refreshCardFieldsPanelView()
                    refreshObjectGallery(resetPaging = false)
                }
            }

            list.add(row)
        }
        return propertySection("Показывать на карточке", list).apply {
            element.style["width"] = "100%"
        }
    }

    private fun buildStatisticsPrototypePanel(): Component {
        val sections = mutableListOf<Component>()
        when (leftPanelMode) {
            LeftPanelMode.PROJECTS -> {
                selectedProject?.let { project ->
                    sections += propertySection(
                        "Статистика проекта: ${project.name}",
                        phaseStatisticsView(project.objects)
                    )
                    val batchObjects = projects
                        .asSequence()
                        .filter { it.batch == project.batch }
                        .flatMap { it.objects.asSequence() }
                        .toList()
                    sections += propertySection(
                        "Статистика партии: ${project.batch}",
                        phaseStatisticsView(batchObjects)
                    )
                }
            }
            LeftPanelMode.COLLECTIONS -> {
                selectedCollection?.let { collection ->
                    sections += propertySection(
                        "Статистика коллекции: ${collection.name}",
                        phaseStatisticsView(collection.objects)
                    )
                }
            }
        }
        if (sections.isEmpty()) {
            val hint = if (leftPanelMode == LeftPanelMode.PROJECTS) {
                "Выберите проект для просмотра статистики проекта и партии."
            } else {
                "Выберите коллекцию для просмотра статистики."
            }
            sections += propertySection("Статистика", Paragraph(hint))
        }
        return VerticalLayout(*sections.toTypedArray()).apply {
            isPadding = false
            isSpacing = true
            setWidthFull()
        }
    }

    private fun phaseStatisticsView(objects: List<DatasetObject>): Component {
        if (objects.isEmpty()) return Paragraph("Нет данных для статистики.")
        val areaByPhase = linkedMapOf<String, Double>()
        val boundaryByPair = linkedMapOf<String, Double>()
        var boundaryPxTotal = 0.0

        objects.forEach { obj ->
            val areaPx = obj.properties["area_px"]?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1.0
            val root = runCatching { jsonMapper.readTree(obj.properties["phase_area_shares"].orEmpty()) }.getOrNull()
            if (root?.isObject == true) {
                root.properties().asSequence().forEach { (phaseName, node) ->
                    val share = node.asDouble(0.0)
                    val normalized = if (share <= 1.0) share else share / 100.0
                    areaByPhase[phaseName] = (areaByPhase[phaseName] ?: 0.0) + normalized * areaPx
                }
            }
            obj.properties["phase_boundary_px"]?.toDoubleOrNull()?.let { boundaryPxTotal += it }
            val boundaryPx = obj.properties["phase_boundary_px"]?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 0.0
            val pairRoot = runCatching { jsonMapper.readTree(obj.properties["phase_boundary_pair_shares"].orEmpty()) }.getOrNull()
            if (boundaryPx > 0.0 && pairRoot?.isObject == true) {
                pairRoot.properties().asSequence().forEach { (pair, node) ->
                    val share = node.asDouble(0.0).coerceIn(0.0, 1.0)
                    boundaryByPair[pair] = (boundaryByPair[pair] ?: 0.0) + share * boundaryPx
                }
            }
        }

        val totalArea = areaByPhase.values.sum()
        if (totalArea <= 0.0) return Paragraph("Нет фазовых долей для диаграммы.")
        val shares = areaByPhase.entries
            .map { it.key to (it.value / totalArea) }
            .sortedByDescending { it.second }

        val layout = VerticalLayout().apply {
            isPadding = false
            isSpacing = true
            setWidthFull()
        }
        layout.add(buildInteractivePieChart("Доли фаз по площади, %", shares))
        layout.add(Hr(), buildBoundaryPropertyHistograms(objects))
        if (boundaryByPair.isNotEmpty()) {
            val topBoundaryShares = boundaryByPair.entries
                .sortedByDescending { it.value }
                .take(8)
                .map { it.key to (it.value / boundaryByPair.values.sum()) }
            val topSum = topBoundaryShares.sumOf { it.second }
            val boundaryShares = topBoundaryShares.toMutableList()
            val otherShare = (1.0 - topSum).coerceAtLeast(0.0)
            if (otherShare > 0.0001) {
                boundaryShares += "Прочие контакты" to otherShare
            }
            layout.add(buildBoundaryContactPieChart(boundaryShares))
        }
        return layout
    }

    private fun buildInteractivePieChart(title: String, shares: List<Pair<String, Double>>): Component {
        if (shares.isEmpty()) return Paragraph("Нет фаз для диаграммы.")
        val colorMap = grainClassColorMapForCurrentDataset()
        val radius = 74.0
        val center = 80.0
        val sortedShares = shares.filter { it.second > 0.0 }
        var startAngle = -PI / 2.0
        val paths = sortedShares.map { (phase, share) ->
            val sweep = (share * 2.0 * PI).coerceAtMost(2.0 * PI)
            val endAngle = startAngle + sweep
            val color = normalizeMaskColor(colorMap[phase])?.let { "#" + it.removePrefix("0x") }
                ?: fallbackColorForPhase(phase)
            val startX = center + radius * cos(startAngle)
            val startY = center + radius * sin(startAngle)
            val endX = center + radius * cos(endAngle)
            val endY = center + radius * sin(endAngle)
            val largeArc = if (sweep > PI) 1 else 0
            val percent = "%.1f".format(Locale.US, share * 100.0)
            val path = """
                <path d="M $center $center L $startX $startY A $radius $radius 0 $largeArc 1 $endX $endY Z"
                      fill="$color" stroke="#111" stroke-width="0.6">
                  <title>$phase — $percent%</title>
                </path>
            """.trimIndent()
            startAngle = endAngle
            path
        }
        val svg = """
            <div style="display:flex;flex-direction:column;align-items:center;gap:8px;">
              <svg viewBox="0 0 160 160" width="160" height="160" role="img" aria-label="Фазовый состав">
                ${paths.joinToString("\n")}
              </svg>
            </div>
        """.trimIndent()
        val chart = Div().apply {
            element.setProperty("innerHTML", svg)
            setWidthFull()
        }
        return VerticalLayout(
            buildChartHeader(
                title = title,
                helpText = "Круг показывает доли фаз по площади в текущей выборке. Наведите курсор на сектор, чтобы увидеть фазу и процент."
            ),
            chart
        ).apply {
            isPadding = false
            isSpacing = true
            setWidthFull()
        }
    }

    private fun buildBoundaryContactPieChart(contactShares: List<Pair<String, Double>>): Component {
        if (contactShares.isEmpty()) return Paragraph("Нет данных по контактам фаз.")
        val colorMap = grainClassColorMapForCurrentDataset()
        val hasOtherContactsSlice = contactShares.any { it.first == "Прочие контакты" }
        val radius = 74.0
        val innerRadius = radius / 2.0
        val center = 80.0
        var startAngle = -PI / 2.0
        val paths = contactShares.filter { it.second > 0.0 }.flatMap { (pairName, share) ->
            val sweep = (share * 2.0 * PI).coerceAtMost(2.0 * PI)
            val endAngle = startAngle + sweep
            val percent = "%.1f".format(Locale.US, share * 100.0)

            val outerPath = sectorPath(center, center, radius, startAngle, endAngle)
            val fragments = if (pairName == "Прочие контакты") {
                listOf(
                    """
                    <g>
                      <title>$pairName — $percent%</title>
                      <path d="$outerPath" fill="#FFFFFF" stroke="#111" stroke-width="0.6"></path>
                    </g>
                    """.trimIndent()
                )
            } else {
                val (phaseA, phaseB) = splitContactPair(pairName)
                val colorA = normalizeMaskColor(colorMap[phaseA])?.let { "#" + it.removePrefix("0x") } ?: fallbackColorForPhase(phaseA)
                val colorB = normalizeMaskColor(colorMap[phaseB])?.let { "#" + it.removePrefix("0x") } ?: fallbackColorForPhase(phaseB)
                val innerPath = sectorPath(center, center, innerRadius, startAngle, endAngle)
                listOf(
                    """
                    <g>
                      <title>$pairName — $percent%</title>
                      <path d="$outerPath" fill="$colorB" stroke="#111" stroke-width="0.6"></path>
                      <path d="$innerPath" fill="$colorA" stroke="#111" stroke-width="0.3"></path>
                    </g>
                    """.trimIndent()
                )
            }
            startAngle = endAngle
            fragments
        }

        val svg = """
            <div style="display:flex;flex-direction:column;align-items:center;gap:8px;">
              <svg viewBox="0 0 160 160" width="160" height="160" role="img" aria-label="Контакты фаз">
                ${paths.joinToString("\n")}
              </svg>
            </div>
        """.trimIndent()
        val chart = Div().apply {
            element.setProperty("innerHTML", svg)
            setWidthFull()
        }
        val helpText = if (hasOtherContactsSlice) {
            """
            Диаграмма показывает топ-8 контактов фаз по длине границы
            и агрегированный сектор «Прочие».
            Цвет от центра — первая фаза пары,
            от середины к краю — вторая.
            """.trimIndent()
        } else {
            """
            Диаграмма показывает контакты фаз по длине границы.
            Цвет от центра — первая фаза пары,
            от середины к краю — вторая.
            """.trimIndent()
        }
        return VerticalLayout(
            buildChartHeader("Контакты фаз по длине границы, %", helpText),
            chart
        ).apply {
            isPadding = false
            isSpacing = true
            setWidthFull()
        }
    }

    private fun sectorPath(
        cx: Double,
        cy: Double,
        radius: Double,
        startAngle: Double,
        endAngle: Double
    ): String {
        val startX = cx + radius * cos(startAngle)
        val startY = cy + radius * sin(startAngle)
        val endX = cx + radius * cos(endAngle)
        val endY = cy + radius * sin(endAngle)
        val largeArc = if ((endAngle - startAngle) > PI) 1 else 0
        return "M $cx $cy L $startX $startY A $radius $radius 0 $largeArc 1 $endX $endY Z"
    }

    private fun splitContactPair(pairName: String): Pair<String, String> {
        val tokens = pairName.split("|").map { it.trim() }.filter { it.isNotBlank() }
        val first = tokens.getOrNull(0) ?: pairName
        val second = tokens.getOrNull(1) ?: first
        return first to second
    }

    private fun buildBoundaryMetricsChart(
        boundaryDensityValues: List<Double>,
        entropyValues: List<Double>,
        multiPhaseCount: Int,
        totalObjectsCount: Int
    ): Component {
        val densityHistogram = histogramSvg(
            title = "Распределение плотности границ (объекты)",
            values = boundaryDensityValues
        )
        val entropyHistogram = histogramSvg(
            title = "Распределение энтропии контактов (объекты)",
            values = entropyValues
        )
        val svg = """
            <div style="display:flex;flex-direction:column;gap:6px;align-items:center;">
              $densityHistogram
              $entropyHistogram
            </div>
        """.trimIndent()
        val chart = Div().apply {
            element.setProperty("innerHTML", svg)
            setWidthFull()
        }
        return VerticalLayout(
            buildChartHeader(
                "Распределения метрик границ (многофазные объекты)",
                """
                Гистограммы строятся только по многофазным объектам: $multiPhaseCount из $totalObjectsCount.
                Однофазные объекты исключены, чтобы не сжимать распределение к нулевому столбцу.
                Плотность: доля границ в объекте.
                Энтропия контактов: 0 — один тип контакта, выше — более разнообразные контакты.
                """.trimIndent()
            ),
            chart
        ).apply {
            isPadding = false
            isSpacing = true
            setWidthFull()
        }
    }

    private fun buildChartHeader(title: String, helpText: String): Component {
        val titleLabel = Span(title).apply {
            style["font-weight"] = "600"
        }
        val helpButton = Button(VaadinIcon.QUESTION_CIRCLE_O.create()).apply {
            element.setAttribute("title", "Пояснение")
            style["padding"] = "0"
            style["min-width"] = "var(--lumo-size-m)"
            style["background"] = "transparent"
            style["box-shadow"] = "none"
            style["border"] = "none"
        }
        val popupText = Div().apply {
            text = helpText
            style["white-space"] = "pre-line"
            style["max-width"] = "320px"
            style["font-size"] = "var(--lumo-font-size-s)"
            style["padding"] = "4px"
        }
        ContextMenu(helpButton).apply {
            isOpenOnClick = true
            add(popupText)
        }
        return HorizontalLayout(titleLabel, helpButton).apply {
            isPadding = false
            isSpacing = true
            alignItems = FlexComponent.Alignment.CENTER
            style["justify-content"] = "space-between"
            setWidthFull()
        }
    }

    private fun buildBoundaryPropertyHistograms(objects: List<DatasetObject>): Component {
        val multiPhaseObjects = objects.filter { it.properties["object_phase_type"]?.trim()?.lowercase() == "multi_phase" }
        val densityValues = multiPhaseObjects.mapNotNull { it.properties["phase_boundary_density"]?.toDoubleOrNull() }
        val entropyValues = multiPhaseObjects.mapNotNull { it.properties["phase_boundary_entropy"]?.toDoubleOrNull() }
        return buildBoundaryMetricsChart(
            boundaryDensityValues = densityValues,
            entropyValues = entropyValues,
            multiPhaseCount = multiPhaseObjects.size,
            totalObjectsCount = objects.size
        )
    }

    private fun histogramSvg(title: String, values: List<Double>, bins: Int = 8): String {
        if (values.isEmpty()) {
            return """<span style="font-size:var(--lumo-font-size-xs);color:var(--lumo-secondary-text-color);">$title: нет данных</span>"""
        }
        val minValue = values.minOrNull() ?: 0.0
        val maxValue = values.maxOrNull() ?: 0.0
        if (kotlin.math.abs(maxValue - minValue) < 1e-12) {
            return """<span style="font-size:var(--lumo-font-size-xs);color:var(--lumo-secondary-text-color);">$title: все значения одинаковые (${String.format(Locale.US, "%.6f", minValue)})</span>"""
        }
        val counts = IntArray(bins)
        values.forEach { raw ->
            val normalized = ((raw - minValue) / (maxValue - minValue)).coerceIn(0.0, 0.999999)
            val bucket = (normalized * bins).toInt().coerceIn(0, bins - 1)
            counts[bucket] += 1
        }
        val maxCount = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
        val barWidth = 18
        val gap = 4
        val chartHeight = 72.0
        val chartWidth = bins * barWidth + (bins - 1) * gap
        val bars = counts.mapIndexed { i, count ->
            val height = if (count == 0) 2.0 else chartHeight * count.toDouble() / maxCount.toDouble()
            val x = i * (barWidth + gap)
            val y = chartHeight - height
            """<rect x="$x" y="$y" width="$barWidth" height="$height" fill="var(--lumo-primary-color)" rx="2"><title>bin ${i + 1}: $count</title></rect>"""
        }
        return """
            <div style="display:flex;flex-direction:column;gap:4px;align-items:center;">
              <span style="font-size:var(--lumo-font-size-xs);font-weight:600;">$title</span>
              <span style="font-size:10px;color:var(--lumo-secondary-text-color);">min=${String.format(Locale.US, "%.6f", minValue)}, max=${String.format(Locale.US, "%.6f", maxValue)}</span>
              <svg viewBox="0 0 $chartWidth 88" width="$chartWidth" height="88" role="img" aria-label="$title">
                ${bars.joinToString("\n")}
              </svg>
            </div>
        """.trimIndent()
    }

    private fun fallbackColorForPhase(phaseName: String): String {
        val hue = (phaseName.hashCode().toLong().absoluteValue % 360).toInt()
        val angle = hue.toDouble() * PI / 180.0
        val r = (128 + 80 * kotlin.math.cos(angle)).roundToInt().coerceIn(40, 230)
        val g = (128 + 80 * kotlin.math.cos(angle + 2.094)).roundToInt().coerceIn(40, 230)
        val b = (128 + 80 * kotlin.math.cos(angle + 4.188)).roundToInt().coerceIn(40, 230)
        return "#%02X%02X%02X".format(r, g, b)
    }

    private fun availableCardFieldsForPanel(): List<String> {
        val projectFields = activeObjects()
            .asSequence()
            .flatMap { it.properties.keys.asSequence() }
            .filterNot { it == "mask_color_rgb" }
            .toSet()

        val advancedFields = setOf("meta_status", "meta_confidence", "meta_analysis_date", "meta_reviewed")
        return (projectFields + advancedFields + setOf("grain_class", "phase_area_shares"))
            .sorted()
    }

    private fun syncCardFieldOrder(fields: List<String>) {
        val fieldsSet = fields.toSet()
        cardFieldOrder.removeIf { it !in fieldsSet }
        fields.forEach { field ->
            if (field !in cardFieldOrder) {
                cardFieldOrder.add(field)
            }
        }
    }

    private fun reorderCardFieldOrderBySelection() {
        val selected = cardFieldOrder.filter { it in cardVisibleFields }
        val unselected = cardFieldOrder.filterNot { it in cardVisibleFields }
        cardFieldOrder.clear()
        cardFieldOrder.addAll(selected + unselected)
    }

    private fun refreshCardFieldsPanelView() {
        if (selectedObject != null) return
        cardFieldsPanel.removeAll()
        cardFieldsPanel.add(buildCardFieldsPanel())
    }

    private data class OverlayLine(
        val text: String,
        val color: String
    )

    private fun propertySection(title: String, content: Component): Component =
        Div(
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

    private fun prettyLabel(name: String): String = when (name) {
        "area_px" -> "Площадь"
        else -> name.replace("_", " ").replaceFirstChar { it.uppercase() }
    }

    private fun panel(title: Component, content: Component, bodyScrollable: Boolean = true): VerticalLayout {
        val body = Div(content).apply {
            setSizeFull()
            style["min-height"] = "0"
            style["overflow"] = if (bodyScrollable) "auto" else "hidden"
            style["padding-top"] = "8px"
        }

        return VerticalLayout(title, body).apply {
            setSizeFull()
            isPadding = true
            isSpacing = false
            alignItems = FlexComponent.Alignment.STRETCH
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
    val imageCount: Int,
    val source: String,
    val previewUrl: String,
    val objects: List<DatasetObject>
)

private data class DatasetCollection(
    val id: String,
    var name: String,
    val objects: MutableList<DatasetObject>,
    val classColors: MutableMap<String, String>
)

private data class DatasetObject(
    val id: String,
    val name: String,
    val category: String,
    val previewUrl: String,
    val embeddings: DoubleArray = doubleArrayOf(),
    val embeddingColumnNames: List<String> = emptyList(),
    val properties: MutableMap<String, String>,
    val sourceProjectId: String? = null,
    val sourceProjectName: String? = null
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
    val embeddings: DoubleArray = doubleArrayOf(),
    val embeddingColumnNames: List<String> = emptyList(),
    val properties: Map<String, String>
)

private data class CsvEmbeddingRow(
    val x: Int,
    val y: Int,
    val embeddings: DoubleArray
)

private data class ParsedEmbeddingsCsv(
    val rowsBySourceImage: Map<String, List<CsvEmbeddingRow>>,
    val embeddingColumnNames: List<String>
) {
    companion object {
        val EMPTY = ParsedEmbeddingsCsv(
            rowsBySourceImage = emptyMap(),
            embeddingColumnNames = emptyList()
        )
    }
}

private data class ObjectClassInfo(
    val grainClass: String,
    val classColorHex: String,
    val phaseType: String
)

private data class PhaseBoundaryStats(
    val totalBoundaryEdges: Int,
    val boundaryDensity: Double,
    val pairSharesJson: String,
    val dominantPair: String,
    val boundaryEntropy: Double
) {
    companion object {
        val EMPTY = PhaseBoundaryStats(
            totalBoundaryEdges = 0,
            boundaryDensity = 0.0,
            pairSharesJson = "{}",
            dominantPair = "",
            boundaryEntropy = 0.0
        )
    }
}

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

private data class MergeResult(
    val success: Boolean,
    val message: String
)

private data class ClassColorConflict(
    val grainClass: String,
    val sourceColor: String,
    val targetColor: String
)

private enum class ConflictResolutionOption {
    KEEP_TARGET,
    KEEP_SOURCE
}

private enum class ObjectFilter {
    GRAIN_CLASS,
    STATUS,
    CONFIDENCE,
    ANALYSIS_DATE,
    REVIEWED,
    AREA
}

private enum class LeftPanelMode {
    PROJECTS,
    COLLECTIONS
}

private enum class CardBackgroundMode {
    MASKED,
    ORIGINAL_CROP
}
