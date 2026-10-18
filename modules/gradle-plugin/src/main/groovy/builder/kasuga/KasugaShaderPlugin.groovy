package builder.kasuga

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer

/** Adds a dedicated Java shader-definition source set and compiles it into generated resources. */
class KasugaShaderPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.pluginManager.apply('java')

        def sourceSets = project.extensions.getByType(SourceSetContainer)
        def shader = sourceSets.create('shader') {
            java.srcDir('src/shader/java')
            resources.srcDir('src/shader/resources')
        }

        def shaderProject = project.rootProject.findProject(':modules:shader')
        def shaderVersion = project.findProperty('mod_version') ?: '1.0.0'
        def shaderDependency = shaderProject != null ? shaderProject : "lib.kasuga:shader:${shaderVersion}"
        project.dependencies.add(shader.implementationConfigurationName, shaderDependency)

        def generatedResources = project.layout.buildDirectory.dir('generated/kasugaShaders/resources')
        def generatedResourcesPath = generatedResources.get().asFile.absolutePath
        def shaderClassesPath = shader.output.classesDirs.files*.absolutePath.join(File.pathSeparator)
        def generateShaders = project.tasks.register('generateKasugaShaders', JavaExec) {
            group = 'build'
            description = 'Executes imperative Java shader definitions and emits GLSL resources.'
            dependsOn(project.tasks.named(shader.classesTaskName))
            classpath = shader.runtimeClasspath
            mainClass.set('lib.kasuga.shader.compiler.ShaderCompilerMain')
            inputs.files(shader.allSource).withPropertyName('shaderSources')
            outputs.dir(generatedResources)
            args(generatedResourcesPath, shaderClassesPath)
        }

        sourceSets.main.resources.srcDir(generatedResources)
        project.tasks.named(sourceSets.main.processResourcesTaskName).configure {
            dependsOn(generateShaders)
        }
    }
}
