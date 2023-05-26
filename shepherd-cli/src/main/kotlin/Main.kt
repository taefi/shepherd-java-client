import com.github.mvysny.shepherd.api.Project
import com.github.mvysny.shepherd.api.ProjectId
import com.github.mvysny.shepherd.api.ShepherdClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlin.io.path.Path

fun main(args: Array<String>) {
    val a = Args.parse(args)
    a.command.run(a, a.createClient())
}

enum class Command(val argName: String) {
    /**
     * List all projects: their IDs and a quick info about the project: the description, the owner and such.
     */
    ListProjects("list") {
        override fun run(args: Args, client: ShepherdClient) {
            val projects = client.getAllProjects()
            projects.forEach { projectId ->
                val project = client.getProjectInfo(projectId)
                println("${projectId.id}: ${project.description}; ${project.gitRepo} (${project.owner})")
            }
            if (projects.isEmpty()) {
                println("No projects registered.")
            }
        }
    },

    /**
     * The `show` command, shows all information about certain project as a JSON.
     */
    ShowProject("show") {
        override fun run(args: Args, client: ShepherdClient) {
            val project = client.getProjectInfo(requireProjectId(args))
            val json = Json { prettyPrint = true }
            println(json.encodeToString(project))
        }
    },

    /**
     * The `logs` command, prints the runtime logs of the main pod of given project.
     */
    Logs("logs") {
        override fun run(args: Args, client: ShepherdClient) {
            println(client.getRunLogs(requireProjectId(args)))
        }
    },

    /**
     * The `delete` command, Deletes a project. Dangerous operation, requires -y to confirm.
     */
    Delete("delete") {
        override fun run(args: Args, client: ShepherdClient) {
            val pid = requireProjectId(args)
            require(args.deleteSubcommand.yes) { "Pass in -y to confirm that you really want to delete $pid" }
            client.deleteProject(pid)
        }
    },

    /**
     * The `create` command, creates a new project. Fails if the project already exists.
     */
    Create("create") {
        override fun run(args: Args, client: ShepherdClient) {
            val project = Project.loadFromFile(Path(args.createSubcommand.jsonFile))
            client.createProject(project)
        }
    }
    ;

    /**
     * Runs the command, with given [args] over given [client].
     */
    abstract fun run(args: Args, client: ShepherdClient)

    /**
     * Utility function to get the [Args.project], failing if it's missing on the command line.
     */
    protected fun requireProjectId(args: Args): ProjectId {
        require(args.project != null) { "This command requires the project ID" }
        return args.project
    }
}
