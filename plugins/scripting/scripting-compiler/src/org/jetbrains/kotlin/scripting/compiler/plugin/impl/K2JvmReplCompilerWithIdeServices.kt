/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.scripting.compiler.plugin.impl

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.KtPsiSourceElement
import org.jetbrains.kotlin.cli.common.checkKotlinPackageUsageForPsi
import org.jetbrains.kotlin.cli.common.fir.reportToMessageCollector
import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport
import org.jetbrains.kotlin.cli.common.renderDiagnosticInternalName
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.cli.jvm.config.jvmClasspathRoots
import org.jetbrains.kotlin.compiler.plugin.getCompilerExtensions
import org.jetbrains.kotlin.config.jvmTarget
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.impl.DiagnosticsCollectorImpl
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.SessionConfiguration
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.pipeline.buildFirFromKtFiles
import org.jetbrains.kotlin.fir.pipeline.runCheckers
import org.jetbrains.kotlin.fir.pipeline.runResolution
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.scope
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.FirTowerDataContextCollector
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.collectReplTowerDataContexts
import org.jetbrains.kotlin.fir.scopes.*
import org.jetbrains.kotlin.fir.scopes.impl.FirAbstractSimpleImportingScope
import org.jetbrains.kotlin.fir.scopes.impl.FirAbstractStarImportingScope
import org.jetbrains.kotlin.fir.scopes.impl.FirDefaultStarImportingScope
import org.jetbrains.kotlin.fir.scopes.impl.FirPackageMemberScope
import org.jetbrains.kotlin.fir.session.FirJvmSessionFactory
import org.jetbrains.kotlin.fir.session.KmpModuleKind
import org.jetbrains.kotlin.fir.session.environment.AbstractProjectFileSearchScope
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.deprecation.DeprecationLevelValue
import org.jetbrains.kotlin.text
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getQualifiedExpressionForSelector
import org.jetbrains.kotlin.resolve.jvm.KotlinJavaPsiFacade
import org.jetbrains.kotlin.types.AbstractTypeChecker
import org.jetbrains.kotlin.scripting.compiler.plugin.dependencies.collectScriptsCompilationDependencies
import org.jetbrains.kotlin.scripting.compiler.plugin.fir.FirReplCompletionModeComponent
import org.jetbrains.kotlin.scripting.compiler.plugin.fir.FirScriptCompilationComponent
import org.jetbrains.kotlin.scripting.compiler.plugin.services.FirReplHistoryProviderImpl
import org.jetbrains.kotlin.scripting.compiler.plugin.services.firReplHistoryProvider
import org.jetbrains.kotlin.scripting.compiler.plugin.services.isReplSnippetSource
import org.jetbrains.kotlin.scripting.definitions.K1SpecificScriptingServiceAccessor
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.ScriptPriorities
import org.jetbrains.kotlin.scripting.resolve.KtFileScriptSource
import org.jetbrains.kotlin.scripting.resolve.getKtFile
import java.io.File
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.util.calcAbsolute
import kotlin.script.experimental.util.LinkedSnippet

private const val PROXIMITY_LOCAL = 0
private const val PROXIMITY_MEMBER = 0
private const val PROXIMITY_EXTENSION = 2
private const val PROXIMITY_KEYWORD = 3
private const val PROXIMITY_IMPORT = 4
private const val PROXIMITY_CLASSPATH = 5

private val COMPLETION_KEYWORDS = listOf(
    KtTokens.AS_KEYWORD, KtTokens.BREAK_KEYWORD, KtTokens.CLASS_KEYWORD, KtTokens.CONTINUE_KEYWORD, KtTokens.DO_KEYWORD,
    KtTokens.ELSE_KEYWORD, KtTokens.FALSE_KEYWORD, KtTokens.FOR_KEYWORD, KtTokens.FUN_KEYWORD, KtTokens.IF_KEYWORD,
    KtTokens.IN_KEYWORD, KtTokens.INTERFACE_KEYWORD, KtTokens.IS_KEYWORD, KtTokens.NULL_KEYWORD, KtTokens.OBJECT_KEYWORD,
    KtTokens.PACKAGE_KEYWORD, KtTokens.RETURN_KEYWORD, KtTokens.SUPER_KEYWORD, KtTokens.THIS_KEYWORD, KtTokens.THROW_KEYWORD,
    KtTokens.TRUE_KEYWORD, KtTokens.TRY_KEYWORD, KtTokens.TYPE_ALIAS_KEYWORD, KtTokens.TYPEOF_KEYWORD, KtTokens.VAL_KEYWORD,
    KtTokens.VAR_KEYWORD, KtTokens.WHEN_KEYWORD, KtTokens.WHILE_KEYWORD, KtTokens.BY_KEYWORD, KtTokens.CATCH_KEYWORD,
    KtTokens.CONSTRUCTOR_KEYWORD, KtTokens.DELEGATE_KEYWORD, KtTokens.DYNAMIC_KEYWORD, KtTokens.FIELD_KEYWORD,
    KtTokens.FINALLY_KEYWORD, KtTokens.GET_KEYWORD, KtTokens.IMPORT_KEYWORD, KtTokens.INIT_KEYWORD, KtTokens.PARAM_KEYWORD,
    KtTokens.PROPERTY_KEYWORD, KtTokens.RECEIVER_KEYWORD, KtTokens.SET_KEYWORD, KtTokens.SETPARAM_KEYWORD,
    KtTokens.VALUE_KEYWORD, KtTokens.WHERE_KEYWORD, KtTokens.ABSTRACT_KEYWORD, KtTokens.ANNOTATION_KEYWORD,
    KtTokens.COMPANION_KEYWORD, KtTokens.CONST_KEYWORD, KtTokens.CROSSINLINE_KEYWORD, KtTokens.DATA_KEYWORD,
    KtTokens.ENUM_KEYWORD, KtTokens.EXTERNAL_KEYWORD, KtTokens.FINAL_KEYWORD, KtTokens.INFIX_KEYWORD, KtTokens.INLINE_KEYWORD,
    KtTokens.INNER_KEYWORD, KtTokens.INTERNAL_KEYWORD, KtTokens.LATEINIT_KEYWORD, KtTokens.NOINLINE_KEYWORD,
    KtTokens.OPEN_KEYWORD, KtTokens.OPERATOR_KEYWORD, KtTokens.OUT_KEYWORD, KtTokens.OVERRIDE_KEYWORD,
    KtTokens.PRIVATE_KEYWORD, KtTokens.PROTECTED_KEYWORD, KtTokens.PUBLIC_KEYWORD, KtTokens.REIFIED_KEYWORD,
    KtTokens.SEALED_KEYWORD, KtTokens.SUSPEND_KEYWORD, KtTokens.TAILREC_KEYWORD, KtTokens.VARARG_KEYWORD,
).map { it.value }

class K2JvmReplCompilerWithIdeServices(
    private val state: K2ReplCompilationState,
    private val renderType: (ConeKotlinType) -> String = ConeKotlinType::renderReadable,
    private val customizeVariant: CompletionVariantCustomizer = CompletionVariantCustomizer { _, variant -> variant },
    private val classpathClassNameIndexProvider: ReplClasspathClassNameIndexProvider = DefaultReplClasspathClassNameIndexProvider(),
) : ReplCompiler<CompiledSnippet>, ReplCompleter, ReplCodeAnalyzer {

    fun interface CompletionVariantCustomizer {
        fun customize(symbol: FirBasedSymbol<*>, variant: SourceCodeCompletionVariant): SourceCodeCompletionVariant
    }

    private val compiler = K2ReplCompiler(state)

    override val lastCompiledSnippet: LinkedSnippet<CompiledSnippet>?
        get() = compiler.lastCompiledSnippet

    override suspend fun compile(
        snippets: Iterable<SourceCode>,
        configuration: ScriptCompilationConfiguration,
    ): ResultWithDiagnostics<LinkedSnippet<CompiledSnippet>> = compiler.compile(snippets, configuration)

    override suspend fun analyze(
        snippet: SourceCode,
        cursor: SourceCode.Position,
        configuration: ScriptCompilationConfiguration,
    ): ResultWithDiagnostics<ReplAnalyzerResult> {
        val frontend = runStatelessFrontend(snippet, configuration).valueOr { return it }
        val resultType = frontend.findResultProperty()?.returnTypeRef?.coneTypeOrNull?.let(renderType)
        return ReplAnalyzerResult {
            analysisDiagnostics(frontend.diagnostics.toReportableDiagnostics())
            renderedResultType(resultType)
        }.asSuccess()
    }

    override suspend fun complete(
        snippet: SourceCode,
        cursor: SourceCode.Position,
        configuration: ScriptCompilationConfiguration,
    ): ResultWithDiagnostics<ReplCompletionResult> {
        val cursorOffset = cursor.calcAbsolute(snippet)
        val textWithMarker = snippet.text.substring(0, cursorOffset) + COMPLETION_MARKER + snippet.text.substring(cursorOffset)
        val snippetWithMarker = object : SourceCode {
            override val text: String get() = textWithMarker
            override val name: String? get() = snippet.name
            override val locationId: String? get() = snippet.locationId
        }

        val towerCollector = MarkerTowerDataContextCollector(cursorOffset)
        val frontend = runStatelessFrontend(snippetWithMarker, configuration, towerCollector).valueOr { return it }

        val diagnostics = frontend.diagnostics.filterNot { it.message.contains(COMPLETION_MARKER) }

        val nameExpression = frontend.snippetKtFile.findMarkedNameExpression(cursorOffset)
            ?: return emptySequence<SourceCodeCompletionVariant>().asSuccess(diagnostics)

        val prefix = nameExpression.getReferencedNameElement().text.substringBefore(COMPLETION_MARKER)

        if (PsiTreeUtil.getParentOfType(nameExpression, KtImportDirective::class.java, false) != null) {
            val qualifier = (nameExpression.getQualifiedExpressionForSelector() as? KtDotQualifiedExpression)
                ?.receiverExpression?.text?.let(::FqName) ?: FqName.ROOT
            val importVariants = frontend.collectImportCompletions(state.projectEnvironment.project, qualifier, prefix, renderType)
                .asSequence()
                .filter { it.text.startsWith(prefix) }
                .distinct()
            return importVariants.asSuccess(diagnostics)
        }

        // Built after the frontend ran, so the classpath already reflects any `@file:DependsOn` dependencies the snippet resolved.
        val classpathIndex = classpathClassNameIndexProvider.getIndex(
            state.compilerContext.environment.configuration.jvmClasspathRoots
        )

        val candidates = frontend.collectCandidateSymbols(
            nameExpression, prefix, towerCollector.captured, classpathIndex,
        )

        val expectedType = frontend.expectedTypeAtMarker(nameExpression)

        val symbolVariants = candidates.flatMap { candidate ->
            val deprecation = frontend.deprecationLevelOf(candidate.symbol)
            val fromUserSource = candidate.symbol.origin.fromSource
            val expectedMatch = frontend.matchesExpectedType(candidate.symbol, expectedType)
            val base = frontend.renderCandidate(candidate.symbol, renderType)
                ?.copy(deprecationLevel = deprecation)
                ?.let { customizeVariant.customize(candidate.symbol, it) }
            val constructors = runCatching { frontend.renderConstructorCandidates(candidate.symbol, renderType) }
                .getOrDefault(emptyList())
                .map { it.copy(deprecationLevel = deprecation) }
                .map { customizeVariant.customize(candidate.symbol, it) }
            (listOfNotNull(base) + constructors).map { RankedVariant(it, candidate.proximity, fromUserSource, expectedMatch) }
        }

        val namedArgumentVariants = frontend.namedArgumentVariants(nameExpression, prefix, renderType)
            .map { RankedVariant(it, PROXIMITY_LOCAL, fromUserSource = false, expectedMatch = false) }

        val isQualified = nameExpression.getQualifiedExpressionForSelector() != null
        val keywordVariants = if (isQualified) emptyList() else COMPLETION_KEYWORDS
            .filter { it.startsWith(prefix) }
            .map {
                RankedVariant(
                    SourceCodeCompletionVariant(it, it, "keyword", "keyword"),
                    PROXIMITY_KEYWORD, fromUserSource = false, expectedMatch = false,
                )
            }

        val variants = (symbolVariants + namedArgumentVariants + keywordVariants)
            .asSequence()
            .filter { it.variant.text.startsWith(prefix) }
            .sortedWith(rankedVariantComparator(prefix))
            .distinctBy { it.variant }
            .map { it.variant }

        return variants.asSuccess(diagnostics)
    }

    private class RankedVariant(
        val variant: SourceCodeCompletionVariant,
        val proximity: Int,
        val fromUserSource: Boolean,
        val expectedMatch: Boolean,
    )

    private fun rankedVariantComparator(prefix: String): Comparator<RankedVariant> =
        compareBy(
            { if (it.expectedMatch) 0 else 1 },
            { it.proximity },
            { if (it.fromUserSource) 0 else 1 },
            { if (it.variant.deprecationLevel != null) 1 else 0 },
            { if (it.variant.text == prefix) 0 else 1 },
            { it.variant.text.lowercase() },
            { it.variant.text },
        )

    @OptIn(K1SpecificScriptingServiceAccessor::class, SessionConfiguration::class, KtNonPublicApi::class)
    private fun runStatelessFrontend(
        snippet: SourceCode,
        configuration: ScriptCompilationConfiguration,
        towerDataContextCollector: FirTowerDataContextCollector? = null,
    ): ResultWithDiagnostics<FrontendResult> = withMessageCollector(snippet) { messageCollector ->
        val initialConfiguration = configuration.refineBeforeParsing(snippet).valueOr { return it }
        var moduleDataSnapshot: ReplModuleDataProvider.Snapshot? = null
        val historyProvider = state.hostConfiguration[ScriptingHostConfiguration.repl.firReplHistoryProvider]
                as? FirReplHistoryProviderImpl
        val historySizeSnapshot = historyProvider?.snapshotSize()

        try {
            val project = state.projectEnvironment.project
            val compilerConfiguration = state.compilerContext.environment.configuration.copy().apply {
                jvmTarget = selectJvmTarget(configuration, messageCollector)
            }
            val renderInternalDiagnosticNames = compilerConfiguration.renderDiagnosticInternalName
            val diagnosticsReporter = DiagnosticsCollectorImpl()

            val snippetKtFile = getScriptKtFile(snippet, initialConfiguration, project, messageCollector).valueOr { return it }
            snippetKtFile.script?.markAsReplSnippet()

            val snippetPriority = state.scriptCompilationConfiguration[ScriptCompilationConfiguration.repl.currentLineId]?.no
                ?: state.hostConfiguration[ScriptingHostConfiguration.repl.firReplHistoryProvider]?.getSnippetCount()
            if (snippetPriority != null) {
                snippetKtFile.script?.putUserData(ScriptPriorities.PRIORITY_KEY, snippetPriority)
            }

            val definition = ScriptDefinition.FromConfigurations(state.hostConfiguration, configuration, null)
            val allSourceFiles = mutableListOf<SourceCode>(KtFileScriptSource(snippetKtFile))
            (
                val classpath, val newSources = sources, val sourceDependencies
            ) =
                @Suppress("DEPRECATION")
                collectScriptsCompilationDependencies(allSourceFiles) { source ->
                    state.scriptConfigurationsProvider?.let {
                        it.project = state.project
                        it.getScriptCompilationConfiguration(source, initialConfiguration)
                    }
                }
            allSourceFiles.addAll(newSources)
            val sourceFiles = allSourceFiles.map { it.getKtFile(definition, project) }

            sourceFiles.forEach { AnalyzerWithCompilerReport.reportSyntaxErrors(it, messageCollector) }
            checkKotlinPackageUsageForPsi(compilerConfiguration, sourceFiles)

            val baseCompilerOptions = state.scriptCompilationConfiguration[ScriptCompilationConfiguration.compilerOptions]
            val updatedCompilerOptions = allSourceFiles.flatMapTo(mutableListOf()) { file ->
                state.scriptConfigurationsProvider?.let { provider ->
                    provider.project = state.project
                    provider.getScriptCompilationConfiguration(file)?.valueOrNull()?.configuration?.get(
                        ScriptCompilationConfiguration.compilerOptions
                    )?.takeIf { it != baseCompilerOptions }
                } ?: emptyList()
            }
            if (updatedCompilerOptions.isNotEmpty()) {
                compilerConfiguration.updateWithCompilerOptions(
                    updatedCompilerOptions,
                    messageCollector,
                    state.compilerContext.ignoredOptionsReportingState,
                    true,
                )
            }

            val [libraryModuleData, newClasspathRoots] =
                state.moduleDataProvider.addNewLibraryModuleDataIfNeeded(classpath.map(File::toPath))
            if (newClasspathRoots.isNotEmpty()) {
                state.compilerContext.environment.updateClasspath(newClasspathRoots.map { JvmClasspathRoot(it.toFile()) })
            }

            val extensionRegistrars = compilerConfiguration.getCompilerExtensions(FirExtensionRegistrar)
            if (libraryModuleData != null) {
                val projectEnvironment = state.sessionFactoryContext.projectEnvironment
                val searchScope = state.moduleDataProvider.getModuleDataPaths(libraryModuleData)?.let { paths ->
                    projectEnvironment.getSearchScopeByClassPath(paths)
                } ?: state.sessionFactoryContext.librariesScope
                createScriptingAdditionalLibrariesSession(
                    libraryModuleData,
                    state.sessionFactoryContext,
                    state.moduleDataProvider,
                    state.sharedLibrarySession,
                    extensionRegistrars,
                    compilerConfiguration,
                    getKotlinClassFinder = { projectEnvironment.getKotlinClassFinder(searchScope) },
                    getJavaFacade = { projectEnvironment.getFirJavaFacade(it, libraryModuleData, state.sessionFactoryContext.librariesScope) },
                )
                KotlinJavaPsiFacade.getInstance(project).clearPackageCaches()
            }

            moduleDataSnapshot = state.moduleDataProvider.snapshot()
            val snippetModuleData = state.moduleDataProvider.addNewSnippetModuleData(Name.special("<REPL-ide-${snippet.name}>"))

            val session = FirJvmSessionFactory.createSourceSession(
                snippetModuleData,
                AbstractProjectFileSearchScope.EMPTY,
                createIncrementalCompilationSymbolProviders = { null },
                extensionRegistrars,
                compilerConfiguration,
                context = state.sessionFactoryContext,
                needRegisterJavaElementFinder = true,
                kmpModuleKind = KmpModuleKind.SingleModule,
                init = {},
            )

            session.register(
                FirScriptCompilationComponent::class,
                FirScriptCompilationComponent(state.hostConfiguration),
            )

            // Runs only in the stateless completion/analysis frontend (no codegen). Enables the REPL snippet
            // configurator to expose the base class as a receiver so completion can resolve its members.
            session.register(
                FirReplCompletionModeComponent::class,
                FirReplCompletionModeComponent(),
            )

            val rawFirFiles = session.buildFirFromKtFiles(sourceFiles)
            val [scopeSession, resolvedFirFiles] = session.runResolution(rawFirFiles)
            if (towerDataContextCollector != null) {
                collectReplTowerDataContexts(resolvedFirFiles, session, scopeSession, towerDataContextCollector)
            }
            session.runCheckers(scopeSession, resolvedFirFiles, diagnosticsReporter, MppCheckerKind.Common)
            session.runCheckers(scopeSession, resolvedFirFiles, diagnosticsReporter, MppCheckerKind.Platform)
            diagnosticsReporter.reportToMessageCollector(messageCollector, renderInternalDiagnosticNames)

            FrontendResult(session, scopeSession, resolvedFirFiles, snippetKtFile, messageCollector.diagnostics.toList()).asSuccess()
        } finally {
            moduleDataSnapshot?.let { state.moduleDataProvider.restore(it) }
            if (historyProvider != null && historySizeSnapshot != null) historyProvider.restoreToSize(historySizeSnapshot)
        }
    }

    private class FrontendResult(
        val session: FirSession,
        val scopeSession: ScopeSession,
        val firFiles: List<FirFile>,
        val snippetKtFile: KtFile,
        val diagnostics: List<ScriptDiagnostic>,
    ) {
        fun findResultProperty(): FirProperty? =
            firstFirOrNull {
                it is FirProperty && it.origin == FirDeclarationOrigin.ScriptCustomization.ResultProperty
            } as FirProperty?

        class Candidate(val symbol: FirBasedSymbol<*>, val proximity: Int)

        fun collectCandidateSymbols(
            nameExpression: KtSimpleNameExpression,
            prefix: String,
            capturedTower: FirTowerDataContext?,
            classpathIndex: ReplClasspathClassNameIndex,
        ): List<Candidate> {
            val qualifiedAccess = nameExpression.getQualifiedExpressionForSelector()
            if (qualifiedAccess != null) {
                val access = firElementOfTypeFor<FirQualifiedAccessExpression>(qualifiedAccess)
                val receiver = access?.explicitReceiver ?: return emptyList()
                if (receiver is FirResolvedQualifier) {
                    return collectQualifierSymbols(receiver, prefix).map { Candidate(it, PROXIMITY_MEMBER) }.filterAccessible()
                }
                val receiverType = receiver.resolvedType
                val memberSymbols = receiverType.scope(
                    session, scopeSession, CallableCopyTypeCalculator.DoNothing, requiredMembersPhase = null
                )?.collectNamedSymbols(prefix).orEmpty().map { Candidate(it, PROXIMITY_MEMBER) }
                val extensionSymbols = buildList {
                    capturedTower?.towerDataElements?.asReversed()?.forEach { element ->
                        element.getAvailableScopes().forEach { scope -> addAll(scope.collectNamedSymbols(prefix)) }
                    }
                    firFiles.forEach { file ->
                        createImportingScopes(file, session, scopeSession).forEach { scope ->
                            addAll(scope.collectNamedSymbols(prefix))
                        }
                    }
                }.filter { isExtensionApplicableToReceiver(it, receiverType) }.map { Candidate(it, PROXIMITY_EXTENSION) }
                return (memberSymbols + extensionSymbols).filterAccessible()
            }

            val implicitReceiverTypes = capturedTower?.towerDataElements?.mapNotNull { it.implicitReceiver?.type }.orEmpty()
            val localSymbols = buildList {
                capturedTower?.towerDataElements?.asReversed()?.forEach { element ->
                    element.getAvailableScopes().forEach { scope -> addAll(scope.collectNamedSymbols(prefix)) }
                }
            }.map { Candidate(it, PROXIMITY_LOCAL) }
            val importedSymbols = buildList {
                firFiles.forEach { file ->
                    createImportingScopes(file, session, scopeSession).forEach { scope ->
                        addAll(scope.collectNamedSymbols(prefix))
                    }
                }
            }.map { Candidate(it, PROXIMITY_IMPORT) }
            val classpathClassifiers = collectClasspathClassifierCandidates(classpathIndex, prefix)
            return (localSymbols + importedSymbols + classpathClassifiers)
                .filter { isCandidateApplicable(it.symbol, implicitReceiverTypes) }
                .filterAccessible()
        }

        private fun collectClasspathClassifierCandidates(
            classpathIndex: ReplClasspathClassNameIndex,
            prefix: String,
        ): List<Candidate> {
            if (prefix.isEmpty()) return emptyList()
            val symbolProvider = session.symbolProvider
            return classpathIndex.classIdsByPrefix(prefix)
                .mapNotNull { classId ->
                    symbolProvider.getClassLikeSymbolByClassId(classId)?.let { Candidate(it, PROXIMITY_CLASSPATH) }
                }
                .toList()
        }

        private fun List<Candidate>.filterAccessible(): List<Candidate> =
            filter { isVisibleForCompletion(it.symbol) && deprecationLevelOf(it.symbol) != DeprecationLevel.HIDDEN }

        private fun isVisibleForCompletion(symbol: FirBasedSymbol<*>): Boolean {
            val visibility = when (symbol) {
                is FirCallableSymbol<*> -> symbol.rawStatus.visibility
                is FirClassSymbol<*> -> symbol.rawStatus.visibility
                else -> return true
            }
            return visibility != Visibilities.Private &&
                    visibility != Visibilities.PrivateToThis &&
                    visibility != Visibilities.InvisibleFake
        }

        fun deprecationLevelOf(symbol: FirBasedSymbol<*>): DeprecationLevel? =
            when (symbol.getDeprecation(session, callSite = null)?.deprecationLevel) {
                DeprecationLevelValue.WARNING -> DeprecationLevel.WARNING
                DeprecationLevelValue.ERROR -> DeprecationLevel.ERROR
                DeprecationLevelValue.HIDDEN -> DeprecationLevel.HIDDEN
                null -> null
            }

        fun expectedTypeAtMarker(nameExpression: KtSimpleNameExpression): ConeKotlinType? {
            val exprPsi = (nameExpression.getQualifiedExpressionForSelector() as? KtExpression) ?: nameExpression
            val property = exprPsi.parent as? KtProperty ?: return null
            if (property.initializer !== exprPsi || property.typeReference == null) return null
            return firElementOfTypeFor<FirProperty>(property)?.returnTypeRef?.coneTypeOrNull
        }

        fun matchesExpectedType(symbol: FirBasedSymbol<*>, expectedType: ConeKotlinType?): Boolean {
            if (expectedType == null || expectedType.isUnit || expectedType.isNothing) return false
            val returnType = (symbol as? FirCallableSymbol<*>)?.resolvedReturnType ?: return false
            return AbstractTypeChecker.isSubtypeOf(session.typeContext, returnType, expectedType)
        }

        fun namedArgumentVariants(
            nameExpression: KtSimpleNameExpression,
            prefix: String,
            renderType: (ConeKotlinType) -> String,
        ): List<SourceCodeCompletionVariant> {
            if (nameExpression.getQualifiedExpressionForSelector() != null) return emptyList()
            val valueArgument = nameExpression.parent as? KtValueArgument ?: return emptyList()
            if (valueArgument.isNamed()) return emptyList()
            val argumentList = valueArgument.parent as? KtValueArgumentList ?: return emptyList()
            val callExpression = argumentList.parent as? KtCallExpression ?: return emptyList()
            val functionCall = firElementOfTypeFor<FirFunctionCall>(callExpression) ?: return emptyList()
            val functionSymbol = functionCall.calleeReference.toResolvedFunctionSymbol() ?: return emptyList()
            val alreadyNamed = argumentList.arguments.mapNotNull { it.getArgumentName()?.asName?.asString() }.toSet()
            return functionSymbol.valueParameterSymbols
                .filter { !it.name.isSpecial }
                .mapNotNull { param ->
                    val name = param.name.identifier
                    if (!name.startsWith(prefix) || name in alreadyNamed) return@mapNotNull null
                    SourceCodeCompletionVariant("$name = ", "$name =", renderType(param.resolvedReturnType), "parameter")
                }
        }

        private fun isCandidateApplicable(symbol: FirBasedSymbol<*>, implicitReceiverTypes: List<ConeKotlinType>): Boolean {
            val extensionReceiverType = (symbol as? FirCallableSymbol<*>)?.resolvedReceiverType ?: return true
            val starProjected = (extensionReceiverType as? ConeClassLikeType)?.replaceArgumentsWithStarProjections() ?: return true
            return implicitReceiverTypes.any { AbstractTypeChecker.isSubtypeOf(session.typeContext, it, starProjected) }
        }

        private fun isExtensionApplicableToReceiver(symbol: FirBasedSymbol<*>, receiverType: ConeKotlinType): Boolean {
            val extensionReceiverType = (symbol as? FirCallableSymbol<*>)?.resolvedReceiverType ?: return false
            val starProjected = (extensionReceiverType as? ConeClassLikeType)?.replaceArgumentsWithStarProjections() ?: return true
            return AbstractTypeChecker.isSubtypeOf(session.typeContext, receiverType, starProjected)
        }

        private fun collectQualifierSymbols(qualifier: FirResolvedQualifier, prefix: String): List<FirBasedSymbol<*>> {
            val classSymbol = qualifier.qualifierSymbol as? FirClassSymbol<*>
                ?: return FirPackageMemberScope(qualifier.packageFqName, session).collectNamedSymbols(prefix)
            return buildList {
                classSymbol.staticScope(session, scopeSession)?.let { addAll(it.collectNamedSymbols(prefix)) }
                (classSymbol as? FirRegularClassSymbol)?.resolvedCompanionObjectSymbol?.constructType()
                    ?.scope(session, scopeSession, CallableCopyTypeCalculator.DoNothing, requiredMembersPhase = null)
                    ?.let { addAll(it.collectNamedSymbols(prefix)) }
            }
        }

        fun collectImportCompletions(
            project: Project,
            qualifier: FqName,
            prefix: String,
            renderType: (ConeKotlinType) -> String,
        ): List<SourceCodeCompletionVariant> {
            val subPackages = collectSubPackageNames(project, qualifier, prefix)
                .map { SourceCodeCompletionVariant(it, it, "package", "package") }
            val members = FirPackageMemberScope(qualifier, session)
                .collectNamedSymbols(prefix)
                .mapNotNull { renderCandidate(it, renderType) }
            return subPackages + members
        }

        private fun collectSubPackageNames(project: Project, parent: FqName, prefix: String): List<String> {
            val scope = GlobalSearchScope.allScope(project)
            val psiPackage = KotlinJavaPsiFacade.getInstance(project).findPackage(parent.asString(), scope) ?: return emptyList()
            return psiPackage.getSubPackages(scope)
                .mapNotNull { it.name?.takeIf { name -> prefix.isEmpty() || name.startsWith(prefix) } }
                .distinct()
                .sorted()
        }

        fun renderCandidate(symbol: FirBasedSymbol<*>, renderType: (ConeKotlinType) -> String): SourceCodeCompletionVariant? =
            when (symbol) {
                is FirNamedFunctionSymbol -> {
                    val name = symbol.name.asString()
                    val parameters = symbol.valueParameterSymbols.joinToString(", ") { renderValueParameter(it, renderType) }
                    SourceCodeCompletionVariant(name, "$name($parameters)", renderType(symbol.resolvedReturnType), "method")
                }
                is FirVariableSymbol<*> -> {
                    val name = symbol.name.asString()
                    SourceCodeCompletionVariant(name, name, renderType(symbol.resolvedReturnType), "property")
                }
                is FirClassLikeSymbol<*> -> {
                    val classId = symbol.classId
                    val name = classId.shortClassName.asString()
                    val container = classId.asSingleFqName().parent()
                    val tail = if (container.isRoot) "class" else container.asString()
                    SourceCodeCompletionVariant(name, name, tail, "class")
                }
                is FirTypeParameterSymbol -> {
                    val name = symbol.name.asString()
                    SourceCodeCompletionVariant(name, name, "type parameter", "class")
                }
                else -> null
            }

        fun renderConstructorCandidates(
            symbol: FirBasedSymbol<*>,
            renderType: (ConeKotlinType) -> String,
        ): List<SourceCodeCompletionVariant> {
            val classSymbol = symbol as? FirClassSymbol<*> ?: return emptyList()
            if (classSymbol.classKind != ClassKind.CLASS) return emptyList()
            val classModality = classSymbol.rawStatus.modality
            if (classModality == Modality.ABSTRACT || classModality == Modality.SEALED) return emptyList()
            val name = classSymbol.classId.shortClassName.asString()
            val container = classSymbol.classId.asSingleFqName().parent()
            val tail = if (container.isRoot) "class" else container.asString()
            return classSymbol.constructors(session)
                .filter { it.rawStatus.visibility == Visibilities.Public }
                .map { constructor ->
                    val parameters = constructor.valueParameterSymbols.joinToString(", ") { renderValueParameter(it, renderType) }
                    SourceCodeCompletionVariant(name, "$name($parameters)", tail, "constructor")
                }
        }

        private fun renderValueParameter(
            parameter: FirValueParameterSymbol,
            renderType: (ConeKotlinType) -> String,
        ): String {
            val rendered = "${parameter.name.asString()}: ${renderType(parameter.resolvedReturnType)}"
            if (!parameter.hasDefaultValue) return rendered
            // Deserialized (stdlib/binary) parameters keep only a default-value stub with no source text,
            // so the concrete value is available for source declarations and falls back to `...` otherwise.
            val default = parameter.defaultValueSource.text?.toString()?.trim()
            return if (!default.isNullOrEmpty()) "$rendered = $default" else "$rendered = ..."
        }

        private fun firstFirOrNull(predicate: (FirElement) -> Boolean): FirElement? {
            var match: FirElement? = null
            val visitor = object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    when {
                        match != null -> return
                        predicate(element) -> match = element
                        else -> element.acceptChildren(this)
                    }
                }
            }
            firFiles.forEach { it.accept(visitor) }
            return match
        }

        private inline fun <reified T : FirElement> firElementOfTypeFor(psi: KtElement): T? {
            var match: T? = null
            val visitor = object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (element is T && (element.source as? KtPsiSourceElement)?.psi === psi) match = element
                    element.acceptChildren(this)
                }
            }
            firFiles.forEach { it.accept(visitor) }
            return match
        }

        private fun FirScope.collectNamedSymbols(prefix: String): List<FirBasedSymbol<*>> {
            val namesProvider by lazy(LazyThreadSafetyMode.NONE) { session.symbolProvider.symbolNamesProvider }
            val callableNames: Collection<Name>
            val classifierNames: Collection<Name>
            when (this) {
                is FirDefaultStarImportingScope -> {
                    return first.collectNamedSymbols(prefix) + second.collectNamedSymbols(prefix)
                }
                is FirContainingNamesAwareScope -> {
                    callableNames = getCallableNames()
                    classifierNames = getClassifierNames()
                }
                is FirAbstractStarImportingScope -> {
                    return starImports.flatMap { import ->
                        FirPackageMemberScope(import.packageFqName, session).collectNamedSymbols(prefix)
                    }
                }
                is FirAbstractSimpleImportingScope -> {
                    callableNames = simpleImports.keys
                    classifierNames = simpleImports.keys
                }
                is FirPackageMemberScope -> {
                    callableNames = namesProvider.getTopLevelCallableNamesInPackage(fqName).orEmpty()
                    classifierNames = namesProvider.getTopLevelClassifierNamesInPackage(fqName).orEmpty()
                }
                else -> return emptyList()
            }
            return buildList {
                callableNames.matchingPrefix(prefix).forEach { name ->
                    processFunctionsByName(name) { add(it) }
                    processPropertiesByName(name) { add(it) }
                }
                classifierNames.matchingPrefix(prefix).forEach { name ->
                    processClassifiersByName(name) { add(it) }
                }
            }
        }

        private fun Collection<Name>.matchingPrefix(prefix: String): Collection<Name> =
            if (prefix.isEmpty()) this else filter { !it.isSpecial && it.identifier.startsWith(prefix) }
    }

    private fun KtFile.findMarkedNameExpression(cursorOffset: Int): KtSimpleNameExpression? {
        val leaf = findElementAt(cursorOffset) ?: findElementAt(cursorOffset - 1) ?: return null
        return PsiTreeUtil.getParentOfType(leaf, KtSimpleNameExpression::class.java, false)
    }

    private fun List<ScriptDiagnostic>.toReportableDiagnostics(): Sequence<ScriptDiagnostic> =
        asSequence().filter {
            it.severity == ScriptDiagnostic.Severity.FATAL ||
                    it.severity == ScriptDiagnostic.Severity.ERROR ||
                    it.severity == ScriptDiagnostic.Severity.WARNING
        }

    private class MarkerTowerDataContextCollector(private val cursorOffset: Int) : FirTowerDataContextCollector {
        var captured: FirTowerDataContext? = null
            private set
        private var bestRangeLength = Int.MAX_VALUE

        override fun collect(element: FirElement, context: FirTowerDataContext) {
            val source = element.source ?: return
            val start = source.startOffset
            val end = source.endOffset
            if (cursorOffset in start..end) {
                val length = end - start
                if (length <= bestRangeLength) {
                    bestRangeLength = length
                    captured = context.createSnapshot(keepMutable = false)
                }
            }
        }
    }

    companion object {
        private const val COMPLETION_MARKER = "ABCDEFGHIJKLMNOP"

        fun createCompilationState(
            messageCollector: ScriptDiagnosticsMessageCollector,
            rootDisposable: Disposable,
            scriptCompilationConfiguration: ScriptCompilationConfiguration,
            hostConfiguration: ScriptingHostConfiguration =
                ScriptingHostConfiguration(defaultJvmScriptingHostConfiguration) {
                    repl {
                        firReplHistoryProvider(FirReplHistoryProviderImpl())
                        isReplSnippetSource { _, _ -> true }
                    }
                },
        ): K2ReplCompilationState =
            K2ReplCompiler.createCompilationState(
                messageCollector, rootDisposable, scriptCompilationConfiguration, hostConfiguration
            )
    }
}
