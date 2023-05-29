@file:OptIn(ExperimentalSerializationApi::class)

package com.github.mvysny.shepherd.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * The Project ID must:
 *
 * * contain at most 54 characters
 * * contain only lowercase alphanumeric characters or '-'
 * * start with an alphanumeric character
 * * end with an alphanumeric character
 */
@JvmInline
@Serializable
public value class ProjectId(public val id: String) : Comparable<ProjectId> {
    init {
        require(idValidator.matches(id)) { "The ID must contain at most 54 characters, it must contain only lowercase alphanumeric characters or '-', it must start and end with an alphanumeric character" }
    }
    private companion object {
        private val idValidator = "[a-z0-9][a-z0-9\\-]{0,52}[a-z0-9]".toRegex()
    }

    override fun compareTo(other: ProjectId): Int = id.compareTo(other.id)
}

/**
 * Information about the project owner, how to reach him in case the project needs to be modified.
 * Jenkins may send notification emails about the failed builds to [email].
 * @property name e.g. `Martin Vysny`
 * @property email e.g. `mavi@vaadin.com`
 */
@Serializable
public data class ProjectOwner(
    val name: String,
    val email: String
) {
    override fun toString(): String = "$name <$email>"
}

/**
 * Resources the app needs.
 * @property memoryMb max memory in megabytes
 * @property cpu max CPU cores to use. 1 means 1 CPU core to be used.
 */
@Serializable
public data class Resources(
    val memoryMb: Int,
    val cpu: Float
) {
    init {
        require(memoryMb >= 32) { "Give the process at least 32mb: $memoryMb" }
        require(cpu > 0) { "cpu: must be greater than 0 but got $cpu" }
    }
    public companion object {
        @JvmStatic
        public val defaultRuntimeResources: Resources = Resources(memoryMb = 256, cpu = 1f)
        @JvmStatic
        public val defaultBuildResources: Resources = Resources(memoryMb = 2048, cpu = 2f)
    }
}

/**
 * How to build the project.
 * @property resources how many resources to allocate for the build. Passed via `BUILD_MEMORY` and `CPU_QUOTA` env variables to `shepherd-build`.
 * @property buildArgs optional build args, passed as `--build-arg name="value"` to `docker build` via the `BUILD_ARGS` env variable passed to `shepherd-build`.
 * @property dockerFile if not null, we build off this dockerfile instead of the default `Dockerfile`. Passed via `DOCKERFILE` env variable to `shepherd-build`.
 */
@Serializable
public data class Build(
    val resources: Resources,
    val buildArgs: Map<String, String> = mapOf(),
    val dockerFile: String? = null
)

/**
 * A git repository.
 * @property url the git repository from where the project comes from, e.g. `https://github.com/mvysny/vaadin-boot-example-gradle`
 * @property branch usually `master` or `main`
 */
@Serializable
public data class GitRepo(
    val url: String,
    val branch: String
)

/**
 * Runtime project config.
 * @property resources what resources the project needs for running
 * @property envVars environment variables, e.g. `SPRING_DATASOURCE_URL` to `jdbc:postgresql://liukuri-postgres:5432/postgres`
 */
@Serializable
public data class ProjectRuntime(
    val resources: Resources,
    val envVars: Map<String, String> = mapOf()
)

/**
 * @property id the project ID, must be unique.
 * @property description any additional vital information about the project
 * @property gitRepo the git repository from where the project comes from
 * @property owner the project owner: a contact person responsible for the project.
 * @property runtime what resources the project needs for running
 * @property build build info
 * @property additionalServices any additional services the project needs, e.g. additional databases and such.
 */
@Serializable
public data class Project(
    val id: ProjectId,
    val description: String,
    val gitRepo: GitRepo,
    val owner: ProjectOwner,
    val runtime: ProjectRuntime,
    val build: Build,
    val publication: Publication = Publication(),
    val additionalServices: Set<Service> = setOf()
) {
    /**
     * Returns URLs on which this project runs (can be browsed to). E.g. for `vaadin-boot-example-gradle`
     * on the `v-herd.eu` [host], this returns `https://v-herd.eu/vaadin-boot-example-gradle`.
     */
    public fun getPublishedURLs(host: String): List<String> =
        listOf("https://$host/${id.id}") + publication.additionalDomains.map { "https://$it" }

    public companion object {
        /**
         * Loads [Project] from given JSON [file]. Fails if the file doesn't exist.
         */
        @JvmStatic
        public fun loadFromFile(file: Path): Project =
            file.inputStream().buffered().use { stream -> Json.decodeFromStream(stream) }

        private val jsonPrettyPrint = Json { prettyPrint = true }
        private fun getJson(prettyPrint: Boolean): Json = if (prettyPrint) jsonPrettyPrint else Json

        @JvmStatic
        public fun fromJson(json: String): Project = Json.decodeFromString(json)
    }

    /**
     * Saves this project as a JSON to given [file]. Pretty-prints the JSON by default;
     * override via the [prettyPrint] parameter.
     */
    @JvmOverloads
    public fun saveToFile(file: Path, prettyPrint: Boolean = true) {
        file.outputStream().buffered().use { stream -> getJson(prettyPrint).encodeToStream(this, stream) }
    }

    @JvmOverloads
    public fun toJson(prettyPrint: Boolean = false): String = getJson(prettyPrint).encodeToString(this)
}

@Serializable
public enum class ServiceType {
    /**
     * A PostgreSQL database. Use the following values to access the database:
     * * JDBC URI: `jdbc:postgresql://postgres-service:5432/postgres`
     * * username: `postgres`
     * * password: `mysecretpassword`.
     * The database is only accessible by your project; no other project has access to the database.
     */
    Postgres
}

@Serializable
public data class Service(
    val type: ServiceType
)

/**
 * The project publication.
 * @property publishOnMainDomain if true (the default), the project will be published on the main domain as well.
 * Say the main domain is `v-herd.eu`, then the project will be accessible at `v-herd.eu/PROJECT_ID`.
 * @property https only affects [additionalDomains]; if the project is published on the main domain then it always uses https.
 * Defaults to true. If false, the project is published on [additionalDomains] via plain http.
 * Useful e.g. when CloudFlare unwraps https for us.
 * @property additionalDomains additional domains to publish to project at. Must not contain the main domain.
 * E.g. `yourproject.com`.
 */
@Serializable
public data class Publication(
    val publishOnMainDomain: Boolean = true,
    val https: Boolean = true,
    val additionalDomains: Set<String> = setOf()
)
