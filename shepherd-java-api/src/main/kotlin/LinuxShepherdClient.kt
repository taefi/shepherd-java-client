package com.github.mvysny.shepherd.api

import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.*

/**
 * Interacts with the actual shepherd system.
 * @property etcShepherdPath the root shepherd path, `/etc/shepherd`
 */
public class LinuxShepherdClient @JvmOverloads constructor(
    private val kubernetes: SimpleKubernetesClient = SimpleKubernetesClient(),
    private val etcShepherdPath: Path = Path("/etc/shepherd"),
    private val jenkins: SimpleJenkinsClient = SimpleJenkinsClient()
) : ShepherdClient {
    /**
     * Project config JSONs are stored here. Defaults to `/etc/shepherd/projects`.
     */
    private val projectConfigFolder = ProjectConfigFolder(etcShepherdPath / "projects")

    override fun getAllProjectIDs(): List<ProjectId> = projectConfigFolder.getAllProjects()

    override fun getAllProjects(ownerEmail: String?): List<ProjectView> {
        var projects = getAllProjectIDs()
            .map { getProjectInfo(it) }
        if (ownerEmail != null) {
            projects = projects.filter { it.owner.email == ownerEmail }
        }
        val jobs: Map<ProjectId, JenkinsJob> = jenkins.getJobsOverview().associateBy { ProjectId(it.name) }
        return projects.map { project ->
            val job = jobs[project.id]
            val timestamp = job?.lastBuild?.timestamp
            ProjectView(project, job?.lastBuild?.result ?: BuildResult.NOT_BUILT, timestamp?.let { Instant.ofEpochMilli(it) })
        }
    }

    override fun getProjectInfo(id: ProjectId): Project = projectConfigFolder.getProjectInfo(id)

    override fun createProject(project: Project) {
        // 1. Create project JSON file
        projectConfigFolder.requireProjectDoesntExist(project.id)
        projectConfigFolder.writeProjectJson(project)

        // 2. Create Kubernetes config file at /etc/shepherd/k8s/PROJECT_ID.yaml
        kubernetes.writeConfigYamlFile(project)

        // 3. Create Jenkins job
        jenkins.createJob(project)

        // 4. Run the build immediately
        jenkins.build(project.id)
    }

    override fun updateProject(project: Project) {
        val oldProject = projectConfigFolder.getProjectInfo(project.id)
        require(oldProject.gitRepo == project.gitRepo) { "gitRepo is not allowed to be changed: new ${project.gitRepo} old ${project.gitRepo}" }

        // 1. Overwrite the project JSON file
        projectConfigFolder.writeProjectJson(project)

        // 2. Overwrite Kubernetes config file at /etc/shepherd/k8s/PROJECT_ID.yaml
        val kubernetesConfigYamlChanged = kubernetes.writeConfigYamlFile(project)

        // 3. Update Jenkins job
        jenkins.updateJob(project)

        // 4. Detect what kind of update is needed.
        val mainPodDockerImage = kubernetes.getCurrentDockerImage(project.id)
        val isMainPodRunning = mainPodDockerImage != null
        if (!isMainPodRunning) {
            log.info("${project.id.id}: isn't running yet, there is probably no Jenkins job which completed successfully yet")
            jenkins.build(project.id)
        } else if (SimpleJenkinsClient.needsProjectRebuild(project, oldProject)) {
            log.info("${project.id.id}: needs full rebuild on Jenkins")
            jenkins.build(project.id)
        } else if (kubernetesConfigYamlChanged) {
            log.info("${project.id.id}: performing quick kubernetes apply")
            exec("/opt/shepherd/shepherd-apply", project.id.id, mainPodDockerImage!!)
        } else {
            log.info("${project.id.id}: no kubernetes-level/jenkins-level changes detected, not reloading the project")
        }
    }

    override fun deleteProject(id: ProjectId) {
        jenkins.deleteJobIfExists(id)
        kubernetes.deleteIfExists(id)
        projectConfigFolder.deleteIfExists(id)
    }

    override fun getRunLogs(id: ProjectId): String = kubernetes.getRunLogs(id)
    override fun getRunMetrics(id: ProjectId): ResourcesUsage = kubernetes.getMetrics(id)
    override fun getLastBuilds(id: ProjectId): List<Build> {
        val lastBuilds = jenkins.getLastBuilds(id)
        return lastBuilds.map { Build(
            it.number,
            Duration.ofMillis(it.duration),
            Duration.ofMillis(it.estimatedDuration),
            Instant.ofEpochMilli(it.timestamp),
            BuildResult.valueOf(it.result?.name ?: "BUILDING")
        ) }
    }

    override fun getBuildLog(id: ProjectId, build: Build): String {
        TODO("Not yet implemented")
    }

    override fun close() {
        jenkins.close()
    }

    public companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(LinuxShepherdClient::class.java)
    }
}
