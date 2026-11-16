/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.glass

import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.internal.util.catalyxx.GlassLook
import com.android.internal.util.catalyxx.GlassSettings
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.dagger.ActivityContextSingleton
import com.android.launcher3.folder.Folder
import com.android.launcher3.graphics.PathWrapper
import com.android.launcher3.util.BlurBackgroundHelper
import com.android.launcher3.util.ListenableRef
import com.android.launcher3.util.QuickstepBackgroundBlurHelper
import com.android.launcher3.util.Themes
import com.android.launcher3.util.WindowBlurState.WINDOW_BLUR_STATE
import com.android.launcher3.views.ActivityContext
import javax.inject.Inject
import javax.inject.Named
import kotlin.math.roundToInt

/**
 * Catalyxx glass on the home screen's open folders ([GlassSettings.PLACE_FOLDERS]).
 * SurfaceFlinger draws it from blur drawables of the home screen's window, so it bends the
 * wallpaper behind. Glass can't bend what its own window draws, so a folder's drawable clears the
 * home screen's icons behind it from the window instead, as it draws.
 *
 * It's the launcher's [BlurBackgroundHelper] in all of its windows. Away from the home screen,
 * and wherever Catalyxx glass is off, Google's folder blur ([QuickstepBackgroundBlurHelper])
 * carries on as before. Off wherever Android has turned blur off (Battery Saver, heat, Reduce
 * blur), as the launcher keeps its usual look then; it starts again as that changes.
 */
@ActivityContextSingleton
class LauncherGlass
@Inject
constructor(
    activityContext: ActivityContext,
    @Named(WINDOW_BLUR_STATE) private val blurState: ListenableRef<Boolean>,
    private val googleBlur: QuickstepBackgroundBlurHelper,
) : BlurBackgroundHelper() {
    private val launcher = activityContext as? Launcher
    private val context = activityContext.asContext()
    private val density = context.resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())

    private var dragLayer: View? = null

    private val folder =
        Piece(GlassSettings.PLACE_FOLDERS, FOLDER_BLUR_RADIUS_DP, fixedLight = false)

    private val bounds = Rect()

    private val settingsObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                readSettings()
                redraw()
            }
        }

    /** One of the home screen's pieces of glass: its settings, its drawable and how it shows. */
    private inner class Piece(
        val place: String,
        val blurRadiusDp: Float,
        val fixedLight: Boolean,
    ) {
        var on = false
            private set
        var shown = false
            private set
        // Whether anything changed since the last takeChanged()
        private var changed = false

        private var look: GlassLook? = null
        private var tint = Color.TRANSPARENT
        private var blurRadius = 0
        private var drawable: BackgroundBlurDrawable? = null
        private val bounds = Rect()
        // Top left, top right, bottom right, bottom left
        private val radii = FloatArray(4)
        private var joinedSides = 0
        private var alpha = 255

        /** Reads its settings, with [androidColor] as Android's own tint here. */
        fun read(androidColor: Int) {
            val resolver = context.contentResolver
            on = GlassSettings.isOn(resolver, place)
            look = GlassSettings.look(context, place, androidColor)
            tint = GlassSettings.tintColor(context, place, androidColor)
            blurRadius =
                GlassSettings.scaleBlurRadius(
                    (blurRadiusDp * density).roundToInt(),
                    GlassSettings.blurScale(resolver, place),
                )
            drawable?.let { style(it) }
        }

        private fun style(drawable: BackgroundBlurDrawable) {
            drawable.setBlurRadius(blurRadius)
            drawable.setGlass(look)
            drawable.setGlassFixedLight(fixedLight)
            // Glass with a rim brings its own color; plain blur takes the tint
            drawable.setColor(if (look != null) Color.TRANSPARENT else tint)
        }

        /**
         * Shows the piece at [rect] in the coordinates it's drawn in, with these corner radii,
         * [sides] joined (no rim there) and at [alpha] from 0 to 1. It still has to be drawn,
         * in every frame it shows in.
         */
        fun show(
            rect: Rect,
            topLeft: Float,
            topRight: Float,
            bottomRight: Float,
            bottomLeft: Float,
            sides: Int,
            alpha: Float,
        ) {
            val alpha255 = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
            if (alpha255 == 0 || rect.isEmpty) {
                hide()
                return
            }
            val d = drawable ?: create() ?: return
            if (bounds != rect) {
                bounds.set(rect)
                d.bounds = rect
                changed = true
            }
            if (
                radii[0] != topLeft ||
                    radii[1] != topRight ||
                    radii[2] != bottomRight ||
                    radii[3] != bottomLeft
            ) {
                radii[0] = topLeft
                radii[1] = topRight
                radii[2] = bottomRight
                radii[3] = bottomLeft
                d.setCornerRadius(topLeft, topRight, bottomLeft, bottomRight)
                changed = true
            }
            if (joinedSides != sides) {
                joinedSides = sides
                d.setGlassJoinedSides(sides)
                changed = true
            }
            if (this.alpha != alpha255) {
                this.alpha = alpha255
                d.alpha = alpha255
                changed = true
            }
            if (!shown) {
                shown = true
                d.setVisible(true, false)
                changed = true
            }
        }

        fun hide() {
            if (shown) {
                shown = false
                drawable?.setVisible(false, false)
                changed = true
            }
        }

        fun draw(canvas: Canvas) {
            if (shown) {
                drawable?.draw(canvas)
            }
        }

        /** Whether anything changed since this was last asked. */
        fun takeChanged(): Boolean {
            val wasChanged = changed
            changed = false
            return wasChanged
        }

        /** Lets go of the drawable, which belongs to a window that's going away. */
        fun release() {
            hide()
            drawable = null
            bounds.setEmpty()
            radii.fill(0f)
            joinedSides = 0
            alpha = 255
        }

        private fun create(): BackgroundBlurDrawable? {
            val d = dragLayer?.viewRootImpl?.createBackgroundBlurDrawable() ?: return null
            d.setVisible(false, false)
            style(d)
            // As last set: top left, top right, bottom left, bottom right
            d.setCornerRadius(radii[0], radii[1], radii[3], radii[2])
            d.setGlassJoinedSides(joinedSides)
            d.alpha = alpha
            drawable = d
            return d
        }
    }

    override fun onHomeAttached(dragLayer: View) {
        if (launcher == null || this.dragLayer != null) {
            return
        }
        this.dragLayer = dragLayer
        GlassSettings.observe(context.contentResolver, settingsObserver, *GlassSettings.KEYS)
        readSettings()
        dragLayer.invalidate()
    }

    override fun onHomeDetached() {
        dragLayer ?: return
        context.contentResolver.unregisterContentObserver(settingsObserver)
        folder.release()
        dragLayer = null
    }

    override fun onHomeVisibilityChanged(visible: Boolean) {
        if (visible) {
            // Shows it again where it still is
            redraw()
        } else {
            folder.hide()
        }
    }

    override fun prepareToOpenFolder(folder: Folder) {
        if (!isActive(this.folder)) {
            googleBlur.prepareToOpenFolder(folder)
        }
    }

    override fun drawFolderBlur(canvas: Canvas, pathWrapper: PathWrapper?, view: View) {
        if (!isActive(folder)) {
            googleBlur.drawFolderBlur(canvas, pathWrapper, view)
            return
        }
        val radius: Float
        if (pathWrapper != null) {
            // Opening or closing: the reveal's shape
            pathWrapper.bounds.roundOut(bounds)
            radius = pathWrapper.cornerRadius
        } else {
            bounds.set(0, 0, view.width, view.height)
            radius =
                (view.background as? GradientDrawable)?.cornerRadius
                    ?: Themes.getDialogCornerRadius(context)
        }
        folder.show(bounds, radius, radius, radius, radius, sides = 0, alpha = seenAlpha(view))
        folder.draw(canvas)
        if (folder.takeChanged()) {
            // Shown or changed as the folder draws, after this frame's blur went out
            view.postInvalidateOnAnimation()
        }
    }

    override fun folderCloseComplete() {
        folder.hide()
        googleBlur.folderCloseComplete()
    }

    override fun isBlurEnabled() = googleBlur.isBlurEnabled()

    override fun replacesFolderBackground() = isActive(folder)

    override fun isPopupBlurEnabled() = googleBlur.isPopupBlurEnabled()

    override fun applyPopupBlurBackground(view: View) = googleBlur.applyPopupBlurBackground(view)

    override fun getPopupBlurSurfaceColor(fallbackColor: Int) =
        googleBlur.getPopupBlurSurfaceColor(fallbackColor)

    private fun isActive(piece: Piece): Boolean =
        piece.on && launcher != null && dragLayer != null && blurState.value

    private fun readSettings() {
        // Folders have no tint of Android's own over blur; this color stands in
        folder.read(androidTint(Themes.getAttrColor(context, R.attr.folderBackgroundColor)))
        // Turned off it goes at once, though its view may not draw again for a while
        if (!folder.on) {
            folder.hide()
        }
    }

    private fun androidTint(color: Int) = (ANDROID_TINT_ALPHA shl 24) or (color and 0xffffff)

    /** Draws the open folder again where it is now. */
    private fun redraw() {
        val launcher = launcher ?: return
        Folder.getOpen(launcher)?.invalidate()
    }

    /**
     * How much [view] shows through its own and its parents' visibility and alpha, up to the
     * drag layer: SurfaceFlinger draws a blur at its drawable's own alpha, whatever its view's.
     */
    private fun seenAlpha(view: View): Float {
        val layer = dragLayer
        var alpha = 1f
        var v: View? = view
        while (v != null) {
            if (v.visibility != View.VISIBLE) {
                return 0f
            }
            alpha *= v.alpha * v.transitionAlpha
            if (v === layer) {
                break
            }
            v = v.parent as? View
        }
        return alpha
    }

    private companion object {
        // The blur behind a folder, at Blur strength 50
        const val FOLDER_BLUR_RADIUS_DP = 24f
        // How much of a stand-in color makes Android's own tint
        const val ANDROID_TINT_ALPHA = 0x99
    }
}
