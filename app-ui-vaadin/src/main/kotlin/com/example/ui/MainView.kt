package com.example.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.datepicker.DatePicker
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.*
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
import com.vaadin.flow.dom.Style
import com.vaadin.flow.server.StreamResource
import com.vaadin.flow.router.Route
import com.fasterxml.jackson.databind.ObjectMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.ArrayDeque
import java.security.MessageDigest
import java.util.stream.Collectors
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import javax.imageio.ImageIO

@Route("")
class MainView : VerticalLayout() {
    companion object {
        private const val OBJECT_PAGE_SIZE = 200
    }

    private val datasetsRoot: Path = Path.of("/siams/images")
    private val cacheRoot: Path = Path.of("/siams/chache")
    private val projects = mutableListOf<DatasetProject>()

    private val projectHeader = H4("Проекты")
    private val objectHeader = H4("Объекты")
    private val projectList = com.vaadin.flow.component.html.Div()
    private val objectGallery = com.vaadin.flow.component.html.Div()
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

        val leftPanel = panel(projectHeaderWithActions(), projectList)
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
        selectedObjectCard = null
        visibleObjectLimit = minOf(OBJECT_PAGE_SIZE, project.objects.size)
        renderProjects()
        objectHeader.text = "Объекты (${project.objects.size})"
        renderObjects(project.objects)
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
        projects.forEach { project ->
            projectList.add(projectCard(project, project == selectedProject) { selectProject(project) })
        }
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

    private fun openImportDialog() {
        val availableDatasets = listDatasetDirectories()
        if (availableDatasets.isEmpty()) {
            Notification.show("В каталоге $datasetsRoot нет датасетов для импорта.", 3500, Notification.Position.MIDDLE)
                .addThemeVariants(NotificationVariant.LUMO_WARNING)
            return
        }

        val datasetSelector = ComboBox<String>("Каталог датасета").apply {
            setItems(availableDatasets)
            isAllowCustomValue = false
            isClearButtonVisible = true
            placeholder = "Выберите каталог"
            setWidthFull()
        }

        val dialog = Dialog().apply {
            headerTitle = "Импорт проекта"
            add(VerticalLayout(datasetSelector).apply {
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
            val selectedFolder = datasetSelector.value
            if (selectedFolder.isNullOrBlank()) {
                Notification.show("Сначала выберите каталог датасета.", 2500, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_CONTRAST)
                return@addClickListener
            }
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
                    importProjectFromDataset(selectedFolder, cancelRequested) { progress ->
                        currentUi.access {
                            progressText.text = progress.message
                            progressBar.isIndeterminate = progress.indeterminate
                            if (!progress.indeterminate && progress.total > 0) {
                                progressBar.value = progress.current.toDouble() / progress.total.toDouble()
                            }
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

    private fun listDatasetDirectories(): List<String> {
        if (!Files.isDirectory(datasetsRoot)) {
            return emptyList()
        }
        Files.walk(datasetsRoot).use { stream ->
            return stream
                .filter { Files.isDirectory(it) && it != datasetsRoot }
                .filter { dir ->
                    Files.list(dir).use { files ->
                        files.anyMatch { file ->
                            val name = file.fileName.toString()
                            name.startsWith("img-") || name.startsWith("msk_rgb-")
                        }
                    }
                }
                .sorted()
                .map { datasetsRoot.relativize(it).toString().replace('\\', '/') }
                .collect(Collectors.toList())
        }
    }

    private fun importProjectFromDataset(
        datasetDirectoryName: String,
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
            val generated = commonSuffixes.flatMapIndexed { index, suffix ->
                throwIfCancelled(cancelRequested)
                onProgress(ImportProgress("Обработка масок: ${index + 1}/${commonSuffixes.size}", index, commonSuffixes.size, false))
                extractObjectsFromPairToCache(
                    datasetDirectoryName = datasetDirectoryName,
                    suffix = suffix,
                    sourceImagePath = rawBySuffix.getValue(suffix),
                    maskImagePath = maskBySuffix.getValue(suffix),
                    legend = legend,
                    cacheDir = cacheDir,
                    cancelRequested = cancelRequested
                )
            }
            onProgress(ImportProgress("Сохранение кэша…", commonSuffixes.size, commonSuffixes.size, false))
            saveCachedObjects(manifestPath, generated)
            generated
        }

        onProgress(ImportProgress("Подготовка проекта…", 1, 1, false))
        val projectPreview = rawBySuffix[commonSuffixes.first()] ?: rgbMasks.first()
        val objectsForUi = resolvedObjects.map { cached ->
            DatasetObject(
                id = cached.id,
                name = cached.name,
                category = cached.category,
                previewUrl = cacheDir.resolve(cached.previewFileName).toString(),
                properties = cached.properties.toMutableMap()
            )
        }

        return DatasetProject(
            id = "dataset-$datasetDirectoryName",
            name = datasetDirectoryName,
            type = "Импорт из /siams/images",
            source = datasetPath.toString(),
            previewUrl = projectPreview.toString(),
            objects = objectsForUi
        )
    }

    private fun resolveProjectResources(project: DatasetProject): DatasetProject {
        val resolvedPreview = resolvePreviewUrl(project.previewUrl)
        val resolvedObjects = project.objects.map { obj ->
            obj.copy(previewUrl = resolvePreviewUrl(obj.previewUrl))
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

                val component = collectConnectedComponent(mask, x, y, color, visited)
                if (component.points.isEmpty()) continue

                grainCounter += 1
                val grainClass = legend[color] ?: "Unknown(0x${color.toString(16).padStart(6, '0').uppercase()})"
                val previewFileName = "grain-$suffix-$grainCounter.png"
                val previewPath = cacheDir.resolve(previewFileName)
                Files.write(previewPath, buildMaskedCrop(source, component))

                objects += CachedDatasetObject(
                    id = "$datasetDirectoryName-$suffix-$grainCounter",
                    name = "Зерно $suffix #$grainCounter",
                    category = "OreGrain",
                    previewFileName = previewFileName,
                    properties = mapOf(
                        "dataset" to datasetDirectoryName,
                        "source_image_file" to sourceImagePath.fileName.toString(),
                        "mask_rgb_file" to maskImagePath.fileName.toString(),
                        "grain_class" to grainClass,
                        "mask_color_rgb" to "0x${color.toString(16).padStart(6, '0').uppercase()}"
                    )
                )
            }
        }

        return objects
    }

    private fun collectConnectedComponent(
        mask: BufferedImage,
        startX: Int,
        startY: Int,
        componentColor: Int,
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
            if (mask.rgbNoAlpha(x, y) != componentColor) return
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

private data class ImportProgress(
    val message: String,
    val current: Int,
    val total: Int,
    val indeterminate: Boolean
)

private fun demoProjects(): List<DatasetProject> = emptyList()
