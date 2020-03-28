/*
 * Copyright 2000-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.calls.inference.model

import org.jetbrains.kotlin.resolve.calls.components.PostponedArgumentsAnalyzer
import org.jetbrains.kotlin.resolve.calls.inference.*
import org.jetbrains.kotlin.resolve.calls.inference.components.ConstraintInjector
import org.jetbrains.kotlin.resolve.calls.inference.components.KotlinConstraintSystemCompleter
import org.jetbrains.kotlin.resolve.calls.inference.components.PostponedArgumentInputTypesResolver
import org.jetbrains.kotlin.resolve.calls.inference.components.ResultTypeResolver
import org.jetbrains.kotlin.resolve.calls.model.KotlinCallDiagnostic
import org.jetbrains.kotlin.resolve.calls.model.OnlyInputTypesDiagnostic
import org.jetbrains.kotlin.resolve.calls.model.ResolvedAtom
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.checker.NewKotlinTypeChecker
import org.jetbrains.kotlin.types.model.*
import org.jetbrains.kotlin.utils.SmartList
import org.jetbrains.kotlin.utils.SmartSet
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import kotlin.math.max

class NewConstraintSystemImpl(
    private val constraintInjector: ConstraintInjector,
    val typeSystemContext: TypeSystemInferenceExtensionContext
) :
    TypeSystemInferenceExtensionContext by typeSystemContext,
    NewConstraintSystem,
    ConstraintSystemBuilder,
    ConstraintInjector.Context,
    ResultTypeResolver.Context,
    KotlinConstraintSystemCompleter.Context,
    PostponedArgumentInputTypesResolver.Context,
    PostponedArgumentsAnalyzer.Context {
    private val storage = MutableConstraintStorage()
    private var state = State.BUILDING
    private val typeVariablesTransaction: MutableList<TypeVariableMarker> = SmartList()
    private val properTypesCache: MutableSet<KotlinTypeMarker> = SmartSet.create()
    private val notProperTypesCache: MutableSet<KotlinTypeMarker> = SmartSet.create()

    private enum class State {
        BUILDING,
        TRANSACTION,
        FREEZED,
        COMPLETION
    }

    private fun checkState(a: State) {
        if (!AbstractTypeChecker.RUN_SLOW_ASSERTIONS) return
        checkState(*arrayOf(a))
    }

    private fun checkState(a: State, b: State) {
        if (!AbstractTypeChecker.RUN_SLOW_ASSERTIONS) return
        checkState(*arrayOf(a, b))
    }

    private fun checkState(a: State, b: State, c: State) {
        if (!AbstractTypeChecker.RUN_SLOW_ASSERTIONS) return
        checkState(*arrayOf(a, b, c))
    }

    private fun checkState(a: State, b: State, c: State, d: State) {
        if (!AbstractTypeChecker.RUN_SLOW_ASSERTIONS) return
        checkState(*arrayOf(a, b, c, d))
    }

    private fun checkState(vararg allowedState: State) {
        if (!AbstractTypeChecker.RUN_SLOW_ASSERTIONS) return
        assert(state in allowedState) {
            "State $state is not allowed. AllowedStates: ${allowedState.joinToString()}"
        }
    }

    override val diagnostics: List<KotlinCallDiagnostic>
        get() = storage.errors

    override fun getBuilder() = apply { checkState(State.BUILDING, State.COMPLETION) }

    override fun asReadOnlyStorage(): ConstraintStorage {
        checkState(State.BUILDING, State.FREEZED)
        state = State.FREEZED
        return storage
    }

    override fun asConstraintSystemCompleterContext() = apply { checkState(State.BUILDING) }

    override fun asPostponedArgumentsAnalyzerContext() = apply { checkState(State.BUILDING) }

    override fun asPostponedArgumentInputTypesResolverContext() = apply { checkState(State.BUILDING) }

    // ConstraintSystemOperation
    override fun registerVariable(variable: TypeVariableMarker) {
        checkState(State.BUILDING, State.COMPLETION, State.TRANSACTION)

        transactionRegisterVariable(variable)
        storage.allTypeVariables[variable.freshTypeConstructor()] = variable
        notProperTypesCache.clear()
        storage.notFixedTypeVariables[variable.freshTypeConstructor()] = MutableVariableWithConstraints(variable)
    }

    override fun markPostponedVariable(variable: TypeVariableMarker) {
        storage.postponedTypeVariables += variable
    }

    override fun unmarkPostponedVariable(variable: TypeVariableMarker) {
        storage.postponedTypeVariables -= variable
    }

    override fun removePostponedVariables() {
        storage.postponedTypeVariables.clear()
    }

    override fun addSubtypeConstraint(lowerType: KotlinTypeMarker, upperType: KotlinTypeMarker, position: ConstraintPosition) =
        constraintInjector.addInitialSubtypeConstraint(
            apply { checkState(State.BUILDING, State.COMPLETION, State.TRANSACTION) },
            lowerType,
            upperType,
            position
        )

    override fun addEqualityConstraint(a: KotlinTypeMarker, b: KotlinTypeMarker, position: ConstraintPosition) =
        constraintInjector.addInitialEqualityConstraint(
            apply { checkState(State.BUILDING, State.COMPLETION, State.TRANSACTION) },
            a,
            b,
            position
        )

    override fun getProperSuperTypeConstructors(type: KotlinTypeMarker): List<TypeConstructorMarker> {
        checkState(State.BUILDING, State.COMPLETION, State.TRANSACTION)
        val variableWithConstraints = notFixedTypeVariables[type.typeConstructor()] ?: return listOf(type.typeConstructor())

        return variableWithConstraints.constraints.mapNotNull {
            if (it.kind == ConstraintKind.LOWER) return@mapNotNull null
            it.type.typeConstructor().takeUnless { allTypeVariables.containsKey(it) }
        }
    }

    // ConstraintSystemBuilder
    private fun transactionRegisterVariable(variable: TypeVariableMarker) {
        if (state != State.TRANSACTION) return
        typeVariablesTransaction.add(variable)
    }

    private fun closeTransaction(beforeState: State) {
        checkState(State.TRANSACTION)
        typeVariablesTransaction.clear()
        state = beforeState
    }

    override fun runTransaction(runOperations: ConstraintSystemOperation.() -> Boolean): Boolean {
        checkState(State.BUILDING, State.COMPLETION)
        val beforeState = state
        val beforeInitialConstraintCount = storage.initialConstraints.size
        val beforeErrorsCount = storage.errors.size
        val beforeMaxTypeDepthFromInitialConstraints = storage.maxTypeDepthFromInitialConstraints

        state = State.TRANSACTION
        // typeVariablesTransaction is clear
        if (runOperations()) {
            closeTransaction(beforeState)
            return true
        }

        for (addedTypeVariable in typeVariablesTransaction) {
            storage.allTypeVariables.remove(addedTypeVariable.freshTypeConstructor())
            storage.notFixedTypeVariables.remove(addedTypeVariable.freshTypeConstructor())
        }
        storage.maxTypeDepthFromInitialConstraints = beforeMaxTypeDepthFromInitialConstraints
        storage.errors.trimToSize(beforeErrorsCount)

        val addedInitialConstraints = storage.initialConstraints.subList(beforeInitialConstraintCount, storage.initialConstraints.size)

        val shouldRemove = { c: Constraint -> addedInitialConstraints.contains(c.position.initialConstraint) }

        for (variableWithConstraint in storage.notFixedTypeVariables.values) {
            variableWithConstraint.removeLastConstraints(shouldRemove)
        }

        addedInitialConstraints.clear() // remove constraint from storage.initialConstraints
        closeTransaction(beforeState)
        return false
    }

    // ConstraintSystemBuilder, KotlinConstraintSystemCompleter.Context
    override val hasContradiction: Boolean
        get() = storage.hasContradiction.also {
            checkState(
                State.FREEZED,
                State.BUILDING,
                State.COMPLETION,
                State.TRANSACTION
            )
        }

    override fun addOtherSystem(otherSystem: ConstraintStorage) {
        if (otherSystem.allTypeVariables.isNotEmpty()) {
            otherSystem.allTypeVariables.forEach {
                transactionRegisterVariable(it.value)
            }
            storage.allTypeVariables.putAll(otherSystem.allTypeVariables)
            notProperTypesCache.clear()
        }
        for ((variable, constraints) in otherSystem.notFixedTypeVariables) {
            notFixedTypeVariables[variable] = MutableVariableWithConstraints(constraints)
        }
        storage.initialConstraints.addAll(otherSystem.initialConstraints)
        storage.maxTypeDepthFromInitialConstraints =
            max(storage.maxTypeDepthFromInitialConstraints, otherSystem.maxTypeDepthFromInitialConstraints)
        storage.errors.addAll(otherSystem.errors)
        storage.fixedTypeVariables.putAll(otherSystem.fixedTypeVariables)
        storage.postponedTypeVariables.addAll(otherSystem.postponedTypeVariables)
    }

    // ResultTypeResolver.Context, ConstraintSystemBuilder
    override fun isProperType(type: KotlinTypeMarker): Boolean {
        checkState(State.BUILDING, State.COMPLETION, State.TRANSACTION)
        if (storage.allTypeVariables.isEmpty()) return true
        if (notProperTypesCache.contains(type)) return false
        if (properTypesCache.contains(type)) return true
        return isProperTypeImpl(type).also {
            (if (it) properTypesCache else notProperTypesCache).add(type)
        }
    }

    private fun isProperTypeImpl(type: KotlinTypeMarker): Boolean =
        !type.contains {
            val capturedType = it.asSimpleType()?.asCapturedType()
            // TODO: change NewCapturedType to markered one for FE-IR
            val typeToCheck = if (capturedType is CapturedTypeMarker && capturedType.captureStatus() == CaptureStatus.FROM_EXPRESSION)
                capturedType.typeConstructorProjection().takeUnless { projection -> projection.isStarProjection() }?.getType()
            else
                it

            if (typeToCheck == null) return@contains false

            storage.allTypeVariables.containsKey(typeToCheck.typeConstructor())
        }

    override fun isTypeVariable(type: KotlinTypeMarker): Boolean {
        checkState(State.BUILDING, State.COMPLETION, State.TRANSACTION)
        return notFixedTypeVariables.containsKey(type.typeConstructor())
    }

    override fun isPostponedTypeVariable(typeVariable: TypeVariableMarker): Boolean {
        checkState(State.BUILDING, State.COMPLETION, State.TRANSACTION)
        return typeVariable in postponedTypeVariables
    }

    // ConstraintInjector.Context, KotlinConstraintSystemCompleter.Context
    override val allTypeVariables: Map<TypeConstructorMarker, TypeVariableMarker>
        get() {
            checkState(State.BUILDING, State.COMPLETION, State.TRANSACTION)
            return storage.allTypeVariables
        }

    override var maxTypeDepthFromInitialConstraints: Int
        get() = storage.maxTypeDepthFromInitialConstraints
        set(value) {
            checkState(State.BUILDING, State.COMPLETION, State.TRANSACTION)
            storage.maxTypeDepthFromInitialConstraints = value
        }

    override fun addInitialConstraint(initialConstraint: InitialConstraint) {
        checkState(State.BUILDING, State.COMPLETION, State.TRANSACTION)
        storage.initialConstraints.add(initialConstraint)
    }

    // ConstraintInjector.Context, FixationOrderCalculator.Context
    override val notFixedTypeVariables: MutableMap<TypeConstructorMarker, MutableVariableWithConstraints>
        get() {
            checkState(State.BUILDING, State.COMPLETION, State.TRANSACTION)
            return storage.notFixedTypeVariables
        }

    override val fixedTypeVariables: MutableMap<TypeConstructorMarker, KotlinTypeMarker>
        get() {
            checkState(State.BUILDING, State.COMPLETION, State.TRANSACTION)
            return storage.fixedTypeVariables
        }

    override val postponedTypeVariables: List<TypeVariableMarker>
        get() {
            checkState(State.BUILDING, State.COMPLETION, State.TRANSACTION)
            return storage.postponedTypeVariables
        }

    // ConstraintInjector.Context, KotlinConstraintSystemCompleter.Context
    override fun addError(error: KotlinCallDiagnostic) {
        checkState(State.BUILDING, State.COMPLETION, State.TRANSACTION)
        storage.errors.add(error)
    }

    // KotlinConstraintSystemCompleter.Context
    override fun fixVariable(variable: TypeVariableMarker, resultType: KotlinTypeMarker, atom: ResolvedAtom?) {
        checkState(State.BUILDING, State.COMPLETION)

        constraintInjector.addInitialEqualityConstraint(
            this, variable.defaultType(), resultType, FixVariableConstraintPosition(variable, atom)
        )

        val freshTypeConstructor = variable.freshTypeConstructor()

        val variableWithConstraints = notFixedTypeVariables.remove(freshTypeConstructor)
        checkOnlyInputTypesAnnotation(variableWithConstraints, resultType)

        for (variableWithConstraint in notFixedTypeVariables.values) {
            variableWithConstraint.removeConstrains {
                it.type.contains { it.typeConstructor() == freshTypeConstructor }
            }
        }

        storage.fixedTypeVariables[freshTypeConstructor] = resultType
    }

    private fun checkOnlyInputTypesAnnotation(
        variableWithConstraints: MutableVariableWithConstraints?,
        resultType: KotlinTypeMarker
    ) {
        if (resultType !is KotlinType || variableWithConstraints == null) return
        if (variableWithConstraints.typeVariable.safeAs<NewTypeVariable>()?.hasOnlyInputTypesAnnotation() != true) return

        val resultTypeIsInputType = variableWithConstraints.projectedInputCallTypes.any { inputType ->
            NewKotlinTypeChecker.Default.equalTypes(resultType, inputType) ||
                    inputType.constructor is IntersectionTypeConstructor
                    && inputType.constructor.supertypes.any { NewKotlinTypeChecker.Default.equalTypes(resultType, it) }
        }
        if (!resultTypeIsInputType) {
            addError(OnlyInputTypesDiagnostic(variableWithConstraints.typeVariable as NewTypeVariable))
        }
    }

    // KotlinConstraintSystemCompleter.Context, PostponedArgumentsAnalyzer.Context
    override fun canBeProper(type: KotlinTypeMarker): Boolean {
        checkState(State.BUILDING, State.COMPLETION)
        return !type.contains { storage.notFixedTypeVariables.containsKey(it.typeConstructor()) }
    }

    override fun containsOnlyFixedOrPostponedVariables(type: KotlinTypeMarker): Boolean {
        checkState(State.BUILDING, State.COMPLETION)
        return !type.contains {
            val typeConstructor = it.typeConstructor()
            val variable = storage.notFixedTypeVariables[typeConstructor]?.typeVariable
            variable !in storage.postponedTypeVariables && storage.notFixedTypeVariables.containsKey(typeConstructor)
        }
    }

    // PostponedArgumentsAnalyzer.Context
    override fun buildCurrentSubstitutor(): TypeSubstitutorMarker {
        checkState(State.BUILDING, State.COMPLETION)
        return buildCurrentSubstitutor(emptyMap())
    }

    override fun buildCurrentSubstitutor(additionalBindings: Map<TypeConstructorMarker, StubTypeMarker>): TypeSubstitutorMarker {
        checkState(State.BUILDING, State.COMPLETION)
        return storage.buildCurrentSubstitutor(this, additionalBindings)
    }

    override fun buildNotFixedVariablesToStubTypesSubstitutor(): TypeSubstitutorMarker {
        checkState(State.BUILDING, State.COMPLETION)
        return storage.buildNotFixedVariablesToNonSubtypableTypesSubstitutor(this)
    }

    // ResultTypeResolver.Context, VariableFixationFinder.Context
    override fun isReified(variable: TypeVariableMarker): Boolean {
        if (variable !is TypeVariableFromCallableDescriptor) return false
        return variable.originalTypeParameter.isReified
    }

    override fun bindingStubsForPostponedVariables(): Map<TypeVariableMarker, StubTypeMarker> {
        checkState(State.BUILDING, State.COMPLETION)
        // TODO: SUB
        return storage.postponedTypeVariables.associate { it to createStubType(it)/*StubType(it.freshTypeConstructor() as TypeConstructor, it.defaultType().isMarkedNullable())*/ }
    }

    override fun currentStorage(): ConstraintStorage {
        checkState(State.BUILDING, State.COMPLETION)
        return storage
    }

    // PostponedArgumentsAnalyzer.Context
    override fun hasUpperOrEqualUnitConstraint(type: KotlinTypeMarker): Boolean {
        checkState(State.BUILDING, State.COMPLETION, State.FREEZED)

        val constraints = storage.notFixedTypeVariables[type.typeConstructor()]?.constraints ?: return false

        return constraints.any { (it.kind == ConstraintKind.UPPER || it.kind == ConstraintKind.EQUALITY) && it.type.isUnit() }
    }
}
