package com.example.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H4
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.select.Select
import com.vaadin.flow.component.textfield.TextField
import java.util.UUID

class ObjectMaskEditorDialog : Dialog() {
    private val canvasHost = Div()
    private val brushColorSelect = Select<String>()
    private val customColorField = TextField("Цвет (hex)")
    private var currentCanvasId: String = ""

    init {
        isModal = true
        isDraggable = true
        isResizable = true
        setWidth("min(96vw, 1200px)")
        setHeight("min(92vh, 900px)")

        canvasHost.setSizeFull()

        brushColorSelect.label = "Кисть"
        brushColorSelect.setItems("Фон")
        brushColorSelect.value = "Фон"

        customColorField.placeholder = "#RRGGBB"
        customColorField.value = "#000000"
        customColorField.isClearButtonVisible = false
        customColorField.addValueChangeListener {
            if (brushColorSelect.value == "Пользовательский") {
                applyBrushColor(customColorField.value)
            }
        }

        brushColorSelect.addValueChangeListener {
            val value = it.value ?: "Фон"
            val color = when {
                value == "Фон" -> "#000000"
                value == "Пользовательский" -> customColorField.value.ifBlank { "#000000" }
                value.contains("#") -> value.substringAfter("#").let { hex -> "#$hex" }
                else -> "#000000"
            }
            applyBrushColor(color)
        }

        val controls = HorizontalLayout(brushColorSelect, customColorField).apply {
            isPadding = false
            isSpacing = true
            width = "100%"
        }

        val content = VerticalLayout(
            H4("Редактор масок"),
            controls,
            canvasHost
        ).apply {
            setSizeFull()
            isPadding = false
            isSpacing = true
            setFlexGrow(1.0, canvasHost)
        }

        add(content)
    }

    fun openEditor(
        objectName: String,
        sourceImageUrl: String,
        maskImageUrl: String?,
        phaseColors: Map<String, String>,
        onSave: (maskDataUrl: String) -> Unit
    ) {
        currentCanvasId = "mask-editor-${UUID.randomUUID()}"
        canvasHost.removeAll()

        val canvasWrap = Div().apply {
            id = currentCanvasId
            style["position"] = "relative"
            style["display"] = "inline-block"
            style["border"] = "1px solid var(--lumo-contrast-20pct)"
            style["background"] = "#111"
        }
        canvasHost.add(canvasWrap)

        val options = mutableListOf("Фон")
        options += phaseColors.entries
            .sortedBy { it.key }
            .map { "${it.key} #${it.value.removePrefix("0x")}" }
        options += "Пользовательский"
        brushColorSelect.setItems(options)
        brushColorSelect.value = options.first()

        footer.removeAll()
        footer.add(
            Button("Отмена") { close() },
            Button("Сохранить") {
                element.executeJs(
                    """
                    const root = this.querySelector('#' + $0);
                    if (!root || !root.__maskEditor) return null;
                    return root.__maskEditor.exportMask();
                    """.trimIndent(),
                    currentCanvasId
                ).then(String::class.java) { dataUrl ->
                    if (!dataUrl.isNullOrBlank()) {
                        onSave(dataUrl)
                    }
                    close()
                }
            }
        )

        open()
        setupEditor(objectName, sourceImageUrl, maskImageUrl)
    }

    private fun setupEditor(objectName: String, sourceImageUrl: String, maskImageUrl: String?) {
        element.executeJs(
            """
            const container = this.querySelector('#' + $0);
            if (!container) return;
            container.innerHTML = '';

            const scale = 4;
            const holder = document.createElement('div');
            holder.style.position = 'relative';
            holder.style.display = 'inline-block';
            holder.style.transformOrigin = 'top left';
            holder.title = 'Объект: ' + $3;

            const sourceCanvas = document.createElement('canvas');
            const maskCanvas = document.createElement('canvas');
            [sourceCanvas, maskCanvas].forEach((c, idx) => {
                c.style.position = idx === 0 ? 'relative' : 'absolute';
                c.style.left = '0';
                c.style.top = '0';
                c.style.imageRendering = 'pixelated';
                c.style.pointerEvents = idx === 0 ? 'none' : 'auto';
            });
            maskCanvas.style.opacity = '0.6';

            holder.appendChild(sourceCanvas);
            holder.appendChild(maskCanvas);
            container.appendChild(holder);

            const srcCtx = sourceCanvas.getContext('2d', { willReadFrequently: true });
            const maskCtx = maskCanvas.getContext('2d', { willReadFrequently: true });

            const srcImg = new Image();
            srcImg.crossOrigin = 'anonymous';
            srcImg.onload = () => {
                const w = srcImg.width;
                const h = srcImg.height;
                [sourceCanvas, maskCanvas].forEach((c) => {
                    c.width = w;
                    c.height = h;
                    c.style.width = (w * scale) + 'px';
                    c.style.height = (h * scale) + 'px';
                });
                srcCtx.imageSmoothingEnabled = false;
                maskCtx.imageSmoothingEnabled = false;
                srcCtx.drawImage(srcImg, 0, 0, w, h);

                const mUrl = $2;
                if (mUrl) {
                    const maskImg = new Image();
                    maskImg.crossOrigin = 'anonymous';
                    maskImg.onload = () => maskCtx.drawImage(maskImg, 0, 0, w, h);
                    maskImg.src = mUrl;
                }
            };
            srcImg.src = $1;

            let draw = false;
            let brushColor = '#000000';

            const drawPx = (ev) => {
                const rect = maskCanvas.getBoundingClientRect();
                const x = Math.floor((ev.clientX - rect.left) / scale);
                const y = Math.floor((ev.clientY - rect.top) / scale);
                if (x < 0 || y < 0 || x >= maskCanvas.width || y >= maskCanvas.height) return;
                maskCtx.fillStyle = brushColor;
                maskCtx.fillRect(x, y, 1, 1);
            };

            maskCanvas.addEventListener('mousedown', (ev) => { draw = true; drawPx(ev); });
            window.addEventListener('mouseup', () => { draw = false; });
            maskCanvas.addEventListener('mousemove', (ev) => { if (draw) drawPx(ev); });

            container.__maskEditor = {
                setBrushColor: (color) => { brushColor = color || '#000000'; },
                exportMask: () => maskCanvas.toDataURL('image/png')
            };
            """.trimIndent(),
            currentCanvasId,
            sourceImageUrl,
            maskImageUrl,
            objectName
        )
    }

    private fun applyBrushColor(color: String) {
        val normalized = when {
            color.matches(Regex("^#[0-9a-fA-F]{6}$")) -> color
            color.matches(Regex("^[0-9a-fA-F]{6}$")) -> "#$color"
            else -> "#000000"
        }
        element.executeJs(
            """
            const root = this.querySelector('#' + $0);
            if (root && root.__maskEditor) {
                root.__maskEditor.setBrushColor($1);
            }
            """.trimIndent(),
            currentCanvasId,
            normalized
        )
    }
}
