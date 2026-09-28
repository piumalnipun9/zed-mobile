package dev.zed.mobile

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first

/**
 * UI-only project catalog cache for a v2 device credential.
 *
 * Callers must save only [Project] instances returned from the authenticated desktop `projects()`
 * API. The cache is bound to the credential origin and device ID, never authorizes an operation,
 * and is discarded when that binding no longer matches.
 */
interface ProjectCatalogStore {
    suspend fun load(credential: DeviceCredential): List<Project>?
    suspend fun save(credential: DeviceCredential, projects: List<Project>)
    suspend fun clear()
}

/** A JVM-friendly cache implementation for view-model tests and previews. */
class InMemoryProjectCatalogStore : ProjectCatalogStore {
    private var value: CachedProjectCatalog? = null

    override suspend fun load(credential: DeviceCredential): List<Project>? = synchronized(this) {
        val cached = value ?: return@synchronized null
        if (cached.origin != credential.origin || cached.deviceId != credential.deviceId) {
            value = null
            return@synchronized null
        }
        cached.projects.toList()
    }

    override suspend fun save(credential: DeviceCredential, projects: List<Project>) {
        synchronized(this) {
            value = CachedProjectCatalog(
                origin = credential.origin,
                deviceId = credential.deviceId,
                projects = projects.toList(),
            )
        }
    }

    override suspend fun clear() {
        synchronized(this) { value = null }
    }
}

internal data class CachedProjectCatalog(
    val origin: String,
    val deviceId: String,
    val projects: List<Project>,
)

private val Context.zedProjectCatalogDataStore by preferencesDataStore(name = "zed_mobile_project_catalog")
private val cachedProjectCatalogKey = stringPreferencesKey("v2_project_catalog")

/**
 * Production cache backed by private app DataStore. It stores no credential material and validates
 * both its data shape and credential binding before making cached project paths visible to the UI.
 */
class AndroidProjectCatalogStore(context: Context) : ProjectCatalogStore {
    private val appContext = context.applicationContext

    override suspend fun load(credential: DeviceCredential): List<Project>? {
        if (!isValidCredential(credential)) {
            clear()
            return null
        }
        val encoded = appContext.zedProjectCatalogDataStore.data.first()[cachedProjectCatalogKey] ?: return null
        val cached = try {
            decodeCachedProjectCatalog(encoded)
        } catch (_: Exception) {
            clear()
            return null
        }
        if (cached.origin != credential.origin || cached.deviceId != credential.deviceId) {
            clear()
            return null
        }
        return cached.projects
    }

    override suspend fun save(credential: DeviceCredential, projects: List<Project>) {
        require(isValidCredential(credential)) { "Refusing to cache projects for an invalid device credential." }
        val encoded = encodeCachedProjectCatalog(credential, projects)
        appContext.zedProjectCatalogDataStore.edit { preferences ->
            preferences[cachedProjectCatalogKey] = encoded
        }
    }

    override suspend fun clear() {
        appContext.zedProjectCatalogDataStore.edit { preferences ->
            preferences.remove(cachedProjectCatalogKey)
        }
    }

    private fun isValidCredential(credential: DeviceCredential): Boolean = try {
        V2PairingValidation.deviceCredential(credential, isDebuggable())
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun isDebuggable(): Boolean =
        appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}

internal fun encodeCachedProjectCatalog(credential: DeviceCredential, projects: List<Project>): String {
    validateCachedProjects(projects)
    return JsonObject().apply {
        addProperty("origin", credential.origin)
        addProperty("device_id", credential.deviceId)
        add("projects", JsonArray().also { array ->
            projects.forEach { project ->
                array.add(JsonObject().apply {
                    addProperty("id", project.id)
                    addProperty("name", project.name)
                    addProperty("path", project.path)
                    addProperty("is_open", project.isOpen)
                    addProperty("kind", project.kind)
                    addProperty("connection_name", project.connectionName)
                    addProperty("opening", project.opening)
                })
            }
        })
    }.toString()
}

internal fun decodeCachedProjectCatalog(encoded: String): CachedProjectCatalog {
    require(encoded.length <= MAX_CACHED_CATALOG_CHARS) { "Cached project catalog is too large." }
    val objectValue = JsonParser.parseString(encoded).asJsonObject
    require(objectValue.keySet() == CATALOG_FIELDS) { "Cached project catalog fields are invalid." }
    val projectsElement = objectValue.get("projects")
    require(projectsElement != null && projectsElement.isJsonArray) { "Cached project catalog projects are invalid." }
    val projects = projectsElement.asJsonArray.map { value ->
        require(value.isJsonObject) { "Cached project is invalid." }
        value.asJsonObject.cachedProject()
    }
    validateCachedProjects(projects)
    return CachedProjectCatalog(
        origin = objectValue.requiredCatalogString("origin"),
        deviceId = objectValue.requiredCatalogString("device_id"),
        projects = projects,
    )
}

private fun JsonObject.cachedProject(): Project {
    val fields = keySet()
    require(fields == PROJECT_FIELDS || fields == LEGACY_PROJECT_FIELDS) {
        "Cached project fields are invalid."
    }
    return Project(
        id = requiredCatalogString("id"),
        name = requiredCatalogString("name"),
        path = requiredCatalogString("path"),
        isOpen = requiredCatalogBoolean("is_open"),
        // Legacy catalogs predate remote sources and can safely be treated as local entries.
        kind = if (fields == LEGACY_PROJECT_FIELDS) PROJECT_KIND_LOCAL else requiredProjectKind(),
        connectionName = if (fields == LEGACY_PROJECT_FIELDS) null else optionalCatalogString("connection_name"),
        opening = if (fields == LEGACY_PROJECT_FIELDS) false else requiredCatalogBoolean("opening"),
    )
}

private fun JsonObject.requiredProjectKind(): String {
    val kind = requiredCatalogString("kind")
    require(kind in PROJECT_KINDS) { "Cached project kind is invalid." }
    return kind
}

private fun JsonObject.requiredCatalogBoolean(name: String): Boolean {
    val value = get(name)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
        "Cached project catalog field '$name' is invalid."
    }
    return value.asBoolean
}

private fun JsonObject.optionalCatalogString(name: String): String? {
    val value = get(name) ?: return null
    if (value.isJsonNull) return null
    require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
        "Cached project catalog field '$name' is invalid."
    }
    val string = value.asString
    require(string.length <= MAX_CACHED_PROJECT_FIELD_CHARS) {
        "Cached project catalog field '$name' is too large."
    }
    return string
}

private fun JsonObject.requiredCatalogString(name: String): String {
    val value = get(name)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
        "Cached project catalog field '$name' is invalid."
    }
    val string = value.asString
    require(string.length <= MAX_CACHED_PROJECT_FIELD_CHARS) {
        "Cached project catalog field '$name' is too large."
    }
    return string
}

private fun validateCachedProjects(projects: List<Project>) {
    require(projects.size <= MAX_CACHED_PROJECTS) { "Too many projects to cache." }
    require(projects.map(Project::id).toSet().size == projects.size) { "Project cache contains duplicate IDs." }
    projects.forEach { project ->
        require(project.id.isNotEmpty()) { "Project IDs must not be empty." }
        require(project.id.length <= MAX_CACHED_PROJECT_FIELD_CHARS) { "Project ID is too large." }
        require(project.name.length <= MAX_CACHED_PROJECT_FIELD_CHARS) { "Project name is too large." }
        require(project.path.length <= MAX_CACHED_PROJECT_FIELD_CHARS) { "Project path is too large." }
        require(project.kind in PROJECT_KINDS) { "Project kind is invalid." }
        require((project.connectionName?.length ?: 0) <= MAX_CACHED_PROJECT_FIELD_CHARS) {
            "Project connection name is too large."
        }
    }
}

/** Initialized by [ZedMobileApplication] before a production view model uses project caching. */
object ProjectCatalogStores {
    @Volatile
    private var productionStore: ProjectCatalogStore? = null

    fun initialize(context: Context) {
        if (productionStore == null) {
            synchronized(this) {
                if (productionStore == null) productionStore = AndroidProjectCatalogStore(context)
            }
        }
    }

    internal fun production(): ProjectCatalogStore = checkNotNull(productionStore) {
        "ProjectCatalogStores has not been initialized. Use an injected ProjectCatalogStore in JVM tests."
    }
}

/** Delays Android access so v1-only JVM tests can still construct [ZedViewModel]. */
class ApplicationProjectCatalogStore : ProjectCatalogStore {
    override suspend fun load(credential: DeviceCredential): List<Project>? =
        ProjectCatalogStores.production().load(credential)

    override suspend fun save(credential: DeviceCredential, projects: List<Project>) =
        ProjectCatalogStores.production().save(credential, projects)

    override suspend fun clear() = ProjectCatalogStores.production().clear()
}

private val CATALOG_FIELDS = setOf("origin", "device_id", "projects")
private val LEGACY_PROJECT_FIELDS = setOf("id", "name", "path", "is_open")
private val PROJECT_FIELDS = LEGACY_PROJECT_FIELDS + setOf("kind", "connection_name", "opening")
private const val MAX_CACHED_CATALOG_CHARS = 1_000_000
private const val MAX_CACHED_PROJECTS = 2_048
private const val MAX_CACHED_PROJECT_FIELD_CHARS = 8_192
