/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.scripting.compiler.test

import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.codegen.forTestCompile.ForTestCompileRuntime
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.K2JvmReplCompilerWithIdeServices
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.SCRIPT_BASE_COMPILER_ARGUMENTS_PROPERTY
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.ScriptDiagnosticsMessageCollector
import org.jetbrains.kotlin.scripting.test.SCRIPT_TEST_BASE_COMPILER_ARGUMENTS_PROPERTY
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.dependencies.CompoundDependenciesResolver
import kotlin.script.experimental.dependencies.DependsOn
import kotlin.script.experimental.dependencies.FileSystemDependenciesResolver
import kotlin.script.experimental.dependencies.resolveFromScriptSourceAnnotations
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.createScriptDefinitionFromTemplate
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.host.withDefaultsFrom
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvm.util.toSourceCodePosition
import kotlin.script.experimental.jvm.withUpdatedClasspath
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

@Suppress("unused")
class IdeCompletionReceiver {
    fun receiverFun(x: Int): Int = x
    val receiverProp: String = "r"
}

@Suppress("unused")
abstract class IdeCompletionScriptBase {
    fun baseFun(x: Int): Int = x
    val baseProp: String = "b"
}

@Suppress("unused")
fun ideCompletionTopLevelFun(x: Int): Int = x

@Suppress("unused")
@Deprecated("use a newer entry point")
fun ideDeprecatedTopLevelFun(): Int = 1

@Suppress("unused")
@Deprecated("gone", level = DeprecationLevel.HIDDEN)
fun ideHiddenTopLevelFun(): Int = 1

@Suppress("unused")
class IdeVisibilityBox {
    val shownProp: Int = 1
    private val hiddenProp: Int = 2
}

@Suppress("unused")
class TestInnerDslA {
    fun readValue(path: String): String = path
    val entryCount: Int = 0
}

@Suppress("unused")
class TestInnerDslB {
    fun readResource(id: String): String = id
    val version: String = "v1"
}

@Suppress("unused")
open class TestOuterDsl {
    fun <T> withScopeA(receiver: TestInnerDslA.() -> T): T = receiver(TestInnerDslA())
    fun <T> withScopeB(receiver: TestInnerDslB.() -> T): T = receiver(TestInnerDslB())
    fun reportError(message: String) {}
}

@Suppress("unused")
interface TestScriptApi {
    fun execute(
        entry: String,
        entries: List<String>,
        receiver: TestOuterDsl.() -> Unit,
    ): List<String> {
        TestOuterDsl().receiver()
        return emptyList()
    }
}

@Suppress("unused")
abstract class TestDslScriptBase : TestScriptApi

@Suppress("unused")
class TestGroupAStep {
    fun addStep(id: String): String = id
    val stepName: String = "a"
}

@Suppress("unused")
class TestGroupBStep {
    fun addTarget(url: String): String = url
    val target: String = "b"
}

@Suppress("unused")
open class TestGroupDsl {
    val registry: String = "registry"

    fun groupA(name: String, receiver: TestGroupAStep.() -> Unit) {
        TestGroupAStep().receiver()
    }

    fun groupB(name: String, receiver: TestGroupBStep.() -> Unit) {
        TestGroupBStep().receiver()
    }
}

@Suppress("unused")
interface TestNestedScriptApi {
    fun build(receiver: TestGroupDsl.() -> Unit): Any {
        TestGroupDsl().receiver()
        return Unit
    }
}

@Suppress("unused")
abstract class TestNestedDslScriptBase : TestNestedScriptApi

@Suppress("unused")
abstract class TestDelegatingScriptBase(
    private val api: TestNestedScriptApi,
) : TestNestedScriptApi by api

@DslMarker
annotation class TestFlowDslMarker

@TestFlowDslMarker
interface TestMarkedBaseDsl

@Suppress("unused")
@TestFlowDslMarker
open class TestMarkedDsl : TestMarkedBaseDsl {
    val registry: String = "registry"

    fun tokenFlow(name: String, receiver: TestGroupAStep.() -> Unit) {
        TestGroupAStep().receiver()
    }

    fun redirectFlow(name: String, receiver: TestGroupBStep.() -> Unit) {
        TestGroupBStep().receiver()
    }
}

@Suppress("unused")
interface TestMarkedScriptApi {
    fun flows(receiver: TestMarkedDsl.() -> Unit): Any {
        TestMarkedDsl().receiver()
        return Unit
    }
}

@Suppress("unused")
abstract class TestMarkedDslScriptBase : TestMarkedScriptApi

@Suppress("unused")
abstract class TestMarkedDelegatingScriptBase(
    private val api: TestMarkedScriptApi,
) : TestMarkedScriptApi by api

object TemplateFlowsConfiguration : ScriptCompilationConfiguration(
    {
        val classpath = System.getProperty("kotlin.test.script.classpath")?.split(File.pathSeparator)
            ?.mapNotNull { File(it).takeIf { file -> file.exists() } }.orEmpty()
        updateClasspath(classpath + ForTestCompileRuntime.runtimeJarForTests())
        compilerOptions("-Xrender-internal-diagnostic-names=true")
        defaultImports(TestMarkedDsl::class, TestGroupAStep::class, TestGroupBStep::class)
        ide {
            acceptedLocations(ScriptAcceptedLocation.Everywhere)
        }
    }) {
    private fun readResolve(): Any = TemplateFlowsConfiguration
}

@Suppress("unused")
@KotlinScript(fileExtension = "flowstmpl.kts", compilationConfiguration = TemplateFlowsConfiguration::class)
abstract class TestTemplateFlowsScriptBase(
    private val api: TestMarkedScriptApi,
) : TestMarkedScriptApi by api

class K2ReplIdeServicesTest {

    private val isK1 =
        System.getProperty(SCRIPT_BASE_COMPILER_ARGUMENTS_PROPERTY)?.contains("-language-version 1.9") == true ||
                System.getProperty(SCRIPT_TEST_BASE_COMPILER_ARGUMENTS_PROPERTY)?.contains("-language-version 1.9") == true

    @Test
    fun testCompleteLocalSnippetDeclarations() = ideServicesTest {
        val variants = complete(
            """
                val myFieldA = 42
                val myFieldB = "str"
                myField
            """.trimIndent()
        )
        variants.assertContains(SourceCodeCompletionVariant("myFieldA", "myFieldA", "Int", "property"))
        variants.assertContains(SourceCodeCompletionVariant("myFieldB", "myFieldB", "String", "property"))
    }

    @Test
    fun testCompleteFromReplHistory() = ideServicesTest {
        commit("val committedVal = 42")
        commit("fun committedFun(x: Int) = x")

        val variants = complete("committed")
        variants.assertContains(SourceCodeCompletionVariant("committedVal", "committedVal", "Int", "property"))
        variants.assertContains(SourceCodeCompletionVariant("committedFun", "committedFun(x: Int)", "Int", "method"))
    }

    @Test
    fun testCompleteLocalInNestedScope() = ideServicesTest {
        val code = "listOf(1, 2, 3).map { elementInLambda -> elementInLam }"
        val cursor = code.indexOf("elementInLam ") + "elementInLam".length
        val variants = complete(code, cursor)
        variants.assertContains(SourceCodeCompletionVariant("elementInLambda", "elementInLambda", "Int", "property"))
    }

    @Test
    fun testCompleteMemberAccess() = ideServicesTest {
        commit("class Box(val width: Int, val height: String)")
        commit("val box = Box(1, \"a\")")

        val variants = complete("box.")
        variants.assertContains(SourceCodeCompletionVariant("width", "width", "Int", "property"))
        variants.assertContains(SourceCodeCompletionVariant("height", "height", "String", "property"))
    }

    @Test
    fun testCompleteNamedArgument() = ideServicesTest {
        commit("fun greet(greeting: String, times: Int) {}")
        val code = "greet(gr)"
        val cursor = code.lastIndexOf("gr") + 2
        val variants = complete(code, cursor)
        assertTrue(
            variants.any { it.text == "greeting = " && it.icon == "parameter" },
            "Expected named-argument `greeting = ` completion in call argument position, got: $variants"
        )
    }

    @Test
    fun testCompleteNamedArgumentSkipsAlreadyNamed() = ideServicesTest {
        commit("fun greet(greeting: String, times: Int) {}")
        val code = "greet(greeting = \"hi\", ti)"
        val cursor = code.indexOf("ti)") + 2
        val variants = complete(code, cursor)
        assertTrue(
            variants.any { it.text == "times = " && it.icon == "parameter" },
            "Expected remaining named-argument `times = `, got: $variants"
        )
        assertTrue(
            variants.none { it.text == "greeting = " },
            "Already-named `greeting` should not be offered again, got: $variants"
        )
    }

    @Test
    fun testCompleteSmartCastMember() = ideServicesTest {
        val code = "val a: Any = \"x\"\nif (a is String) {\n  a.\n}"
        val cursor = code.lastIndexOf("a.") + 2
        val variants = complete(code, cursor)
        assertTrue(
            variants.any { it.text == "length" && it.icon == "property" },
            "Expected smart-cast `String` member `length` after `a is String`, got: $variants"
        )
    }

    @Test
    fun testCompletePackageMember() = ideServicesTest {
        val variants = complete("kotlin.collections.listO")
        assertTrue(
            variants.any { it.text == "listOf" && it.icon == "method" },
            "Expected `listOf` completing off the `kotlin.collections` package, got: $variants"
        )
    }

    @Test
    fun testCompleteEnumEntryOffClass() = ideServicesTest {
        val variants = complete("DeprecationLevel.W")
        assertTrue(
            variants.any { it.text == "WARNING" },
            "Expected enum entry `WARNING` completing off the `DeprecationLevel` class, got: $variants"
        )
    }

    @Test
    fun testCompleteCompanionMemberOffClass() = ideServicesTest {
        val variants = complete("Int.MAX")
        assertTrue(
            variants.any { it.text == "MAX_VALUE" },
            "Expected companion member `MAX_VALUE` completing off the `Int` class, got: $variants"
        )
    }

    @Test
    fun testCompleteTopLevelStdlib() = ideServicesTest {
        val variants = complete("listO")
        assertTrue(
            variants.any { it.text == "listOf" && it.icon == "method" },
            "Expected `listOf` among stdlib completions, got: $variants"
        )
    }

    @Test
    fun testFunctionCompletionExposesSignature() = ideServicesTest {
        commit("fun combine(prefix: String, count: Int): String = prefix.repeat(count)")
        val variants = complete("combi")
        val variant = variants.firstOrNull { it.text == "combine" && it.icon == "method" }
        assertTrue(variant != null, "Expected function `combine` completion, got: $variants")
        assertEquals(
            "combine(prefix: String, count: Int)", variant.displayText,
            "Function completion should render the full parameter signature in displayText"
        )
        assertEquals(
            "String", variant.tail,
            "Function completion should render the return type in the tail"
        )
    }

    @Test
    fun testCompleteUnimportedClasspathClass() = ideServicesTest {
        val variants = complete("Rando")
        assertTrue(
            variants.any { it.text == "Random" && it.icon == "class" && it.tail == "kotlin.random" },
            "Expected unimported classpath class `kotlin.random.Random` with its package in the tail, got: $variants"
        )
    }

    @Test
    fun testCompleteDependencyClassFromConfiguration() = ideServicesTest(dependencyInConfigurationConfiguration) {
        val variants = complete("IdeComp")
        assertTrue(
            variants.any {
                it.text == DEPENDENCY_CLASS_SIMPLE_NAME && it.icon == "class" && it.tail == DEPENDENCY_CLASS_PACKAGE
            },
            "Expected `$DEPENDENCY_CLASS_SIMPLE_NAME` from a configuration-provided dependency, got: $variants"
        )
    }

    @Test
    fun testDependencyClassUnresolvedWithoutDependency() = ideServicesTest(withoutDependencyConfiguration) {
        val variants = complete("IdeComp")
        assertTrue(
            variants.none { it.text == DEPENDENCY_CLASS_SIMPLE_NAME },
            "Expected `$DEPENDENCY_CLASS_SIMPLE_NAME` to be unresolved without its dependency, got: $variants"
        )
    }

    @Test
    fun testCompleteDependencyClassFromFileDependsOn() = ideServicesTest(dependencyViaDependsOnConfiguration) {
        val dependencyPath = dependencyClasspathEntry.absolutePath.replace("\\", "\\\\")
        val variants = complete("@file:DependsOn(\"$dependencyPath\")\nIdeComp")
        assertTrue(
            variants.any {
                it.text == DEPENDENCY_CLASS_SIMPLE_NAME && it.icon == "class" && it.tail == DEPENDENCY_CLASS_PACKAGE
            },
            "Expected `$DEPENDENCY_CLASS_SIMPLE_NAME` from a `@file:DependsOn` dependency, got: $variants"
        )
    }

    @Test
    fun testClassCompletionExposesPackageInTail() = ideServicesTest {
        val variants = complete("DeprecationLev")
        val variant = variants.firstOrNull { it.text == "DeprecationLevel" && it.icon == "class" }
        assertTrue(variant != null, "Expected class `DeprecationLevel` completion, got: $variants")
        assertEquals(
            "kotlin", variant.tail,
            "Class completion should expose its package in the tail so it can be imported, got: ${variant.tail}"
        )
    }

    @Test
    fun testClassCompletionOffersConstructors() = ideServicesTest {
        val variants = complete("Rege")
        assertTrue(
            variants.any { it.text == "Regex" && it.icon == "class" },
            "Expected class `Regex` completion, got: $variants"
        )
        val constructor = variants.firstOrNull {
            it.text == "Regex" && it.icon == "constructor" && it.displayText.startsWith("Regex(")
        }
        assertTrue(
            constructor != null,
            "Expected a constructor completion for `Regex` with a rendered parameter list, got: $variants"
        )
        assertTrue(
            variants.any { it.text == "Regex" && it.icon == "constructor" && it.displayText == "Regex(pattern: String)" },
            "Expected the single-arg `Regex(pattern: String)` constructor, got: $variants"
        )
    }

    @Test
    fun testCompleteImportTopLevelPackage() = ideServicesTest {
        val variants = complete("import kotl")
        assertTrue(
            variants.any { it.text == "kotlin" && it.icon == "package" },
            "Expected top-level package `kotlin` while typing an import, got: $variants"
        )
    }

    @Test
    fun testCompleteImportPackageSegment() = ideServicesTest {
        val variants = complete("import kotlin.coll")
        assertTrue(
            variants.any { it.text == "collections" && it.icon == "package" },
            "Expected sub-package `collections` while typing `import kotlin.coll`, got: $variants"
        )
    }

    @Test
    fun testCompleteImportClassInPackage() = ideServicesTest {
        val variants = complete("import kotlin.collections.List")
        assertTrue(
            variants.any { it.text == "List" && it.icon == "class" },
            "Expected class `List` while typing `import kotlin.collections.List`, got: $variants"
        )
    }

    @Test
    fun testCompleteImportClassFromDependency() = ideServicesTest {
        val variants = complete("import org.jetbrains.kotlin.scripting.compiler.test.IdeComp")
        assertTrue(
            variants.any { it.text == "IdeCompletionReceiver" && it.icon == "class" },
            "Expected class `IdeCompletionReceiver` from a classpath dependency package, got: $variants"
        )
        assertTrue(
            variants.any { it.text == "IdeCompletionScriptBase" && it.icon == "class" },
            "Expected class `IdeCompletionScriptBase` from a classpath dependency package, got: $variants"
        )
    }

    @Test
    fun testCompleteImportSubPackageFromDependency() = ideServicesTest {
        val variants = complete("import org.jetbrains.kotlin.scripting.")
        assertTrue(
            variants.any { it.text == "compiler" && it.icon == "package" },
            "Expected sub-package `compiler` while typing a classpath dependency import, got: $variants"
        )
    }

    @Test
    fun testCompleteNoVariantsForUnknownPrefix() = ideServicesTest {
        val variants = complete("zzzNoSuchIdentifier")
        assertTrue(variants.isEmpty(), "Expected no completions for an unknown prefix, got: $variants")
    }

    @Test
    fun testAnalyzeResultType() = ideServicesTest {
        val result = analyze(
            """
                val foo = 42
                foo
            """.trimIndent()
        )
        assertEquals("Int", result[ReplAnalyzerResult.renderedResultType])
        assertTrue(result.errors().isEmpty(), "Expected no errors, got: ${result.errors()}")
    }

    @Test
    fun testAnalyzeReportsTypeMismatch() = ideServicesTest {
        val result = analyze("val x: Int = \"not an int\"")
        assertTrue(result.errors().isNotEmpty(), "Expected a type-mismatch error to be reported")
    }

    @Test
    fun testAnalyzeReportsUnresolvedReference() = ideServicesTest {
        val result = analyze("thisReferenceDoesNotExist")
        val errors = result.errors()
        assertTrue(errors.isNotEmpty(), "Expected an unresolved-reference error to be reported")
        assertTrue(
            errors.any { it.message.contains("thisReferenceDoesNotExist") },
            "Expected the unresolved identifier in the diagnostic message, got: $errors"
        )
    }

    @Test
    fun testAnalyzeCleanSnippetHasNoErrors() = ideServicesTest {
        commit("val base = 10")
        val result = analyze("base * 2")
        assertTrue(result.errors().isEmpty(), "Expected no errors, got: ${result.errors()}")
        assertEquals("Int", result[ReplAnalyzerResult.renderedResultType])
    }

    @Test
    fun testAnalyzeDoesNotPolluteHistory() = ideServicesTest {
        commit("val realDecl = 1")

        val result = analyze(
            """
                val ghostDecl = realDecl + 1
                ghostDecl
            """.trimIndent()
        )
        assertEquals("Int", result[ReplAnalyzerResult.renderedResultType])

        val afterGhost = complete("ghost")
        assertTrue(
            afterGhost.none { it.text == "ghostDecl" },
            "analyze leaked `ghostDecl` into the committed REPL history: $afterGhost"
        )
        val afterReal = complete("real")
        assertTrue(
            afterReal.any { it.text == "realDecl" },
            "Committed `realDecl` should still be visible after a stateless query: $afterReal"
        )
    }

    @Test
    fun testRepeatedCompletionIsStable() = ideServicesTest {
        commit("val repeatable = 7")

        val first = complete("repeat")
        val second = complete("repeat")
        assertEquals(first, second, "Repeated completion produced different results, history is not stateless")
        assertTrue(first.any { it.text == "repeatable" })
    }

    @Test
    fun testCompleteImplicitReceiverMember() = ideServicesTest(receiverCompilationConfiguration) {
        val variants = complete("receiver")
        variants.assertContains(SourceCodeCompletionVariant("receiverFun", "receiverFun(x: Int)", "Int", "method"))
        variants.assertContains(SourceCodeCompletionVariant("receiverProp", "receiverProp", "String", "property"))
    }

    @Test
    fun testCompleteDefaultImportedTopLevel() = ideServicesTest(defaultImportsCompilationConfiguration) {
        val variants = complete("ideCompletionTopLevel")
        variants.assertContains(
            SourceCodeCompletionVariant("ideCompletionTopLevelFun", "ideCompletionTopLevelFun(x: Int)", "Int", "method")
        )
    }

    @Test
    fun testCompleteBaseClassMember() = ideServicesTest(baseClassCompilationConfiguration) {
        val variants = complete("base")
        variants.assertContains(SourceCodeCompletionVariant("baseFun", "baseFun(x: Int)", "Int", "method"))
        variants.assertContains(SourceCodeCompletionVariant("baseProp", "baseProp", "String", "property"))
    }

    @Test
    fun testCompleteEntrypointFromBaseClass() = ideServicesTest(dslScriptCompilationConfiguration) {
        val variants = complete("execu")
        assertTrue(
            variants.any { it.text == "execute" && it.icon == "method" },
            "Expected the `execute` entrypoint (from the script base class) among completions, got: $variants"
        )
    }

    @Test
    fun testCompleteOuterDslReceiverMembers() = ideServicesTest(dslScriptCompilationConfiguration) {
        val code = "execute(\"e\", listOf(\"e\")) { withScope }"
        val cursor = code.indexOf("withScope") + "withScope".length
        val variants = complete(code, cursor)
        assertTrue(
            variants.any { it.text == "withScopeA" && it.icon == "method" },
            "Expected `withScopeA` DSL member inside the outer lambda, got: $variants"
        )
        assertTrue(
            variants.any { it.text == "withScopeB" && it.icon == "method" },
            "Expected `withScopeB` DSL member inside the outer lambda, got: $variants"
        )
    }

    @Test
    fun testCompleteInsideNestedDslLambda() = ideServicesTest(dslScriptCompilationConfiguration) {
        val code = "execute(\"e\", listOf(\"e\")) { withScopeA { read } }"
        val cursor = code.indexOf("read") + "read".length
        val variants = complete(code, cursor)
        variants.assertContains(
            SourceCodeCompletionVariant("readValue", "readValue(path: String)", "String", "method")
        )

        val propCode = "execute(\"e\", listOf(\"e\")) { withScopeA { entry } }"
        val propCursor = propCode.indexOf("entry") + "entry".length
        val propVariants = complete(propCode, propCursor)
        propVariants.assertContains(SourceCodeCompletionVariant("entryCount", "entryCount", "Int", "property"))
    }

    @Test
    fun testCompleteMemberAccessInsideNestedDslLambda() = ideServicesTest(dslScriptCompilationConfiguration) {
        val code = "execute(\"e\", listOf(\"e\")) { withScopeB { version. } }"
        val cursor = code.indexOf("version.") + "version.".length
        val variants = complete(code, cursor)
        assertTrue(
            variants.any { it.text == "length" && it.icon == "property" },
            "Expected `String` members completing off `version` inside the nested lambda, got: $variants"
        )
    }

    @Test
    fun testCompleteNestedEntrypointFromBaseClass() = ideServicesTest(nestedDslScriptCompilationConfiguration) {
        val variants = complete("buil")
        assertTrue(
            variants.any { it.text == "build" && it.icon == "method" },
            "Expected the `build` entrypoint (from the script base class) among completions, got: $variants"
        )
    }

    @Test
    fun testCompleteNestedGroupDslMembers() = ideServicesTest(nestedDslScriptCompilationConfiguration) {
        val code = "build { group }"
        val cursor = code.indexOf("group") + "group".length
        val variants = complete(code, cursor)
        assertTrue(
            variants.any { it.text == "groupA" && it.icon == "method" },
            "Expected `groupA` DSL member inside the `build` lambda, got: $variants"
        )
        assertTrue(
            variants.any { it.text == "groupB" && it.icon == "method" },
            "Expected `groupB` DSL member inside the `build` lambda, got: $variants"
        )
    }

    @Test
    fun testCompleteInsideDeeplyNestedDslLambda() = ideServicesTest(nestedDslScriptCompilationConfiguration) {
        val code = "build { groupA(\"x\") { add } }"
        val cursor = code.indexOf("add") + "add".length
        val variants = complete(code, cursor)
        variants.assertContains(SourceCodeCompletionVariant("addStep", "addStep(id: String)", "String", "method"))

        val propCode = "build { groupA(\"x\") { step } }"
        val propCursor = propCode.indexOf("step") + "step".length
        val propVariants = complete(propCode, propCursor)
        propVariants.assertContains(SourceCodeCompletionVariant("stepName", "stepName", "String", "property"))
    }

    @Test
    fun testCompleteMemberAccessInsideDeeplyNestedDslLambda() = ideServicesTest(nestedDslScriptCompilationConfiguration) {
        val code = "build { groupB(\"x\") { target. } }"
        val cursor = code.indexOf("target.") + "target.".length
        val variants = complete(code, cursor)
        assertTrue(
            variants.any { it.text == "length" && it.icon == "property" },
            "Expected `String` members completing off `target` inside the deeply nested lambda, got: $variants"
        )
    }

    @Test
    fun testCompleteInsideDelegatedEntrypointLambda() = ideServicesTest(delegatingDslScriptCompilationConfiguration) {
        val code = "build {\n  group\n}"
        val cursor = code.indexOf("group") + "group".length
        val variants = complete(code, cursor)
        assertTrue(
            variants.any { it.text == "groupA" && it.icon == "method" },
            "Expected `groupA` inside the delegated `build` lambda, got: $variants"
        )
        assertTrue(
            variants.any { it.text == "groupB" && it.icon == "method" },
            "Expected `groupB` inside the delegated `build` lambda, got: $variants"
        )
    }

    @Test
    fun testCompleteInsideDslMarkedEntrypointLambda() = ideServicesTest(markedDslScriptCompilationConfiguration) {
        val code = "flows {\n  token\n}"
        val cursor = code.indexOf("token") + "token".length
        val variants = complete(code, cursor)
        assertTrue(
            variants.any { it.text == "tokenFlow" && it.icon == "method" },
            "Expected `tokenFlow` DSL member inside the @DslMarker `flows` lambda, got: $variants"
        )
    }

    @Test
    fun testCompleteUnimportedClasspathClassInsideDslLambda() = ideServicesTest(markedDslScriptCompilationConfiguration) {
        val code = "flows {\n  Rando\n}"
        val cursor = code.indexOf("Rando") + "Rando".length
        val variants = complete(code, cursor)
        assertTrue(
            variants.any { it.text == "Random" && it.icon == "class" && it.tail == "kotlin.random" },
            "Expected unimported classpath class `kotlin.random.Random` inside the `flows { }` lambda, got: $variants"
        )
    }

    @Test
    fun testCompleteInsideDslLambdaDoesNotLeakUnrelatedExtensions() = ideServicesTest(markedDslScriptCompilationConfiguration) {
        val code = "flows {\n  d\n}"
        val cursor = code.indexOf("d") + "d".length
        val variants = complete(code, cursor)
        assertTrue(
            variants.none { it.text == "distinct" },
            "`Iterable.distinct` is not applicable to any receiver in scope and must not leak into `flows { }` completions, got: $variants"
        )
    }

    @Test
    fun testCompleteInsideTemplateLikeEntrypointLambda() = ideServicesTest(templateLikeMarkedConfiguration) {
        val code = "flows {\n  token\n}"
        val cursor = code.indexOf("token") + "token".length
        val variants = complete(code, cursor)
        assertTrue(
            variants.any { it.text == "tokenFlow" && it.icon == "method" },
            "Expected `tokenFlow` DSL member inside the template-like `flows` lambda, got: $variants"
        )
    }

    @Test
    fun testCompleteInsideFaithfulFlowsEntrypointLambda() = ideServicesTest(faithfulFlowsConfiguration) {
        val code = "flows {\n  token\n}"
        val cursor = code.indexOf("token") + "token".length
        val variants = complete(code, cursor)
        assertTrue(
            variants.any { it.text == "tokenFlow" && it.icon == "method" },
            "Expected `tokenFlow` inside the faithful (marker+delegation+ctor-param) `flows` lambda, got: $variants"
        )
    }

    @Test
    fun testCompleteInsideTemplatePathFlowsEntrypointLambda() = ideServicesTest(templateFlowsConfiguration) {
        val code = "flows {\n  token\n}"
        val cursor = code.indexOf("token") + "token".length
        val variants = complete(code, cursor)
        assertTrue(
            variants.any { it.text == "tokenFlow" && it.icon == "method" },
            "Expected `tokenFlow` inside the template-path (`@KotlinScript` base class) `flows` lambda, got: $variants"
        )
        val errors = analyze(code).errors()
        assertTrue(
            errors.none { it.message.contains("Unresolved reference 'flows'") },
            "Expected no `Unresolved reference 'flows'` diagnostic, got: $errors"
        )
    }

    @Test
    fun testCompleteInsidePlaygroundFaithfulFlowsEntrypointLambda() = ideServicesTest(
        templateFlowsConfiguration,
        playgroundHostConfiguration,
    ) {
        val code = "flows {\n  token\n}"
        val cursor = code.indexOf("token") + "token".length
        val variants = complete(code, cursor)
        assertTrue(
            variants.any { it.text == "tokenFlow" && it.icon == "method" },
            "Expected `tokenFlow` inside the playground-faithful `flows` lambda, got: $variants"
        )
        val errors = analyze(code).errors()
        assertTrue(
            errors.none { it.message.contains("Unresolved reference 'flows'") },
            "Expected no `Unresolved reference 'flows'` diagnostic, got: $errors"
        )
    }

    private fun assertResultType(code: String, expected: String) = ideServicesTest {
        val result = analyze(code)
        assertTrue(result.errors().isEmpty(), "Expected `$code` to analyze cleanly, got: ${result.errors()}")
        assertEquals(expected, result[ReplAnalyzerResult.renderedResultType], "Wrong result type for `$code`")
    }

    private fun assertClean(code: String) = ideServicesTest {
        val result = analyze(code)
        assertTrue(result.errors().isEmpty(), "Expected `$code` to analyze without errors, got: ${result.errors()}")
    }

    private fun assertHasError(code: String) = ideServicesTest {
        val result = analyze(code)
        assertTrue(result.errors().isNotEmpty(), "Expected `$code` to report a diagnostic, but none was produced")
    }

    @Test
    fun testAnalyzeIntLiteralResultType() = assertResultType("42", "Int")

    @Test
    fun testAnalyzeLongLiteralResultType() = assertResultType("42L", "Long")

    @Test
    fun testAnalyzeDoubleLiteralResultType() = assertResultType("3.14", "Double")

    @Test
    fun testAnalyzeFloatLiteralResultType() = assertResultType("1.5f", "Float")

    @Test
    fun testAnalyzeBooleanLiteralResultType() = assertResultType("true", "Boolean")

    @Test
    fun testAnalyzeCharLiteralResultType() = assertResultType("'c'", "Char")

    @Test
    fun testAnalyzeStringLiteralResultType() = assertResultType("\"hi\"", "String")

    @Test
    fun testAnalyzeStringTemplateResultType() = assertResultType("\"x=\${1 + 1}\"", "String")

    @Test
    fun testAnalyzeIntArithmeticResultType() = assertResultType("1 + 2 * 3", "Int")

    @Test
    fun testAnalyzeMixedArithmeticResultType() = assertResultType("1 + 2.0", "Double")

    @Test
    fun testAnalyzeIntDivisionResultType() = assertResultType("10 / 3", "Int")

    @Test
    fun testAnalyzeModuloResultType() = assertResultType("10 % 3", "Int")

    @Test
    fun testAnalyzeBooleanAndResultType() = assertResultType("true && false", "Boolean")

    @Test
    fun testAnalyzeComparisonResultType() = assertResultType("1 < 2", "Boolean")

    @Test
    fun testAnalyzeEqualityResultType() = assertResultType("1 == 2", "Boolean")

    @Test
    fun testAnalyzeNegationResultType() = assertResultType("!false", "Boolean")

    @Test
    fun testAnalyzeStringLengthResultType() = assertResultType("\"abc\".length", "Int")

    @Test
    fun testAnalyzeStringUppercaseResultType() = assertResultType("\"abc\".uppercase()", "String")

    @Test
    fun testAnalyzeStringRepeatResultType() = assertResultType("\"ab\".repeat(3)", "String")

    @Test
    fun testAnalyzeStringSplitResultType() = assertResultType("\"a,b\".split(\",\")", "List<String>")

    @Test
    fun testAnalyzeListOfResultType() = assertResultType("listOf(1, 2, 3)", "List<Int>")

    @Test
    fun testAnalyzeSetOfResultType() = assertResultType("setOf(\"a\")", "Set<String>")

    @Test
    fun testAnalyzeMapOfResultType() = assertResultType("mapOf(1 to \"a\")", "Map<Int, String>")

    @Test
    fun testAnalyzeMutableListResultType() = assertResultType("mutableListOf(1)", "MutableList<Int>")

    @Test
    fun testAnalyzePairResultType() = assertResultType("1 to \"a\"", "Pair<Int, String>")

    @Test
    fun testAnalyzeArrayOfResultType() = assertResultType("arrayOf(1, 2)", "Array<Int>")

    @Test
    fun testAnalyzeIntArrayResultType() = assertResultType("intArrayOf(1, 2)", "IntArray")

    @Test
    fun testAnalyzeRangeToListResultType() = assertResultType("(1..5).toList()", "List<Int>")

    @Test
    fun testAnalyzeListSumResultType() = assertResultType("listOf(1, 2, 3).sum()", "Int")

    @Test
    fun testAnalyzeListMapResultType() = assertResultType("listOf(1, 2).map { it * 2 }", "List<Int>")

    @Test
    fun testAnalyzeDataClassIsClean() = assertClean("data class PointA(val x: Int, val y: Int)")

    @Test
    fun testAnalyzeEnumClassIsClean() = assertClean("enum class ColorA { RED, GREEN, BLUE }")

    @Test
    fun testAnalyzeSealedClassHierarchyIsClean() =
        assertClean("sealed class ShapeA\nclass CircleA : ShapeA()\nclass SquareA : ShapeA()")

    @Test
    fun testAnalyzeInterfaceIsClean() = assertClean("interface GreeterA { fun greet(): String }")

    @Test
    fun testAnalyzeObjectDeclarationIsClean() = assertClean("object ConfigA { val name = \"x\" }")

    @Test
    fun testAnalyzeGenericClassIsClean() = assertClean("class BoxA<T>(val value: T)")

    @Test
    fun testAnalyzeExtensionFunctionIsClean() = assertClean("fun Int.doubledA(): Int = this * 2")

    @Test
    fun testAnalyzeInfixFunctionIsClean() = assertClean("infix fun Int.combineA(other: Int): Int = this + other")

    @Test
    fun testAnalyzeDefaultArgsFunctionIsClean() = assertClean("fun greetA(name: String = \"world\") = \"hi \$name\"")

    @Test
    fun testAnalyzeVarargFunctionIsClean() = assertClean("fun sumAllA(vararg xs: Int): Int = xs.sum()")

    @Test
    fun testAnalyzeWhenExpressionIsClean() =
        assertClean("val n = 2\nval r = when (n) { 1 -> \"a\"; else -> \"b\" }")

    @Test
    fun testAnalyzeIfExpressionIsClean() = assertClean("val a = if (true) 1 else 2")

    @Test
    fun testAnalyzeForLoopIsClean() = assertClean("for (i in 1..3) { println(i) }")

    @Test
    fun testAnalyzeWhileLoopIsClean() = assertClean("var w = 0\nwhile (w < 3) { w++ }")

    @Test
    fun testAnalyzeDoWhileLoopIsClean() = assertClean("var d = 0\ndo { d++ } while (d < 3)")

    @Test
    fun testAnalyzeTryCatchIsClean() = assertClean("val t = try { 1 } catch (e: Exception) { 0 }")

    @Test
    fun testAnalyzeLambdaValIsClean() = assertClean("val f: (Int) -> Int = { it + 1 }")

    @Test
    fun testAnalyzeHigherOrderFunctionIsClean() = assertClean("fun applyTwiceA(x: Int, op: (Int) -> Int): Int = op(op(x))")

    @Test
    fun testAnalyzeTypeAliasIsClean() = assertClean("typealias StringMapA = Map<String, String>")

    @Test
    fun testAnalyzeAnnotationClassIsClean() = assertClean("annotation class MarkerA")

    @Test
    fun testAnalyzeNestedClassIsClean() = assertClean("class OuterA { class InnerA }")

    @Test
    fun testAnalyzeCompanionObjectIsClean() = assertClean("class WithCompA { companion object { const val ID = 1 } }")

    @Test
    fun testAnalyzeNullableSafeCallIsClean() = assertClean("val sn: String? = null\nval len = sn?.length")

    @Test
    fun testAnalyzeElvisOperatorIsClean() = assertClean("val sn2: String? = null\nval len2 = sn2?.length ?: 0")

    @Test
    fun testAnalyzeSmartCastIsClean() =
        assertClean("val any: Any = \"x\"\nval up = if (any is String) any.length else 0")

    @Test
    fun testAnalyzeTypeMismatchError() = assertHasError("val x: Int = \"str\"")

    @Test
    fun testAnalyzeUnresolvedReferenceError() = assertHasError("undefinedThingA")

    @Test
    fun testAnalyzeUnresolvedCallError() = assertHasError("undefinedFuncA()")

    @Test
    fun testAnalyzeWrongArgumentTypeError() = ideServicesTest {
        commit("fun needIntA(x: Int) = x")
        assertTrue(analyze("needIntA(\"s\")").errors().isNotEmpty(), "Expected argument-type mismatch error")
    }

    @Test
    fun testAnalyzeMissingArgumentError() = ideServicesTest {
        commit("fun needIntB(x: Int) = x")
        assertTrue(analyze("needIntB()").errors().isNotEmpty(), "Expected missing-argument error")
    }

    @Test
    fun testAnalyzeTooManyArgumentsError() = ideServicesTest {
        commit("fun needIntC(x: Int) = x")
        assertTrue(analyze("needIntC(1, 2)").errors().isNotEmpty(), "Expected too-many-arguments error")
    }

    @Test
    fun testAnalyzeValReassignmentError() = assertHasError("val a = 1\na = 2")

    @Test
    fun testAnalyzeUnresolvedMemberError() = assertHasError("\"s\".noSuchMemberA()")

    @Test
    fun testAnalyzeTypeArgumentMismatchError() = assertHasError("listOf<Int>(\"s\")")

    @Test
    fun testAnalyzeIntPlusStringError() = assertHasError("1 + \"s\" + 1")

    @Test
    fun testAnalyzeNullForNonNullError() = assertHasError("val s: String = null")

    @Test
    fun testAnalyzeUnsafeCallError() = assertHasError("val n: Int? = null\nn.inc()")

    @Test
    fun testAnalyzeConditionTypeMismatchError() = assertHasError("if (1) { }")

    @Test
    fun testAnalyzeForLoopNotIterableError() = assertHasError("for (i in 5) { }")

    @Test
    fun testAnalyzeListElementTypeMismatchError() = assertHasError("val x: List<Int> = listOf(\"a\")")

    @Test
    fun testAnalyzeReturnTypeMismatchError() = assertHasError("fun fRetA(): Int { return \"s\" }")

    @Test
    fun testAnalyzeMissingReturnError() = assertHasError("fun fRetB(): Int { }")

    @Test
    fun testAnalyzeAbstractInstantiationError() = assertHasError("abstract class AbsA\nAbsA()")

    @Test
    fun testAnalyzeInterfaceInstantiationError() = assertHasError("interface IfaceA\nIfaceA()")

    @Test
    fun testAnalyzeThrowNonThrowableError() = assertHasError("throw 42")

    @Test
    fun testAnalyzeWhileConditionTypeError() = assertHasError("while (\"x\") { }")

    @Test
    fun testAnalyzeCallNonFunctionError() = assertHasError("val y = 5\ny()")

    @Test
    fun testAnalyzeWrongReceiverExtensionError() = assertHasError("fun Int.extA() {}\n\"s\".extA()")

    @Test
    fun testAnalyzeDivideStringError() = assertHasError("\"a\" / 2")

    @Test
    fun testAnalyzeIncompatibleAssignmentError() = assertHasError("var vx = 1\nvx = \"s\"")

    @Test
    fun testCompleteLocalValPure() = ideServicesTest {
        val variants = complete("val localValA = 1\nlocalVal")
        variants.assertContains(SourceCodeCompletionVariant("localValA", "localValA", "Int", "property"))
    }

    @Test
    fun testCompleteLocalVarPure() = ideServicesTest {
        val variants = complete("var localVarA = \"s\"\nlocalVar")
        variants.assertContains(SourceCodeCompletionVariant("localVarA", "localVarA", "String", "property"))
    }

    @Test
    fun testCompleteLocalFunPure() = ideServicesTest {
        val variants = complete("fun localFunA(x: Int) = x\nlocalFun")
        variants.assertContains(SourceCodeCompletionVariant("localFunA", "localFunA(x: Int)", "Int", "method"))
    }

    @Test
    fun testCompleteLocalClassPure() = ideServicesTest {
        commit("class LocalWidgetA")
        val variants = complete("LocalWidg")
        assertTrue(
            variants.any { it.text == "LocalWidgetA" && it.icon == "class" },
            "Expected locally declared class `LocalWidgetA`, got: $variants"
        )
    }

    @Test
    fun testCompleteLocalObjectPure() = ideServicesTest {
        commit("object LocalRegistryA { val id = 1 }")
        val variants = complete("LocalRegist")
        assertTrue(variants.any { it.text == "LocalRegistryA" }, "Expected object `LocalRegistryA`, got: $variants")
    }

    @Test
    fun testCompleteMultipleLocalsPure() = ideServicesTest {
        val variants = complete("val alphaA = 1\nval alphaB = \"s\"\nalpha")
        variants.assertContains(SourceCodeCompletionVariant("alphaA", "alphaA", "Int", "property"))
        variants.assertContains(SourceCodeCompletionVariant("alphaB", "alphaB", "String", "property"))
    }

    @Test
    fun testCompleteLambdaParamPure() = ideServicesTest {
        val code = "listOf(1, 2, 3).map { squaredItem -> squaredIt }"
        val cursor = code.indexOf("squaredIt ") + "squaredIt".length
        val variants = complete(code, cursor)
        variants.assertContains(SourceCodeCompletionVariant("squaredItem", "squaredItem", "Int", "property"))
    }

    @Test
    fun testCompleteForLoopVarPure() = ideServicesTest {
        val code = "for (indexVarA in 1..3) {\n  indexVar\n}"
        val cursor = code.indexOf("indexVar\n") + "indexVar".length
        val variants = complete(code, cursor)
        variants.assertContains(SourceCodeCompletionVariant("indexVarA", "indexVarA", "Int", "property"))
    }

    @Test
    fun testCompleteHistoryValPure() = ideServicesTest {
        commit("val committedValA = 42")
        val variants = complete("committedVal")
        variants.assertContains(SourceCodeCompletionVariant("committedValA", "committedValA", "Int", "property"))
    }

    @Test
    fun testCompleteHistoryFunPure() = ideServicesTest {
        commit("fun committedFunA(x: Int) = x")
        val variants = complete("committedFun")
        variants.assertContains(SourceCodeCompletionVariant("committedFunA", "committedFunA(x: Int)", "Int", "method"))
    }

    @Test
    fun testCompleteHistoryClassMemberPure() = ideServicesTest {
        commit("class BoxB(val width: Int, val label: String)")
        commit("val boxB = BoxB(1, \"a\")")
        val variants = complete("boxB.")
        variants.assertContains(SourceCodeCompletionVariant("width", "width", "Int", "property"))
        variants.assertContains(SourceCodeCompletionVariant("label", "label", "String", "property"))
    }

    @Test
    fun testCompleteStringMemberPure() = ideServicesTest {
        commit("val strB = \"abc\"")
        val variants = complete("strB.")
        assertTrue(variants.any { it.text == "length" && it.icon == "property" }, "Expected `length`, got: $variants")
    }

    @Test
    fun testCompleteIntMemberPure() = ideServicesTest {
        commit("val myIntB = 42")
        val variants = complete("myIntB.")
        assertTrue(variants.any { it.text == "toLong" && it.icon == "method" }, "Expected `toLong`, got: $variants")
    }

    @Test
    fun testCompleteListMemberPure() = ideServicesTest {
        commit("val myListB = listOf(1, 2, 3)")
        val variants = complete("myListB.")
        assertTrue(variants.any { it.text == "size" && it.icon == "property" }, "Expected `size`, got: $variants")
    }

    @Test
    fun testCompleteFunctionShowsSourceDefaultValues() = ideServicesTest {
        commit("fun greetC(name: String, times: Int = 3, greeting: String = \"Hi\") = name")
        val variants = complete("greetC")
        assertTrue(
            variants.any { it.text == "greetC" && it.displayText == "greetC(name: String, times: Int = 3, greeting: String = \"Hi\")" },
            "Expected rendered source default values in the signature, got: $variants"
        )
    }

    @Test
    fun testCompleteConstructorShowsSourceDefaultValues() = ideServicesTest {
        commit("class WidgetC(val id: Int, val label: String = \"def\")")
        val variants = complete("WidgetC")
        assertTrue(
            variants.any { it.icon == "constructor" && it.displayText == "WidgetC(id: Int, label: String = \"def\")" },
            "Expected constructor signature with rendered source default value, got: $variants"
        )
    }

    @Test
    fun testCompleteBinaryDefaultValuesRenderedAsEllipsis() = ideServicesTest {
        val code = "listOf(1, 2, 3).joinToStrin"
        val variants = complete(code)
        val joinToString = variants.firstOrNull { it.text == "joinToString" && it.displayText.contains("= ...") }
        assertTrue(
            joinToString != null,
            "Expected `joinToString` with stdlib defaults rendered as `= ...`, got: $variants"
        )
    }

    @Test
    fun testCompleteStdlibExtensionOnListReceiverPure() = ideServicesTest {
        val code = "listOf(1, 2, 3).ma"
        val variants = complete(code)
        assertTrue(
            variants.any { it.text == "map" && it.icon == "method" },
            "Expected `Iterable.map` extension on an explicit `List` receiver, got: $variants"
        )
    }

    @Test
    fun testCompleteExtensionOnListReceiverInsideDslLambda() = ideServicesTest(markedDslScriptCompilationConfiguration) {
        val code = "flows {\n  listOf(1, 2, 3).ma\n}"
        val cursor = code.indexOf(".ma") + ".ma".length
        val variants = complete(code, cursor)
        assertTrue(
            variants.any { it.text == "map" && it.icon == "method" },
            "Expected `Iterable.map` on an explicit `List` receiver inside a `flows { }` lambda, got: $variants"
        )
    }

    @Test
    fun testCompleteExtensionOnChainedCallReceiverInsideDslLambda() = ideServicesTest(
        templateFlowsConfiguration,
        playgroundHostConfiguration,
    ) {
        val code = "flows {\n  listOf(\"string\").map { it.lowercase() }.f\n}"
        val cursor = code.indexOf(".f\n") + ".f".length
        val variants = complete(code, cursor)
        assertTrue(
            variants.any { it.text == "filter" && it.icon == "method" },
            "Expected `Iterable.filter` on a chained `.map { }` receiver inside a `flows { }` lambda, got: $variants"
        )
    }

    @Test
    fun testCompleteExtensionAfterLambdaArgChainInsideDslLambda() = ideServicesTest(
        templateFlowsConfiguration,
        playgroundHostConfiguration,
    ) {
        val code = "flows {\n  listOf(\"string\").map { it.lowercase() }.filter { !it.isBlank() }.toL\n}"
        val cursor = code.indexOf(".toL") + ".toL".length
        val variants = complete(code, cursor)
        assertTrue(
            variants.any { it.text == "toList" && it.icon == "method" },
            "Expected `Iterable.toList` after a `.filter { }` chain inside a `flows { }` lambda, got: $variants"
        )
    }

    @Test
    fun testCompleteExtensionOnChainedReceiverUnterminatedDslLambda() = ideServicesTest(
        templateFlowsConfiguration,
        playgroundHostConfiguration,
    ) {
        val code = "flows {\n  listOf(\"string\").map { it.lowercase() }.f"
        val variants = complete(code)
        assertTrue(
            variants.any { it.text == "filter" && it.icon == "method" },
            "Expected `Iterable.filter` on a chained receiver in an unterminated `flows { }` lambda, got: $variants"
        )
    }

    @Test
    fun testCompleteExtensionAfterLambdaArgChainUnterminatedDslLambda() = ideServicesTest(
        templateFlowsConfiguration,
        playgroundHostConfiguration,
    ) {
        val code = "flows {\n  listOf(\"string\").map { it.lowercase() }.filter { !it.isBlank() }.toL"
        val variants = complete(code)
        assertTrue(
            variants.any { it.text == "toList" && it.icon == "method" },
            "Expected `Iterable.toList` after a `.filter { }` chain in an unterminated `flows { }` lambda, got: $variants"
        )
    }

    @Test
    fun testCompleteInapplicableExtensionAbsentOnListReceiverPure() = ideServicesTest {
        val code = "listOf(1, 2, 3).re"
        val variants = complete(code)
        assertTrue(
            variants.none { it.text == "readText" },
            "`File.readText` is not applicable to a `List` receiver and must not appear, got: $variants"
        )
    }

    @Test
    fun testCompleteStringBuilderMemberPure() = ideServicesTest {
        commit("val sbB = StringBuilder()")
        val variants = complete("sbB.")
        assertTrue(variants.any { it.text == "append" && it.icon == "method" }, "Expected `append`, got: $variants")
    }

    @Test
    fun testCompleteTopLevelMutableListOfPure() = ideServicesTest {
        val variants = complete("mutableListO")
        assertTrue(variants.any { it.text == "mutableListOf" && it.icon == "method" }, "Expected `mutableListOf`, got: $variants")
    }

    @Test
    fun testCompleteTopLevelPrintlnPure() = ideServicesTest {
        val variants = complete("printl")
        assertTrue(variants.any { it.text == "println" && it.icon == "method" }, "Expected `println`, got: $variants")
    }

    @Test
    fun testCompleteStdlibClassPure() = ideServicesTest {
        val variants = complete("StringBuild")
        assertTrue(variants.any { it.text == "StringBuilder" && it.icon == "class" }, "Expected class `StringBuilder`, got: $variants")
    }

    @Test
    fun testCompleteImportPackagePure() = ideServicesTest {
        val variants = complete("import kotlin.te")
        assertTrue(variants.any { it.text == "text" && it.icon == "package" }, "Expected package `text`, got: $variants")
    }

    @Test
    fun testPureKotlinCompletionRespectsExtensionApplicability() = ideServicesTest {
        val broad = complete("als")
        assertTrue(broad.any { it.text == "also" }, "Expected broadly-applicable extension `also`, got: $broad")
        val distinctExt = complete("dist")
        assertTrue(
            distinctExt.none { it.text == "distinct" },
            "`Iterable.distinct` must not leak without an Iterable receiver, got: $distinctExt"
        )
        val filterExt = complete("filt")
        assertTrue(filterExt.none { it.text == "filter" }, "`Iterable.filter` must not leak without an Iterable receiver, got: $filterExt")
    }

    @Test
    fun testCompleteRanksLocalDeclarationAboveImported() = ideServicesTest {
        val variants = complete("val alsoLocalRank = 1\nalso")
        val localIndex = variants.indexOfFirst { it.text == "alsoLocalRank" }
        val stdlibIndex = variants.indexOfFirst { it.text == "also" }
        assertTrue(localIndex >= 0, "Expected local `alsoLocalRank`, got: $variants")
        assertTrue(stdlibIndex >= 0, "Expected stdlib `also`, got: $variants")
        assertTrue(
            localIndex < stdlibIndex,
            "A snippet-local declaration should rank above an imported one, got order: $variants"
        )
    }

    @Test
    fun testCompleteRanksExpectedTypeMatchFirst() = ideServicesTest {
        val variants = complete("fun zqAlpha(): Int = 0\nfun zqBeta(): String = \"\"\nval s: String = zq")
        val betaIndex = variants.indexOfFirst { it.text == "zqBeta" }
        val alphaIndex = variants.indexOfFirst { it.text == "zqAlpha" }
        assertTrue(betaIndex >= 0 && alphaIndex >= 0, "Expected both `zq` candidates, got: $variants")
        assertTrue(
            betaIndex < alphaIndex,
            "A `String`-returning candidate should rank first for `val s: String =`, got order: $variants"
        )
    }

    @Test
    fun testCompleteDedupsSymbolReachableFromMultipleScopes() = ideServicesTest {
        val variants = complete("also")
        assertEquals(
            1, variants.count { it.text == "also" },
            "`also` should appear exactly once after rank-aware dedup, got: $variants"
        )
    }

    @Test
    fun testCompleteOffersKeyword() = ideServicesTest {
        val variants = complete("retur")
        assertTrue(
            variants.any { it.text == "return" && it.icon == "keyword" },
            "Expected the `return` keyword completion, got: $variants"
        )
    }

    @Test
    fun testCompleteDoesNotOfferKeywordAfterDot() = ideServicesTest {
        val variants = complete("\"abc\".whil")
        assertTrue(
            variants.none { it.icon == "keyword" },
            "Keywords must not be offered in a qualified (member) position, got: $variants"
        )
    }

    @Test
    fun testCompleteMarksDeprecatedSymbol() = ideServicesTest(deprecationCompletionConfiguration) {
        val variants = complete("ideDeprecatedTopLevel")
        val deprecated = variants.firstOrNull { it.text == "ideDeprecatedTopLevelFun" }
        assertTrue(deprecated != null, "Expected `ideDeprecatedTopLevelFun`, got: $variants")
        assertEquals(
            DeprecationLevel.WARNING, deprecated.deprecationLevel,
            "Expected the deprecation level to be surfaced on the variant, got: ${deprecated.deprecationLevel}"
        )
    }

    @Test
    fun testCompleteHidesHiddenDeprecatedSymbol() = ideServicesTest(deprecationCompletionConfiguration) {
        val variants = complete("ideHiddenTopLevel")
        assertTrue(
            variants.none { it.text == "ideHiddenTopLevelFun" },
            "A `@Deprecated(level = HIDDEN)` symbol must not be offered, got: $variants"
        )
    }

    @Test
    fun testCompleteHidesPrivateMember() = ideServicesTest {
        commit("val vbox = org.jetbrains.kotlin.scripting.compiler.test.IdeVisibilityBox()")
        val variants = complete("vbox.")
        assertTrue(
            variants.any { it.text == "shownProp" && it.icon == "property" },
            "Expected the public `shownProp`, got: $variants"
        )
        assertTrue(
            variants.none { it.text == "hiddenProp" },
            "A private member must not be offered on a receiver, got: $variants"
        )
    }

    private fun ideServicesTest(
        compilationConfiguration: ScriptCompilationConfiguration = baseCompilationConfiguration,
        hostConfiguration: ScriptingHostConfiguration? = null,
        body: IdeServicesTestContext.() -> Unit,
    ) {
        if (isK1) return
        val messageCollector = ScriptDiagnosticsMessageCollector(null)
        val disposable = Disposer.newDisposable("Disposable for K2ReplIdeServicesTest")
        try {
            val state =
                if (hostConfiguration != null)
                    K2JvmReplCompilerWithIdeServices.createCompilationState(
                        messageCollector, disposable, compilationConfiguration, hostConfiguration
                    )
                else
                    K2JvmReplCompilerWithIdeServices.createCompilationState(messageCollector, disposable, compilationConfiguration)
            val services = K2JvmReplCompilerWithIdeServices(state)
            IdeServicesTestContext(services, compilationConfiguration).body()
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private class IdeServicesTestContext(
        private val services: K2JvmReplCompilerWithIdeServices,
        private val configuration: ScriptCompilationConfiguration,
    ) {
        private var counter = 0

        private fun source(code: String): SourceCode = code.toScriptSource("Snippet_${counter++}.repl.kts")

        fun commit(code: String) {
            val res = runBlocking { services.compile(source(code), configuration) }
            if (res is ResultWithDiagnostics.Failure) {
                fail("Failed to compile committed snippet:\n$code\n" + res.reports.joinToString("\n") { it.message })
            }
        }

        fun complete(code: String, cursor: Int = code.length): List<SourceCodeCompletionVariant> {
            val src = source(code)
            val res = runBlocking { services.complete(src, cursor.toSourceCodePosition(src), configuration) }
            return res.valueOrThrow().toList()
        }

        fun analyze(code: String): ReplAnalyzerResult {
            val src = source(code)
            return runBlocking { services.analyze(src, SourceCode.Position(0, 0), configuration) }.valueOrThrow()
        }
    }

    private fun List<SourceCodeCompletionVariant>.assertContains(expected: SourceCodeCompletionVariant) {
        assertTrue(contains(expected), "Expected completion $expected, got: $this")
    }

    private fun ReplAnalyzerResult.errors(): List<ScriptDiagnostic> =
        (this[ReplAnalyzerResult.analysisDiagnostics] ?: emptySequence()).filter {
            it.severity == ScriptDiagnostic.Severity.ERROR || it.severity == ScriptDiagnostic.Severity.FATAL
        }.toList()

    companion object {
        private val baseCompilationConfiguration: ScriptCompilationConfiguration =
            ScriptCompilationConfiguration {
                val classpath = System.getProperty("kotlin.test.script.classpath")?.split(File.pathSeparator)
                    ?.mapNotNull { File(it).takeIf { file -> file.exists() } }.orEmpty()
                updateClasspath(classpath + ForTestCompileRuntime.runtimeJarForTests())
                compilerOptions("-Xrender-internal-diagnostic-names=true")
            }

        private val receiverCompilationConfiguration: ScriptCompilationConfiguration =
            baseCompilationConfiguration.with {
                implicitReceivers(IdeCompletionReceiver::class)
            }

        private val defaultImportsCompilationConfiguration: ScriptCompilationConfiguration =
            baseCompilationConfiguration.with {
                defaultImports("org.jetbrains.kotlin.scripting.compiler.test.ideCompletionTopLevelFun")
            }

        private val deprecationCompletionConfiguration: ScriptCompilationConfiguration =
            baseCompilationConfiguration.with {
                defaultImports(
                    "org.jetbrains.kotlin.scripting.compiler.test.ideDeprecatedTopLevelFun",
                    "org.jetbrains.kotlin.scripting.compiler.test.ideHiddenTopLevelFun",
                )
            }

        private val baseClassCompilationConfiguration: ScriptCompilationConfiguration =
            baseCompilationConfiguration.with {
                baseClass(IdeCompletionScriptBase::class)
            }

        private val dslScriptCompilationConfiguration: ScriptCompilationConfiguration =
            baseCompilationConfiguration.with {
                baseClass(TestDslScriptBase::class)
            }

        private val nestedDslScriptCompilationConfiguration: ScriptCompilationConfiguration =
            baseCompilationConfiguration.with {
                baseClass(TestNestedDslScriptBase::class)
            }

        private val delegatingDslScriptCompilationConfiguration: ScriptCompilationConfiguration =
            baseCompilationConfiguration.with {
                baseClass(TestDelegatingScriptBase::class)
            }

        private val markedDslScriptCompilationConfiguration: ScriptCompilationConfiguration =
            baseCompilationConfiguration.with {
                baseClass(TestMarkedDslScriptBase::class)
            }

        private val templateLikeMarkedConfiguration: ScriptCompilationConfiguration =
            baseCompilationConfiguration.with {
                baseClass(TestMarkedDslScriptBase::class)
                defaultImports(TestMarkedDsl::class, TestGroupAStep::class, TestGroupBStep::class)
                ide {
                    acceptedLocations(ScriptAcceptedLocation.Everywhere)
                }
            }

        private val faithfulFlowsConfiguration: ScriptCompilationConfiguration =
            baseCompilationConfiguration.with {
                baseClass(TestMarkedDelegatingScriptBase::class)
                defaultImports(TestMarkedDsl::class, TestGroupAStep::class, TestGroupBStep::class)
                ide {
                    acceptedLocations(ScriptAcceptedLocation.Everywhere)
                }
            }

        private val templateFlowsConfiguration: ScriptCompilationConfiguration =
            createScriptDefinitionFromTemplate(
                KotlinType(TestTemplateFlowsScriptBase::class),
                defaultJvmScriptingHostConfiguration,
            ).compilationConfiguration

        private val playgroundHostConfiguration: ScriptingHostConfiguration =
            ScriptingHostConfiguration { }.withDefaultsFrom(defaultJvmScriptingHostConfiguration)

        private const val DEPENDENCY_CLASS_SIMPLE_NAME = "IdeCompletionReceiver"
        private const val DEPENDENCY_CLASS_PACKAGE = "org.jetbrains.kotlin.scripting.compiler.test"
        private const val DEPENDENCY_CLASS_FILE = "org/jetbrains/kotlin/scripting/compiler/test/IdeCompletionReceiver.class"

        private val fullScriptClasspath: List<File> =
            System.getProperty("kotlin.test.script.classpath")?.split(File.pathSeparator)
                ?.mapNotNull { File(it).takeIf(File::exists) }.orEmpty()

        private fun File.containsClassFile(relativePath: String): Boolean = when {
            isDirectory -> File(this, relativePath).exists()
            else -> runCatching { ZipFile(this).use { it.getEntry(relativePath) != null } }.getOrDefault(false)
        }

        private val dependencyClasspathEntry: File =
            (fullScriptClasspath.firstOrNull { it.containsClassFile(DEPENDENCY_CLASS_FILE) }
                ?: error("Could not locate the classpath entry containing $DEPENDENCY_CLASS_FILE"))

        private val classpathWithoutDependency: List<File> =
            (fullScriptClasspath + ForTestCompileRuntime.runtimeJarForTests())
                .filterNot { it.canonicalPath == dependencyClasspathEntry.canonicalPath }

        private val dependencyInConfigurationConfiguration: ScriptCompilationConfiguration =
            ScriptCompilationConfiguration {
                updateClasspath(classpathWithoutDependency + dependencyClasspathEntry)
                compilerOptions("-Xrender-internal-diagnostic-names=true")
            }

        private val withoutDependencyConfiguration: ScriptCompilationConfiguration =
            ScriptCompilationConfiguration {
                updateClasspath(classpathWithoutDependency)
                compilerOptions("-Xrender-internal-diagnostic-names=true")
            }

        private val scriptingDependenciesApiJar: File =
            File(DependsOn::class.java.protectionDomain.codeSource.location.toURI())

        private val dependencyViaDependsOnConfiguration: ScriptCompilationConfiguration =
            ScriptCompilationConfiguration {
                updateClasspath(classpathWithoutDependency + scriptingDependenciesApiJar)
                defaultImports(DependsOn::class)
                compilerOptions("-Xrender-internal-diagnostic-names=true")
                refineConfiguration {
                    onAnnotations(DependsOn::class, handler = ::resolveDependsOnFromFileSystem)
                }
            }

        private fun resolveDependsOnFromFileSystem(
            context: ScriptConfigurationRefinementContext,
        ): ResultWithDiagnostics<ScriptCompilationConfiguration> {
            val annotations = context.collectedData?.get(ScriptCollectedData.collectedAnnotations)?.takeIf { it.isNotEmpty() }
                ?: return context.compilationConfiguration.asSuccess()
            val resolver = CompoundDependenciesResolver(FileSystemDependenciesResolver())
            return runBlocking { resolver.resolveFromScriptSourceAnnotations(annotations) }
                .onSuccess { classpath ->
                    context.compilationConfiguration
                        .let { if (classpath.isEmpty()) it else it.withUpdatedClasspath(classpath) }
                        .asSuccess()
                }
        }
    }
}
