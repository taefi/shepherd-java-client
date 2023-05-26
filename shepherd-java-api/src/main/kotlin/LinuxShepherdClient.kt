package com.github.mvysny.shepherd.api

import java.nio.file.Path
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

    override fun getAllProjects(): List<ProjectId> = projectConfigFolder.getAllProjects()

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

    override fun deleteProject(id: ProjectId) {
        jenkins.deleteJobIfExists(id)
        kubernetes.deleteIfExists(id)
        projectConfigFolder.deleteIfExists(id)
    }

    override fun getRunLogs(id: ProjectId): String = kubernetes.getRunLogs(id)
}
