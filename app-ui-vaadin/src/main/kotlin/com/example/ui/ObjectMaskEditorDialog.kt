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
    private var canvasWrap: Div? = null

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
        canvasHost.removeAll()

        val currentCanvasWrap = Div().apply {
            setId("mask-editor-${UUID.randomUUID()}")
            style["position"] = "relative"
            style["display"] = "inline-block"
            style["border"] = "1px solid var(--lumo-contrast-20pct)"
            style["background"] = "#111"
        }
        canvasWrap = currentCanvasWrap
        canvasHost.add(currentCanvasWrap)

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
                val wrap = canvasWrap ?: return@Button
                wrap.element.executeJs(
                    """
                    if (!this.__maskEditor) return null;
                    return this.__maskEditor.exportMask();
                    """.trimIndent()
                ).then(String::class.java) { dataUrl ->
                    if (!dataUrl.isNullOrBlank()) {
                        onSave(dataUrl)
                    }
                    close()
                }
            }
        )

        open()
        setupEditor(currentCanvasWrap, objectName, sourceImageUrl, maskImageUrl)
    }

    private fun setupEditor(container: Div, objectName: String, sourceImageUrl: String, maskImageUrl: String?) {
        container.element.executeJs(
            """
            this.innerHTML = '';

            const scale = 4;
            const holder = document.createElement('div');
            holder.style.position = 'relative';
            holder.style.display = 'inline-block';
            holder.style.transformOrigin = 'top left';
            holder.title = 'Объект: ' + $3;

            const sourceCanvas = document.createElement('canvas');
            const baseMaskCanvas = document.createElement('canvas');
            const editMaskCanvas = document.createElement('canvas');
            [sourceCanvas, baseMaskCanvas, editMaskCanvas].forEach((c, idx) => {
                c.style.position = idx === 0 ? 'relative' : 'absolute';
                c.style.left = '0';
                c.style.top = '0';
                c.style.imageRendering = 'pixelated';
                c.style.pointerEvents = idx === 2 ? 'auto' : 'none';
            });
            baseMaskCanvas.style.opacity = '0.6';
            editMaskCanvas.style.opacity = '0.6';

            holder.appendChild(sourceCanvas);
            holder.appendChild(baseMaskCanvas);
            holder.appendChild(editMaskCanvas);
            this.appendChild(holder);

            const srcCtx = sourceCanvas.getContext('2d', { willReadFrequently: true });
            const baseMaskCtx = baseMaskCanvas.getContext('2d', { willReadFrequently: true });
            const editMaskCtx = editMaskCanvas.getContext('2d', { willReadFrequently: true });
            let sourceLoaded = false;
            let baseMaskLoaded = false;

            const srcImg = new Image();
            srcImg.onload = () => {
                const w = srcImg.width;
                const h = srcImg.height;
                [sourceCanvas, baseMaskCanvas, editMaskCanvas].forEach((c) => {
                    c.width = w;
                    c.height = h;
                    c.style.width = (w * scale) + 'px';
                    c.style.height = (h * scale) + 'px';
                });
                srcCtx.imageSmoothingEnabled = false;
                baseMaskCtx.imageSmoothingEnabled = false;
                editMaskCtx.imageSmoothingEnabled = false;
                srcCtx.drawImage(srcImg, 0, 0, w, h);
                sourceLoaded = true;

                const mUrl = $2;
                if (mUrl) {
                    const maskImg = new Image();
                    maskImg.onload = () => {
                        baseMaskCtx.drawImage(maskImg, 0, 0, w, h);
                        baseMaskLoaded = true;
                    };
                    maskImg.onerror = () => { baseMaskLoaded = true; };
                    maskImg.src = mUrl;
                } else {
                    baseMaskLoaded = true;
                }
            };
            srcImg.onerror = () => {
                sourceLoaded = true;
                baseMaskLoaded = true;
            };
            srcImg.src = $1;

            let draw = false;
            let brushColor = '#000000';

            const drawPx = (ev) => {
                const rect = editMaskCanvas.getBoundingClientRect();
                const x = Math.floor((ev.clientX - rect.left) / scale);
                const y = Math.floor((ev.clientY - rect.top) / scale);
                if (x < 0 || y < 0 || x >= editMaskCanvas.width || y >= editMaskCanvas.height) return;
                editMaskCtx.fillStyle = brushColor;
                editMaskCtx.fillRect(x, y, 1, 1);
            };

            editMaskCanvas.addEventListener('mousedown', (ev) => { draw = true; drawPx(ev); });
            window.addEventListener('mouseup', () => { draw = false; });
            editMaskCanvas.addEventListener('mousemove', (ev) => { if (draw) drawPx(ev); });

            this.__maskEditor = {
                setBrushColor: (color) => { brushColor = color || '#000000'; },
                exportMask: async () => {
                    await new Promise((resolve) => {
                        const checkReady = () => {
                            if (sourceLoaded && baseMaskLoaded) {
                                resolve();
                            } else {
                                requestAnimationFrame(checkReady);
                            }
                        };
                        checkReady();
                    });
                    const result = document.createElement('canvas');
                    result.width = baseMaskCanvas.width;
                    result.height = baseMaskCanvas.height;
                    const resultCtx = result.getContext('2d');
                    resultCtx.imageSmoothingEnabled = false;
                    resultCtx.drawImage(baseMaskCanvas, 0, 0);
                    resultCtx.drawImage(editMaskCanvas, 0, 0);
                    return result.toDataURL('image/png');
                }
            };
            """.trimIndent(),
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
        canvasWrap?.element?.executeJs(
            """
            if (this.__maskEditor) {
                this.__maskEditor.setBrushColor($0);
            }
            """.trimIndent(),
            normalized
        )
    }
}
