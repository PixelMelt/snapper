package com.snapper.android.actions

internal object SnapperActionContract {
    const val ACTION_PROCESS_IMAGE = "com.snapper.android.action.PROCESS_IMAGE"

    const val META_PROVIDER_ID = "com.snapper.android.action_provider.ID"

    const val META_REMOVE_SNAP_AFTER_PROCESSING =
        "com.snapper.android.action_provider.REMOVE_SNAP_AFTER_PROCESSING"

    const val EXTRA_CONTRACT_VERSION = "com.snapper.android.extra.ACTION_CONTRACT_VERSION"

    const val EXTRA_PROVIDER_ID = "com.snapper.android.extra.ACTION_PROVIDER_ID"

    const val EXTRA_SOURCE = "com.snapper.android.extra.ACTION_SOURCE"

    const val SOURCE_CROP = "crop"
    const val SOURCE_PIN = "pin"
    const val CONTRACT_VERSION = 1
}
