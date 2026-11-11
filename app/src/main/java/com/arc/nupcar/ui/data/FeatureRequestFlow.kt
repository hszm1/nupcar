package com.arc.nupcar.ui.data

import com.arc.nupcar.explorer.ApiOperation

internal class FeatureRequestFlow {
    private var pendingInitialReadFeatureId: String? = null

    fun beginEntry(featureId: String) {
        pendingInitialReadFeatureId = featureId
    }

    fun shouldRunAutomaticRead(
        featureId: String,
        operation: ApiOperation,
        succeeded: Boolean
    ): Boolean {
        if (operation != ApiOperation.CAPABILITY) return false
        if (pendingInitialReadFeatureId != featureId) return false
        pendingInitialReadFeatureId = null
        return succeeded
    }

    fun clear() {
        pendingInitialReadFeatureId = null
    }
}
