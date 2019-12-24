/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.js.ir

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.plugins.ExtensionAware
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.plugin.AbstractKotlinTargetConfigurator
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinTargetWithTests
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJsCompilation
import org.jetbrains.kotlin.gradle.plugin.whenEvaluated
import org.jetbrains.kotlin.gradle.targets.js.KotlinJsIrPlatformTestRun
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsIrSubTargetDsl
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.npm.NpmResolverPlugin
import org.jetbrains.kotlin.gradle.targets.js.npm.npmProject
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.tasks.registerTask
import org.jetbrains.kotlin.gradle.testing.internal.configureConventions
import org.jetbrains.kotlin.gradle.testing.internal.kotlinTestRegistry
import org.jetbrains.kotlin.gradle.utils.lowerCamelCaseName

abstract class KotlinJsIrSubTarget(
    val target: KotlinJsIrTarget,
    private val disambiguationClassifier: String
) : KotlinJsIrSubTargetDsl {
    val project get() = target.project
    private val nodeJs = NodeJsRootPlugin.apply(project.rootProject)

    abstract val testTaskDescription: String

    final override lateinit var testRuns: NamedDomainObjectContainer<KotlinJsIrPlatformTestRun>
        private set

    fun configure() {
        NpmResolverPlugin.apply(project)

        configureBuildVariants()
        configureTests()
        configureMain()

        target.compilations.all {
            val npmProject = it.npmProject
            it.compileKotlinTask.kotlinOptions.outputFile = npmProject.dir.resolve(npmProject.main).canonicalPath
        }
    }

    override fun produceKotlinLibrary() {
        target.compilations
            .matching { it.name == KotlinCompilation.TEST_COMPILATION_NAME }
            .all {
                it.kotlinOptions {
                    freeCompilerArgs += listOf("-Xir-produce-klib-dir", "-Xir-produce-js")
                }
            }

        target.compilations
            .matching { it.name == KotlinCompilation.MAIN_COMPILATION_NAME }
            .all {
                it.kotlinOptions {
                    freeCompilerArgs += listOf("-Xir-produce-klib-dir")
                }
            }
    }

    override fun testTask(body: KotlinJsTest.() -> Unit) {
        testRuns.getByName(KotlinTargetWithTests.DEFAULT_TEST_RUN_NAME).executionTask.configure(body)
    }

    protected fun disambiguateCamelCased(vararg names: String): String =
        lowerCamelCaseName(target.disambiguationClassifier, disambiguationClassifier, *names)

    abstract fun configureBuildVariants()

    private fun configureTests() {
        testRuns = project.container(KotlinJsIrPlatformTestRun::class.java) { name -> KotlinJsIrPlatformTestRun(name, this) }.also {
            (this as ExtensionAware).extensions.add(this::testRuns.name, it)
        }

        testRuns.all { configureTestRunDefaults(it) }
        testRuns.create(KotlinTargetWithTests.DEFAULT_TEST_RUN_NAME)
    }

    protected open fun configureTestRunDefaults(testRun: KotlinJsIrPlatformTestRun) {
        target.compilations.matching { it.name == KotlinCompilation.TEST_COMPILATION_NAME }.all { compilation ->
            configureTestsRun(testRun, compilation)
        }
    }

    private fun configureTestsRun(testRun: KotlinJsIrPlatformTestRun, compilation: KotlinJsCompilation) {
        fun KotlinJsIrPlatformTestRun.subtargetTestTaskName(): String = disambiguateCamelCased(
            lowerCamelCaseName(
                name.takeIf { it != KotlinTargetWithTests.DEFAULT_TEST_RUN_NAME },
                AbstractKotlinTargetConfigurator.testTaskNameSuffix
            )
        )

        val testJs = project.registerTask<KotlinJsTest>(testRun.subtargetTestTaskName()) { testJs ->
            val compileTask = compilation.compileKotlinTask

            testJs.group = LifecycleBasePlugin.VERIFICATION_GROUP
            testJs.description = testTaskDescription

            testJs.dependsOn(nodeJs.npmInstallTask, compileTask, nodeJs.nodeJsSetupTask)

            testJs.onlyIf {
                compileTask.outputFile.exists()
            }

            testJs.compilation = compilation
            testJs.targetName = listOfNotNull(target.disambiguationClassifier, disambiguationClassifier)
                .takeIf { it.isNotEmpty() }
                ?.joinToString()

            testJs.configureConventions()
        }

        testRun.executionTask = testJs

        target.testRuns.matching { it.name == testRun.name }.all { parentTestRun ->
            target.project.kotlinTestRegistry.registerTestTask(
                testJs,
                parentTestRun.executionTask
            )
        }

        project.whenEvaluated {
            testJs.configure {
                if (it.testFramework == null) {
                    configureDefaultTestFramework(it)
                }
            }
        }
    }

    protected abstract fun configureDefaultTestFramework(it: KotlinJsTest)

    private fun configureMain() {
        target.compilations.all { compilation ->
            if (compilation.name == KotlinCompilation.MAIN_COMPILATION_NAME) {
                configureMain(compilation)
            }
        }
    }

    protected abstract fun configureMain(compilation: KotlinJsCompilation)

    companion object {
        const val RUN_TASK_NAME = "run"
    }
}