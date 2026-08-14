package com.mahadi.claudesessions

import com.intellij.ide.util.PropertiesComponent

private const val AUTO_REFRESH_ENABLED = "com.mahadi.claudesessions.autoRefreshEnabled"
private const val AUTO_REFRESH_SECONDS = 30

/**
 * Plugin-local UI preferences. These stay in [PropertiesComponent] rather than the shared
 * sidecar because, unlike the session path and binary, they mean nothing to the MCP cache.
 */
object SessionSettings {

    fun isAutoRefreshEnabled(): Boolean =
        PropertiesComponent.getInstance().getBoolean(AUTO_REFRESH_ENABLED, true)

    fun setAutoRefreshEnabled(enabled: Boolean) =
        PropertiesComponent.getInstance().setValue(AUTO_REFRESH_ENABLED, enabled, true)

    fun autoRefreshSeconds(): Int = AUTO_REFRESH_SECONDS
}
