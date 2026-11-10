package builder.kasuga;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import groovy.json.JsonSlurper;
import groovy.json.JsonOutput;
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.SourceSet

import java.nio.file.Files;
import java.util.concurrent.*;

class KasugaMonorepoPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        def deepMerge
        deepMerge = { a, b ->
            if (a instanceof Map && b instanceof Map) {
                def result = [:]
                (a.keySet() + b.keySet()).each { key ->
                    def av = a[key]
                    def bv = b[key]
                    result[key] =
                            (av && bv) ? deepMerge(av, bv)
                                    : (av ?: bv)
                }
                return result
            }
            if (a instanceof List && b instanceof List) {
                return (a + b).unique()
            }
            return b ?: a
        }

        project.tasks.named("processResources").configure {
            def slurper = new JsonSlurper()
            def buckets = [:].withDefault { [] }
            eachFile { fileCopyDetails ->
                if (fileCopyDetails.name.endsWith(".json")) {
                    buckets[fileCopyDetails.path] << fileCopyDetails.file
                    fileCopyDetails.exclude()
                }
            }
            doLast {
                def executor = Executors.newFixedThreadPool(
                        Runtime.runtime.availableProcessors()
                )
                try {
                    def futures = buckets.entrySet().collect { entry ->
                        executor.submit({
                            def path = entry.key
                            def files = entry.value

                            def out = new File(destinationDir, path)
                            out.parentFile.mkdirs()

                            if (files.size() == 1) {
                                Files.copy(files[0].toPath(), out.toPath())
                            } else {
                                def merged = null
                                files.each { file ->
                                    def parsed = slurper.parse(file)
                                    merged = merged == null ?
                                            parsed :
                                            deepMerge(merged, parsed)
                                }
                                out.text = JsonOutput.toJson(merged)
                            }
                        } as Callable)
                    }

                    futures*.get()

                } finally {
                    executor.shutdown()
                }
            }
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }

        project.ext.useModProject = { Project targetProject, boolean includeContentTesting = true ->

            project.evaluationDependsOn(targetProject.path)


            if (targetProject == null) {
                throw new IllegalArgumentException("targetProject must not be null")
            }

            def modId = project.findProperty("mod_id") ?: "main"

            project.logger.info("Create Monorepo Dependency: " + project.projectDir.toString() + "-> " + targetProject.projectDir.toString() + "@" + Objects.hashCode(targetProject.sourceSets.main))

            project.neoForge {
                mods {
                    "${modId}" {
                        sourceSet targetProject.sourceSets.main
                    }
                }
            }

            project.dependencies {
                compileOnly targetProject
                if(project.configurations.findByName("shadowOnly")) {
                    it.shadowOnly targetProject
                }
            }

            project.tasks.named("processResources").configure {
                from(targetProject.sourceSets.main.resources) {
                    exclude "META-INF/neoforge.mods.toml"
                    exclude "META-INF/mods.toml"
                }
                dependsOn(targetProject.tasks.named("processResources"))
            }


            project.afterEvaluate {

                if(includeContentTesting && targetProject.sourceSets.hasProperty("contentTesting")) {
                    project.neoForge {
                        mods {
                            "${modId}" {
                                sourceSet targetProject.sourceSets.contentTesting
                            }
                        }
                    }
                }

                if(includeContentTesting && project.sourceSets.hasProperty("contentTesting") && targetProject.sourceSets.hasProperty("contentTesting")) {
                    project.sourceSets {
                        contentTesting {
                            System.out.println("Add contentTesting classpath from " + targetProject.path + " to " + project.path)
                            compileClasspath += targetProject.sourceSets.contentTesting.output
                            runtimeClasspath += targetProject.sourceSets.contentTesting.output

                            compileClasspath += targetProject.sourceSets.contentTesting.compileClasspath
                            runtimeClasspath += targetProject.sourceSets.contentTesting.runtimeClasspath
                        }
                    }
                }

                def shadowExtension = project.getTasks().findByName('shadowJar')

                if (shadowExtension != null) {
                    shadowExtension.configure {
                        from(targetProject.sourceSets.main.output) {
                            exclude "META-INF/neoforge.mods.toml"
                            exclude "META-INF/mods.toml"
                        }
                    }
                }

            }

        }
    }
}
