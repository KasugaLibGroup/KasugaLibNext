package builder.kasuga

import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

class KasugaShaderPluginTest {
    @Test
    void configuresShaderSourcesCompilerAndProjectVersionDependency() {
        def project = ProjectBuilder.builder().withName('shader-consumer').build()
        project.extensions.extraProperties.set('mod_version', '2.3.4')

        project.pluginManager.apply(KasugaShaderPlugin)

        def sourceSets = project.extensions.getByType(SourceSetContainer)
        def shader = sourceSets.getByName('shader')
        assertTrue(shader.java.srcDirs.any { it == project.file('src/shader/java') })
        assertTrue(shader.resources.srcDirs.any { it == project.file('src/shader/resources') })

        def dependencies = project.configurations
                .getByName(shader.implementationConfigurationName)
                .dependencies
        assertEquals(1, dependencies.size())
        def dependency = dependencies.iterator().next()
        assertEquals('lib.kasuga', dependency.group)
        assertEquals('shader', dependency.name)
        assertEquals('2.3.4', dependency.version)

        JavaExec generate = project.tasks.getByName('generateKasugaShaders') as JavaExec
        assertEquals('lib.kasuga.shader.compiler.ShaderCompilerMain', generate.mainClass.get())
        assertTrue(project.tasks.getByName('processResources').taskDependencies
                .getDependencies(project.tasks.getByName('processResources'))
                .contains(generate))
        assertTrue(sourceSets.main.resources.srcDirs.any {
            it == project.layout.buildDirectory.dir('generated/kasugaShaders/resources').get().asFile
        })
    }
}
