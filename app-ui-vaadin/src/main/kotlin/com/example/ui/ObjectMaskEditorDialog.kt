package com.example.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H4
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.menubar.MenuBar
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import java.util.UUID

class ObjectMaskEditorDialog : Dialog() {
    private val canvasHost = Div()
    private val brushColorMenuBar = MenuBar()
    private var brushOptions: List<BrushOption> = listOf(BrushOption("Фон", "#000000"))
    private var selectedBrushOption: BrushOption = brushOptions.first()
    private var canvasWrap: Div? = null

    init {
        isModal = true
        isDraggable = true
        isResizable = true
        setWidth("min(96vw, 1200px)")
        setHeight("min(92vh, 900px)")

        canvasHost.setSizeFull()

        brushColorMenuBar.style["padding"] = "0"
        brushColorMenuBar.style["margin"] = "0"
        rebuildBrushColorMenu()
        val controls = HorizontalLayout(brushColorMenuBar).apply {
            isPadding = false
            isSpacing = true
            width = "100%"
            style["align-items"] = "center"
            style["gap"] = "8px"
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

        val options = mutableListOf(BrushOption("Фон", "#000000"))
        options += phaseColors.entries
            .sortedBy { it.key }
            .mapNotNull { (phaseName, rawColor) ->
                val normalizedHex = normalizeBrushHex(rawColor) ?: return@mapNotNull null
                BrushOption(phaseName, normalizedHex)
            }
        brushOptions = options
        selectedBrushOption = brushOptions.first()
        applyBrushColor(selectedBrushOption.color)
        rebuildBrushColorMenu()

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
                    if (!dataUrl.isNullOrBlank() && dataUrl != "null" && dataUrl.startsWith("data:image/")) {
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
            holder.title = 'Объект: ' + $2;

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
            sourceCanvas.style.opacity = '0.35';
            baseMaskCanvas.style.opacity = '1';
            editMaskCanvas.style.opacity = '1';

            holder.appendChild(sourceCanvas);
            holder.appendChild(baseMaskCanvas);
            holder.appendChild(editMaskCanvas);
            this.appendChild(holder);

            const srcCtx = sourceCanvas.getContext('2d', { willReadFrequently: true });
            const baseMaskCtx = baseMaskCanvas.getContext('2d', { willReadFrequently: true });
            const editMaskCtx = editMaskCanvas.getContext('2d', { willReadFrequently: true });
            let sourceLoaded = false;
            let baseMaskLoaded = false;
            let canvasesInitialized = false;

            const initCanvases = (w, h) => {
                if (canvasesInitialized) return;
                [sourceCanvas, baseMaskCanvas, editMaskCanvas].forEach((c) => {
                    c.width = w;
                    c.height = h;
                    c.style.width = (w * scale) + 'px';
                    c.style.height = (h * scale) + 'px';
                });
                srcCtx.imageSmoothingEnabled = false;
                baseMaskCtx.imageSmoothingEnabled = false;
                editMaskCtx.imageSmoothingEnabled = false;
                canvasesInitialized = true;
            };

            const srcImg = new Image();
            srcImg.onload = () => {
                initCanvases(srcImg.width, srcImg.height);
                srcCtx.drawImage(srcImg, 0, 0, sourceCanvas.width, sourceCanvas.height);
                sourceLoaded = true;
            };
            srcImg.onerror = (event) => {
                console.error('[MaskEditor] Failed to load source image', {
                    sourceImageUrl: $0,
                    errorEvent: event
                });
                sourceLoaded = true;
            };
            srcImg.src = $0;

            const mUrl = $1;
            if (mUrl) {
                const maskImg = new Image();
                maskImg.onload = () => {
                    initCanvases(maskImg.width, maskImg.height);
                    baseMaskCtx.drawImage(maskImg, 0, 0, baseMaskCanvas.width, baseMaskCanvas.height);
                    baseMaskLoaded = true;
                };
                maskImg.onerror = (event) => {
                    console.error('[MaskEditor] Failed to load mask image', {
                        maskImageUrl: mUrl,
                        errorEvent: event
                    });
                    baseMaskLoaded = true;
                };
                maskImg.src = mUrl;
            } else {
                baseMaskLoaded = true;
            }

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
                    if (!canvasesInitialized || baseMaskCanvas.width === 0 || baseMaskCanvas.height === 0) {
                        return '';
                    }
                    const result = document.createElement('canvas');
                    result.width = baseMaskCanvas.width;
                    result.height = baseMaskCanvas.height;
                    const resultCtx = result.getContext('2d');
                    resultCtx.imageSmoothingEnabled = false;
                    resultCtx.fillStyle = '#000000';
                    resultCtx.fillRect(0, 0, result.width, result.height);
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
        val normalized = normalizeBrushHex(color) ?: "#000000"
        canvasWrap?.element?.executeJs(
            """
            if (this.__maskEditor) {
                this.__maskEditor.setBrushColor($0);
            }
            """.trimIndent(),
            normalized
        )
    }

    private fun selectBrushOption(option: BrushOption) {
        selectedBrushOption = option
        applyBrushColor(option.color)
        rebuildBrushColorMenu()
    }

    private fun brushMenuLabelComponent(option: BrushOption): HorizontalLayout {
        val colorDot = Div().apply {
            style["width"] = "12px"
            style["height"] = "12px"
            style["border-radius"] = "999px"
            style["background"] = option.color
            style["border"] = "1px solid rgba(0,0,0,0.35)"
            style["flex-shrink"] = "0"
        }
        return HorizontalLayout(colorDot, Span("Кисть ${option.title}")).apply {
            isPadding = false
            isSpacing = true
            style["align-items"] = "center"
            style["gap"] = "8px"
        }
    }

    private fun rebuildBrushColorMenu() {
        brushColorMenuBar.removeAll()
        val rootItem = brushColorMenuBar.addItem(brushMenuLabelComponent(selectedBrushOption))
        val subMenu = rootItem.subMenu
        brushOptions.forEach { option ->
            val item = subMenu.addItem(brushMenuLabelComponent(option)) {
                selectBrushOption(option)
            }
            item.isCheckable = true
            item.isChecked = option.title == selectedBrushOption.title
        }
    }

    private fun normalizeBrushHex(color: String?): String? {
        val value = color?.trim().orEmpty()
        if (value.isBlank()) return null
        return when {
            value.matches(Regex("^#[0-9a-fA-F]{6}$")) -> value
            value.matches(Regex("^[0-9a-fA-F]{6}$")) -> "#$value"
            value.matches(Regex("^0x[0-9a-fA-F]{6}$")) -> "#" + value.removePrefix("0x")
            else -> null
        }
    }

    private data class BrushOption(
        val title: String,
        val color: String
    )
}
