package com.snapper.android.actions

import android.content.ComponentName
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import com.snapper.android.R
import com.snapper.android.types.SelectionAction

object SnapperActionRegistry {
    private const val PREFS_NAME = "settings"
    private const val KEY_ACTIONS_ORDER = "actionsOrder"
    private const val KEY_ENABLED_PREFIX = "plugin-enabled-"
    private const val ID_PREFIX = "com.jontelang.snapper3.plugin."
    private const val EXTERNAL_ID_PREFIX = "com.snapper.android.external."
    private const val EXTERNAL_ACTION_BASE = 10_000
    private const val MAX_EXTERNAL_ACTIONS = 64
    private val discoveryLock = Any()
    private val externalActionCodes = mutableMapOf<String, Int>()

    @Volatile
    private var externalActions: List<Action> = emptyList()

    @Volatile
    private var externalDiscoveryComplete = false
    private var nextExternalAction = EXTERNAL_ACTION_BASE

    internal const val ID_FLOAT = ID_PREFIX + "float"
    internal const val ID_COPY = ID_PREFIX + "copy"
    internal const val ID_SAVE = ID_PREFIX + "save"
    internal const val ID_SHARE = ID_PREFIX + "share"
    internal const val ID_QR = ID_PREFIX + "qr"
    internal const val ID_OCR = ID_PREFIX + "ocr"
    internal const val ID_IMGUR = ID_PREFIX + "imgur"
    internal const val ID_SCREENSHOT = ID_PREFIX + "screenshot"
    internal const val ID_URL_SCHEME = ID_PREFIX + "urlscheme"

    const val SCREENSHOT_ICON = SnapperActionIcons.SCREENSHOT
    const val CLOSE_ICON = SnapperActionIcons.CLOSE

    enum class ExternalSource(internal val contractValue: String) {
        CROP(SnapperActionContract.SOURCE_CROP),
        PIN(SnapperActionContract.SOURCE_PIN),
    }

    class Action private constructor(
        val id: String,
        private val titleResource: Int,
        private val menuTitleResource: Int,
        private val rawTitle: String?,
        private val rawMenuTitle: String?,
        val detail: String,
        val selectionAction: Int,
        val iconIndex: Int,
        val crop: Boolean,
        val pin: Boolean,
        val defaultEnabled: Boolean,
        val available: Boolean,
        internal val providerId: String?,
        internal val providerComponent: ComponentName?,
        val providerIcon: Bitmap?,
        val removeSnapAfterProcessing: Boolean,
    ) {
        internal constructor(
            id: String,
            titleResource: Int,
            menuTitleResource: Int,
            detail: String,
            selectionAction: Int,
            iconIndex: Int,
            crop: Boolean,
            pin: Boolean,
            defaultEnabled: Boolean,
            available: Boolean,
        ) : this(
            id, titleResource, menuTitleResource, null, null, detail,
            selectionAction, iconIndex, crop, pin, defaultEnabled, available,
            null, null, null, true,
        )

        internal constructor(
            id: String,
            title: String,
            menuTitle: String,
            detail: String,
            selectionAction: Int,
            iconIndex: Int,
            crop: Boolean,
            pin: Boolean,
            defaultEnabled: Boolean,
            available: Boolean,
            providerId: String? = null,
            providerComponent: ComponentName? = null,
            providerIcon: Bitmap? = null,
            removeSnapAfterProcessing: Boolean = true,
        ) : this(
            id, 0, 0, title, menuTitle, detail, selectionAction, iconIndex, crop, pin,
            defaultEnabled, available, providerId, providerComponent, providerIcon,
            removeSnapAfterProcessing,
        )

        val isExternal: Boolean
            get() = providerComponent != null

        fun title(context: Context): String =
            if (titleResource == 0) requireNotNull(rawTitle) else context.getString(titleResource)

        fun menuTitle(context: Context): String =
            if (menuTitleResource == 0) requireNotNull(rawMenuTitle)
            else context.getString(menuTitleResource)
    }

    private val builtInActions = listOf(
        Action(
            ID_FLOAT, R.string.action_float, R.string.action_float,
            "Crop only · create a floating pin",
            SelectionAction.FLOAT, SnapperActionIcons.SNAP,
            true, false, true, true,
        ),
        Action(
            ID_SHARE, R.string.action_share, R.string.pin_menu_share,
            "Crop and pin · send the image to another app",
            SelectionAction.SHARE, SnapperActionIcons.SHARE,
            true, true, true, true,
        ),
        Action(
            ID_COPY, R.string.action_copy, R.string.pin_menu_copy,
            "Crop and pin · copy the image",
            SelectionAction.COPY, SnapperActionIcons.COPY,
            true, true, true, true,
        ),
        Action(
            ID_SAVE, R.string.action_save, R.string.pin_menu_save,
            "Crop and pin · save to Pictures/Snapper",
            SelectionAction.SAVE, SnapperActionIcons.SAVE,
            true, true, true, true,
        ),
        Action(
            ID_QR, R.string.action_qr, R.string.action_qr,
            "Crop and pin · scan a QR code on-device",
            SelectionAction.QR, SnapperActionIcons.QR,
            true, true, true, true,
        ),
        Action(
            ID_OCR, R.string.action_ocr, R.string.action_ocr,
            "Crop and pin · recognize text on-device",
            SelectionAction.OCR, SnapperActionIcons.OCR,
            true, true, true, true,
        ),
        Action(
            ID_IMGUR, R.string.action_imgur, R.string.pin_menu_imgur,
            "Crop and pin · upload and copy the returned link",
            SelectionAction.IMGUR, SnapperActionIcons.IMGUR,
            true, true, false, true,
        ),
        Action(
            ID_SCREENSHOT, R.string.action_screenshot, R.string.action_screenshot,
            "Crop only · fixed Camera action remains available",
            SelectionAction.SCREENSHOT, SnapperActionIcons.SCREENSHOT,
            true, false, false, false,
        ),
        Action(
            ID_URL_SCHEME, "URL Scheme", "URL Scheme",
            "Crop and pin · save, wait one second, then open a configured URL",
            SelectionAction.URL_SCHEME, SnapperActionIcons.URL_SCHEME,
            true, true, false, true,
        ),
    )

    fun ensureExternalActions(context: Context) {
        if (!externalDiscoveryComplete) {
            refreshExternalActions(context)
        }
    }

    fun refreshExternalActions(context: Context) {
        val applicationContext = context.applicationContext
        synchronized(discoveryLock) {
            externalActions = discoverExternalActions(applicationContext)
            externalDiscoveryComplete = true
        }
    }

    private fun discoverExternalActions(context: Context): List<Action> {
        val packages = context.packageManager
        val query = Intent(SnapperActionContract.ACTION_PROCESS_IMAGE)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setType("image/png")
        val resolved = packages.queryIntentActivities(
            query,
            PackageManager.ResolveInfoFlags.of(
                (PackageManager.GET_META_DATA or PackageManager.MATCH_DEFAULT_ONLY).toLong(),
            ),
        )

        val candidates = ArrayList<ExternalCandidate>()
        for (resolveInfo in resolved) {
            val info = resolveInfo.activityInfo
            if (!info.exported || !info.enabled || !info.applicationInfo.enabled) {
                continue
            }
            val metadata = info.metaData ?: continue
            val providerId = normalizeProviderId(
                info.packageName,
                metadata.getString(SnapperActionContract.META_PROVIDER_ID),
            ) ?: continue
            val component = ComponentName(info.packageName, info.name)
            val title = providerTitle(packages, resolveInfo) ?: continue
            val icon = providerIcon(context, packages, resolveInfo) ?: continue
            val removeAfterProcessing = metadata.getBoolean(
                SnapperActionContract.META_REMOVE_SNAP_AFTER_PROCESSING, true,
            )
            candidates.add(
                ExternalCandidate(providerId, component, title, icon, removeAfterProcessing),
            )
        }
        candidates.sortWith(
            compareBy<ExternalCandidate> { it.providerId }
                .thenBy { it.component.flattenToString() },
        )

        val actions = ArrayList<Action>(minOf(candidates.size, MAX_EXTERNAL_ACTIONS))
        val seen = mutableSetOf<String>()
        for (candidate in candidates) {
            if (actions.size >= MAX_EXTERNAL_ACTIONS || !seen.add(candidate.providerId)) {
                continue
            }
            val selectionAction = externalActionCodes.getOrPut(candidate.providerId) {
                nextExternalAction++
            }
            actions.add(
                Action(
                    EXTERNAL_ID_PREFIX + candidate.providerId,
                    candidate.title,
                    candidate.title,
                    "Third-party action · " + candidate.providerId,
                    selectionAction,
                    SnapperActionIcons.BUILT_IN_COUNT + actions.size,
                    true,
                    true,
                    false,
                    true,
                    candidate.providerId,
                    candidate.component,
                    candidate.icon,
                    candidate.removeSnapAfterProcessing,
                ),
            )
        }
        return actions
    }

    private fun normalizeProviderId(packageName: String, rawId: String?): String? {
        if (rawId == null) return null
        val id = rawId
        if (id.isEmpty() || id.length > 160 ||
            !(id == packageName || id.startsWith("$packageName."))
        ) {
            return null
        }
        return id.takeIf { value ->
            value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' || it == ':' }
        }
    }

    private fun providerTitle(
        packages: PackageManager,
        resolveInfo: ResolveInfo,
    ): String? {
        val title = resolveInfo.loadLabel(packages).toString().trim()
        return title.takeIf { it.isNotEmpty() }?.take(80)
    }

    private fun providerIcon(
        context: Context,
        packages: PackageManager,
        resolveInfo: ResolveInfo,
    ): Bitmap? {
        val drawable = resolveInfo.loadIcon(packages) ?: return null
        val target = maxOf(
            48,
            minOf(192, Math.round(40f * context.resources.displayMetrics.density)),
        )
        val bitmap = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, target, target)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    internal val externalIcons: List<Bitmap>
        get() = externalActions.map { requireNotNull(it.providerIcon) }

    fun decodeActionIcons(context: Context): Array<Bitmap> = SnapperActionIcons.decode(context)

    fun prewarmMenuIcons(context: Context) {
        SnapperMenuIcons.prewarm(context)
    }

    fun usesDarkMenuArtwork(context: Context): Boolean =
        SnapperMenuIcons.usesDarkArtwork(context)

    fun menuIcon(context: Context, darkArtwork: Boolean, actionId: String): Bitmap? =
        SnapperMenuIcons.iconFor(context, darkArtwork, actionId)

    fun externalActionIntent(action: Action, image: Uri, source: ExternalSource): Intent {
        require(action.isExternal)
        val launch = Intent(SnapperActionContract.ACTION_PROCESS_IMAGE)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .setComponent(requireNotNull(action.providerComponent))
            .setDataAndType(image, "image/png")
            .putExtra(Intent.EXTRA_STREAM, image)
            .putExtra(
                SnapperActionContract.EXTRA_CONTRACT_VERSION,
                SnapperActionContract.CONTRACT_VERSION,
            )
            .putExtra(SnapperActionContract.EXTRA_PROVIDER_ID, requireNotNull(action.providerId))
            .putExtra(SnapperActionContract.EXTRA_SOURCE, source.contractValue)
        launch.clipData = ClipData.newRawUri("Snapper cropped image", image)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return launch
    }

    fun isExternalProviderAvailable(context: Context, action: Action?): Boolean {
        val provider = requireNotNull(action)
        require(provider.isExternal)
        return try {
            val current = context.packageManager.getActivityInfo(
                requireNotNull(provider.providerComponent),
                PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
            if (!current.exported || !current.enabled || !current.applicationInfo.enabled) {
                return false
            }
            val metadata = current.metaData
            val currentId = metadata?.getString(SnapperActionContract.META_PROVIDER_ID)
            provider.providerId == normalizeProviderId(current.packageName, currentId)
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun orderedAll(context: Context): List<Action> {
        val (enabled, disabled) = readOrder(context).partition { isEnabled(context, it) }
        return enabled + disabled
    }

    fun enabledCropActions(context: Context): List<Action> =
        readOrder(context).filter { isEnabled(context, it) && it.crop }

    fun enabledPinActions(context: Context): List<Action> =
        readOrder(context).filter { isEnabled(context, it) && it.pin }

    fun isEnabled(context: Context, action: Action): Boolean {
        if (!action.available) return false
        return preferences(context)
            .getBoolean(enabledKey(action.id), action.defaultEnabled)
    }

    fun setEnabled(context: Context, action: Action, enabled: Boolean) {
        require(action.available)
        preferences(context).edit()
            .putBoolean(enabledKey(action.id), enabled)
            .apply()
        writeOrder(context, orderedAll(context))
    }

    fun canMove(context: Context, action: Action, direction: Int): Boolean {
        require(direction == -1 || direction == 1)
        val ordered = orderedAll(context)
        val index = ordered.indexOfFirst { it.id == action.id }
        check(index >= 0)
        val target = index + direction
        return target in ordered.indices &&
            isEnabled(context, ordered[index]) == isEnabled(context, ordered[target])
    }

    fun move(context: Context, action: Action, direction: Int) {
        require(direction == -1 || direction == 1)
        val ordered = orderedAll(context).toMutableList()
        val index = ordered.indexOfFirst { it.id == action.id }
        check(index >= 0)
        val target = index + direction
        check(target in ordered.indices)
        check(isEnabled(context, ordered[index]) == isEnabled(context, ordered[target]))
        ordered[index] = ordered.set(target, ordered[index])
        writeOrder(context, ordered)
    }

    fun actionForSelectionCode(selectionAction: Int): Action? {
        return builtInActions.firstOrNull { it.selectionAction == selectionAction }
            ?: externalActions.firstOrNull { it.selectionAction == selectionAction }
    }

    private fun readOrder(context: Context): List<Action> {
        val knownActions = allActions(context)
        val saved = preferences(context).getString(KEY_ACTIONS_ORDER, null)
            ?: return knownActions
        val knownById = knownActions.associateBy { it.id }
        val ordered = saved.split(",").mapNotNull(knownById::get).toMutableList()
        val included = ordered.mapTo(mutableSetOf()) { it.id }
        ordered.addAll(knownActions.filterNot { it.id in included })
        return ordered
    }

    private fun allActions(context: Context): List<Action> {
        ensureExternalActions(context)
        return builtInActions + externalActions
    }

    private fun writeOrder(context: Context, actions: List<Action>) {
        preferences(context).edit()
            .putString(KEY_ACTIONS_ORDER, actions.joinToString(",") { it.id })
            .apply()
    }

    private fun enabledKey(id: String): String = KEY_ENABLED_PREFIX + id

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private class ExternalCandidate(
        val providerId: String,
        val component: ComponentName,
        val title: String,
        val icon: Bitmap,
        val removeSnapAfterProcessing: Boolean,
    )
}
