/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.scripting.compiler.plugin.fir

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.FirSessionComponent

/**
 * Marker component registered only on sessions created for the stateless completion/analysis frontend of the
 * IDE-services REPL compiler. It is never present during actual compilation (codegen).
 *
 * REPL snippet base classes are materialized as receivers only in this mode so that completion can resolve their
 * members. During real compilation the base class is intentionally not turned into a receiver, because later
 * lowerings cannot construct base classes that require constructor arguments (e.g. the default ScriptTemplateWithArgs).
 */
class FirReplCompletionModeComponent : FirSessionComponent

val FirSession.replCompletionModeComponent: FirReplCompletionModeComponent? by FirSession.nullableSessionComponentAccessor()
