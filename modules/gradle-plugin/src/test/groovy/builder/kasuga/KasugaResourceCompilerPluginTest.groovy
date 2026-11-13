package builder.kasuga

import org.gradle.api.tasks.JavaExec
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertFalse

class KasugaResourceCompilerPluginTest {
    @Test
    void clearsGeneratedResourcesBeforeCompilation() {
        def project = ProjectBuilder.builder().withName('resource-consumer').build()
        project.extensions.extraProperties.set('mod_id', 'test_mod')
        project.pluginManager.apply('java')
        project.pluginManager.apply(KasugaResourceCompilerPlugin)

        def stale = project.file('src/compiled/resources/scripts/packages/removed/index.js')
        stale.parentFile.mkdirs()
        stale.text = 'stale'

        JavaExec compileResources = project.tasks.getByName('compileResources') as JavaExec
        compileResources.actions.first().execute(compileResources)

        assertFalse(stale.exists())
    }
}
