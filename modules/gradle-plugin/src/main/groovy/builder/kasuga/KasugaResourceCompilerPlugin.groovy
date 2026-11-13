package builder.kasuga

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec;

class KasugaResourceCompilerPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.configurations {
            resourceCompilerDependency
        }
        var compileResource = project.tasks.register('compileResources', JavaExec) {
            String inDir = "$project.projectDir/resources", outDir = "$project.projectDir/src/compiled/resources";
            File outputDirectory = project.file(outDir);

            inputs.dir(inDir)
                    .withPropertyName("sources");

            outputs.dir(outDir)

            classpath = project.configurations.resourceCompilerDependency

            mainClass = 'lib.kasuga.resource.ResourceCompilerMain'

            args(inDir, outDir, project.findProperty("mod_id"))

            javaLauncher.set(project.javaToolchains.launcherFor {
                languageVersion = project.java.toolchain.languageVersion
            })

            doFirst {
                outputDirectory.deleteDir()
            }
        }

        project.tasks.getByName("processResources").dependsOn(compileResource);

        project.dependencies {
            if (project.findProject(":modules:resource-compiler")) {
                resourceCompilerDependency project.findProject(':modules:resource-compiler');
            } else if(project.findProject(":KasugaLibNext:modules:resource-compiler")) {
                resourceCompilerDependency project.findProject(':KasugaLibNext:modules:resource-compiler');
            } else {
                resourceCompilerDependency 'lib.kasuga:resource-compiler:1.0.0';
            }
        }
    }
}
