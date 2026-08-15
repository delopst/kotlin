/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.transformers.body.resolve

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirReplSnippet
import org.jetbrains.kotlin.fir.declarations.FirScript
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.declarations.utils.isScriptTopLevelDeclaration
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.extensions.scriptResolutionHacksComponent
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.SessionAndScopeSessionHolder
import org.jetbrains.kotlin.fir.resolve.SessionHolderImpl
import org.jetbrains.kotlin.fir.resolve.dfa.DataFlowAnalyzerContext
import org.jetbrains.kotlin.fir.resolve.transformers.ReturnTypeCalculatorForFullBodyResolve
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularPropertySymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.util.PrivateForInline

fun collectReplTowerDataContexts(
    firFiles: List<FirFile>,
    session: FirSession,
    scopeSession: ScopeSession,
    collector: FirTowerDataContextCollector,
) {
    val holder = SessionHolderImpl(session, scopeSession)
    val visitor = FirReplCompletionContextCollectorVisitor(holder, collector)
    firFiles.forEach { it.accept(visitor) }
}

@OptIn(PrivateForInline::class)
private class FirReplCompletionContextCollectorVisitor(
    private val holder: SessionAndScopeSessionHolder,
    private val collector: FirTowerDataContextCollector,
) : FirVisitorVoid(), SessionAndScopeSessionHolder by holder {

    private val context = BodyResolveContext(
        returnTypeCalculator = ReturnTypeCalculatorForFullBodyResolve.Default,
        dataFlowAnalyzerContext = DataFlowAnalyzerContext(holder.session),
        isContextCollectorMode = true,
    )

    private fun collect(element: FirElement) {
        collector.collect(element, context.towerDataContext)
    }

    override fun visitElement(element: FirElement) {
        collect(element)
        element.acceptChildren(this)
    }

    override fun visitFile(file: FirFile) {
        context.withFile(file) {
            collect(file)
            file.acceptChildren(this)
        }
    }

    override fun visitReplSnippet(replSnippet: FirReplSnippet) {
        collect(replSnippet)
        context.withReplSnippet(replSnippet) {
            replSnippet.snippetClass.accept(this)
        }
    }

    override fun visitScript(script: FirScript) {
        collect(script)
        context.withScript(script) {
            script.acceptChildren(this)
        }
    }

    override fun visitRegularClass(regularClass: FirRegularClass) {
        collect(regularClass)
        context.withContainingClass(regularClass) {
            context.forRegularClassBody(regularClass) {
                regularClass.acceptChildren(this)
            }
        }
    }

    override fun visitNamedFunction(namedFunction: FirNamedFunction) {
        collect(namedFunction)
        context.withNamedFunction(namedFunction, holder.session) {
            context.forFunctionBody(namedFunction) {
                namedFunction.acceptChildren(this)
            }
        }
    }

    override fun visitProperty(property: FirProperty) {
        collect(property)
        context.withProperty(property) {
            context.withParameters(property) {
                property.contextParameters.forEach { it.accept(this) }
            }
            val skipCleanup = property.symbol is FirRegularPropertySymbol &&
                    property.isScriptTopLevelDeclaration == true &&
                    holder.session.scriptResolutionHacksComponent?.skipTowerDataCleanupForTopLevelInitializers == true
            context.forPropertyInitializer(skipCleanup) {
                property.initializer?.accept(this)
                property.delegate?.accept(this)
                property.backingField?.accept(this)
            }
        }
    }

    override fun visitAnonymousInitializer(anonymousInitializer: FirAnonymousInitializer) {
        collect(anonymousInitializer)
        context.withAnonymousInitializer(anonymousInitializer, holder.session) {
            anonymousInitializer.body?.accept(this)
        }
    }

    override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {
        collect(anonymousFunction)
        context.withTypeParametersOf(anonymousFunction) {
            anonymousFunction.receiverParameter?.accept(this)
            context.withAnonymousFunction(anonymousFunction) {
                for (contextParameter in anonymousFunction.contextParameters) {
                    context.storeValueParameterIfNeeded(contextParameter, holder.session)
                }
                for (valueParameter in anonymousFunction.valueParameters) {
                    context.storeValueParameterIfNeeded(valueParameter, holder.session)
                }
                anonymousFunction.valueParameters.forEach { it.accept(this) }
                anonymousFunction.body?.accept(this)
            }
        }
    }

    override fun visitBlock(block: FirBlock) {
        collect(block)
        context.forBlock(holder.session) {
            for (statement in block.statements) {
                statement.accept(this)
                when (statement) {
                    is FirProperty -> context.storeVariable(statement, holder.session)
                    is FirNamedFunction -> context.storeFunction(statement, holder.session)
                    is FirRegularClass -> context.storeClassOrTypealiasIfNotNested(statement, holder.session)
                    is FirTypeAlias -> context.storeClassOrTypealiasIfNotNested(statement, holder.session)
                    else -> {}
                }
            }
        }
    }
}
