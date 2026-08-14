package com.mahadi.claudesessions

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.logger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val LOG = logger<SessionMetadataStore>()

private const val LEGACY_TITLE_PREFIX = "com.mahadi.claudesessions.title."
private const val LEGACY_PINNED_PREFIX = "com.mahadi.claudesessions.pinned."
private const val LEGACY_TAGS_PREFIX = "com.mahadi.claudesessions.tags."
private const val DEFAULT_NAME = "claude"

private data class SessionEntry(
    var title: String? = null,
    var pinned: Boolean = false,
    var tags: MutableList<String> = mutableListOf(),
    var excludeFromSync: Boolean = false,
) {
    fun isEmpty(): Boolean = title.isNullOrBlank() && !pinned && tags.isEmpty() && !excludeFromSync
}

private data class EnvironmentEntry(
    var name: String = "",
    var sessionRoot: String? = null,
    var claudeBinary: String? = null,
    var configDir: String? = null,
)

private data class TeamSyncEntry(
    var enabled: Boolean = false,
    var repoPath: String? = null,
    var repoUrl: String? = null,
    var owner: String? = null,
    var projects: MutableList<String> = mutableListOf(),
    var syncHours: MutableList<Int> = mutableListOf(),
)

private data class MetadataFile(
    var version: Int = 1,
    var sessionRoot: String? = null,
    var claudeBinary: String? = null,
    var environments: MutableList<EnvironmentEntry> = mutableListOf(),
    var activeEnvironment: String? = null,
    var teamSync: TeamSyncEntry? = null,
    var sessions: MutableMap<String, SessionEntry> = mutableMapOf(),
)

/**
 * User-authored session metadata (custom title, pin, tags) and the configured Claude
 * environments, persisted as plain JSON at `~/.claude-session-browser/metadata.json`.
 *
 * This lives outside the IDE's own settings so the Python cache/MCP server can read the
 * same file — tags set here become searchable dimensions there. Values previously stored
 * in [PropertiesComponent] are migrated across lazily, per session, on first read.
 */
object SessionMetadataStore {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val file: File =
        File(System.getProperty("user.home"), ".claude-session-browser/metadata.json")

    private var cache: MetadataFile? = null

    fun customTitle(sessionId: String): String? = synchronized(this) {
        entry(sessionId)?.title?.takeIf { it.isNotBlank() }
    }

    fun setTitle(sessionId: String, title: String) = synchronized(this) {
        mutate(sessionId) { it.title = title.trim().takeIf { trimmed -> trimmed.isNotEmpty() } }
    }

    fun clearTitle(sessionId: String) = synchronized(this) {
        mutate(sessionId) { it.title = null }
    }

    fun isPinned(sessionId: String): Boolean = synchronized(this) {
        entry(sessionId)?.pinned ?: false
    }

    fun setPinned(sessionId: String, pinned: Boolean) = synchronized(this) {
        mutate(sessionId) { it.pinned = pinned }
    }

    /** True when the user has marked this session to be kept out of any future team sync/export. */
    fun isExcludedFromSync(sessionId: String): Boolean = synchronized(this) {
        entry(sessionId)?.excludeFromSync ?: false
    }

    fun setExcludedFromSync(sessionId: String, excluded: Boolean) = synchronized(this) {
        mutate(sessionId) { it.excludeFromSync = excluded }
    }

    fun tags(sessionId: String): List<String> = synchronized(this) {
        entry(sessionId)?.tags?.toList() ?: emptyList()
    }

    fun setTags(sessionId: String, tags: List<String>) = synchronized(this) {
        val cleaned = tags
            .map { it.trim().removePrefix("#").trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
        mutate(sessionId) { it.tags = cleaned.toMutableList() }
    }

    fun forget(sessionId: String) = synchronized(this) {
        val metadata = load()
        if (metadata.sessions.remove(sessionId) != null) save(metadata)
    }

    /** Every configured Claude installation, in the order they appear in the dropdown. */
    fun environments(): List<ClaudeEnvironment> = synchronized(this) {
        load().environments.map(::toEnvironment)
    }

    /** The installation currently being browsed. Always present — one is always seeded. */
    fun activeEnvironment(): ClaudeEnvironment = synchronized(this) {
        val metadata = load()
        val active = metadata.environments.firstOrNull { it.name == metadata.activeEnvironment }
            ?: metadata.environments.firstOrNull()
        active?.let(::toEnvironment) ?: ClaudeEnvironment(DEFAULT_NAME, defaultSessionRoot(), null, null)
    }

    fun setActiveEnvironment(name: String) = synchronized(this) {
        val metadata = load()
        if (metadata.environments.none { it.name == name }) {
            LOG.warn("Cannot activate unknown Claude environment '$name'")
            return
        }
        metadata.activeEnvironment = name
        save(metadata)
    }

    /**
     * Replaces the whole environment list, keeping [activeName] active where it still
     * exists. The active entry's paths are mirrored onto the top-level `sessionRoot` /
     * `claudeBinary` keys by [save], which is what the Python cache reads.
     */
    fun replaceEnvironments(environments: List<ClaudeEnvironment>, activeName: String) = synchronized(this) {
        val entries = environments
            .filter { it.name.isNotBlank() && it.sessionRoot.isNotBlank() }
            .map { toEntry(it) }
            .toMutableList()
        if (entries.isEmpty()) {
            LOG.warn("Refusing to drop the last Claude environment")
            return
        }

        val metadata = load()
        metadata.environments = entries
        metadata.activeEnvironment = entries.firstOrNull { it.name == activeName }?.name ?: entries.first().name
        save(metadata)
    }

    /** Team knowledge-base sync settings; a disabled default when never configured. */
    fun teamSync(): TeamSyncConfig = synchronized(this) {
        val entry = load().teamSync ?: return TeamSyncConfig()
        TeamSyncConfig(
            enabled = entry.enabled,
            repoPath = entry.repoPath?.trim()?.takeIf { it.isNotEmpty() },
            repoUrl = entry.repoUrl?.trim()?.takeIf { it.isNotEmpty() },
            owner = entry.owner?.trim()?.takeIf { it.isNotEmpty() },
            projects = entry.projects.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            syncHours = entry.syncHours.filter { it in 0..23 }.distinct().sorted()
                .ifEmpty { TeamSyncConfig.DEFAULT_SYNC_HOURS },
        )
    }

    fun setTeamSync(config: TeamSyncConfig) = synchronized(this) {
        val metadata = load()
        metadata.teamSync = TeamSyncEntry(
            enabled = config.enabled,
            repoPath = config.repoPath?.trim()?.takeIf { it.isNotEmpty() },
            repoUrl = config.repoUrl?.trim()?.takeIf { it.isNotEmpty() },
            owner = config.owner?.trim()?.takeIf { it.isNotEmpty() },
            projects = config.projects.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList(),
            syncHours = config.syncHours.filter { it in 0..23 }.distinct().sorted().toMutableList(),
        )
        save(metadata)
    }

    /** Directory the active environment's transcripts are read from. */
    fun sessionRoot(): String = activeEnvironment().sessionRoot

    /** Active environment's `claude` executable, or null to auto-detect. */
    fun claudeBinary(): String? = activeEnvironment().claudeBinary

    /**
     * `CLAUDE_CONFIG_DIR` to run `claude` under, or null for the default account. Every
     * command driven on a session's behalf must carry this, otherwise a second account's
     * session is resumed by the account that cannot see it.
     */
    fun claudeConfigDir(): String? =
        activeEnvironment().configDir?.takeIf { it != defaultConfigDir() }

    fun defaultConfigDir(): String =
        File(System.getProperty("user.home"), ".claude").absolutePath

    fun defaultSessionRoot(): String = File(defaultConfigDir(), "projects").absolutePath

    private fun toEnvironment(entry: EnvironmentEntry): ClaudeEnvironment = ClaudeEnvironment(
        name = entry.name,
        sessionRoot = entry.sessionRoot?.takeIf { it.isNotBlank() } ?: defaultSessionRoot(),
        claudeBinary = entry.claudeBinary?.takeIf { it.isNotBlank() },
        configDir = entry.configDir?.takeIf { it.isNotBlank() },
    )

    private fun toEntry(environment: ClaudeEnvironment): EnvironmentEntry {
        val root = environment.sessionRoot.trim()
        return EnvironmentEntry(
            name = environment.name.trim(),
            sessionRoot = root.takeIf { it != defaultSessionRoot() },
            claudeBinary = environment.claudeBinary?.trim()?.takeIf { it.isNotEmpty() },
            configDir = environment.configDir?.trim()
                ?.takeIf { it.isNotEmpty() && it != defaultConfigDir() },
        )
    }

    /**
     * Turns a pre-environment config into a single environment named after its binary, so
     * an upgrade keeps browsing exactly what it browsed before.
     */
    private fun seedEnvironments(metadata: MetadataFile): Boolean {
        var changed = false
        if (metadata.environments.isEmpty()) {
            metadata.environments.add(
                EnvironmentEntry(
                    name = seedName(metadata.claudeBinary),
                    sessionRoot = metadata.sessionRoot,
                    claudeBinary = metadata.claudeBinary,
                )
            )
            changed = true
        }
        if (metadata.environments.none { it.name == metadata.activeEnvironment }) {
            metadata.activeEnvironment = metadata.environments.first().name
            changed = true
        }
        return changed
    }

    private fun seedName(binary: String?): String =
        binary?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it).name } ?: DEFAULT_NAME

    private fun mirrorActiveEnvironment(metadata: MetadataFile) {
        val active = metadata.environments.firstOrNull { it.name == metadata.activeEnvironment }
            ?: metadata.environments.firstOrNull()
            ?: return
        metadata.sessionRoot = active.sessionRoot
        metadata.claudeBinary = active.claudeBinary
    }

    private fun entry(sessionId: String): SessionEntry? {
        val metadata = load()
        metadata.sessions[sessionId]?.let { return it }

        val migrated = migrateLegacy(sessionId) ?: return null
        metadata.sessions[sessionId] = migrated
        save(metadata)
        return migrated
    }

    private fun mutate(sessionId: String, change: (SessionEntry) -> Unit) {
        val metadata = load()
        val existing = metadata.sessions[sessionId] ?: migrateLegacy(sessionId) ?: SessionEntry()
        change(existing)
        if (existing.isEmpty()) {
            metadata.sessions.remove(sessionId)
        } else {
            metadata.sessions[sessionId] = existing
        }
        save(metadata)
    }

    private fun migrateLegacy(sessionId: String): SessionEntry? {
        val properties = PropertiesComponent.getInstance()
        val legacyTitle = properties.getValue(LEGACY_TITLE_PREFIX + sessionId)?.takeIf { it.isNotBlank() }
        val legacyPinned = properties.getBoolean(LEGACY_PINNED_PREFIX + sessionId, false)
        val legacyTags = properties.getValue(LEGACY_TAGS_PREFIX + sessionId)
            ?.split(",")
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        if (legacyTitle == null && !legacyPinned && legacyTags.isEmpty()) return null

        LOG.info("Migrated legacy metadata for session $sessionId into metadata.json")
        return SessionEntry(title = legacyTitle, pinned = legacyPinned, tags = legacyTags.toMutableList())
    }

    private fun load(): MetadataFile {
        cache?.let { return it }

        val parsed = if (file.isFile) {
            try {
                gson.fromJson(file.readText(), MetadataFile::class.java)
            } catch (throwable: JsonSyntaxException) {
                LOG.warn("metadata.json is not valid JSON; starting from empty", throwable)
                null
            } catch (throwable: Exception) {
                LOG.warn("Could not read metadata.json; starting from empty", throwable)
                null
            }
        } else {
            null
        }

        val metadata = (parsed ?: MetadataFile()).also {
            if (it.sessions.isEmpty() && it.version == 0) it.version = 1
        }
        cache = metadata
        if (seedEnvironments(metadata)) save(metadata)
        return metadata
    }

    private fun save(metadata: MetadataFile) {
        mirrorActiveEnvironment(metadata)
        cache = metadata
        try {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(gson.toJson(metadata))
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (throwable: Exception) {
            LOG.warn("Could not write metadata.json at ${file.absolutePath}", throwable)
        }
    }
}
