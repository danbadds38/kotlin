/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.uast.kotlin

import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiTypesUtil
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.resolve.CompileTimeConstantUtils
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.VariableAsFunctionResolvedCall
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.uast.*
import org.jetbrains.uast.internal.acceptList
import org.jetbrains.uast.kotlin.declarations.KotlinUIdentifier
import org.jetbrains.uast.kotlin.internal.TypedResolveResult
import org.jetbrains.uast.kotlin.internal.getReferenceVariants
import org.jetbrains.uast.kotlin.internal.multiResolveResults
import org.jetbrains.uast.visitor.UastVisitor

class KotlinUFunctionCallExpression(
        override val psi: KtCallElement,
        givenParent: UElement?,
        private val _resolvedCall: ResolvedCall<*>?
) : KotlinAbstractUExpression(givenParent), UCallExpressionEx, KotlinUElementWithType, UMultiResolvable {

    constructor(psi: KtCallElement, uastParent: UElement?) : this(psi, uastParent, null)

    private val resolvedCall
        get() = _resolvedCall ?: psi.getResolvedCall(psi.analyze())

    override val receiverType by lz {
        val resolvedCall = this.resolvedCall ?: return@lz null
        val receiver = resolvedCall.dispatchReceiver ?: resolvedCall.extensionReceiver ?: return@lz null
        receiver.type.toPsiType(this, psi, boxed = true)
    }

    override val methodName by lz { resolvedCall?.resultingDescriptor?.name?.asString() }

    override val classReference by lz {
        KotlinClassViaConstructorUSimpleReferenceExpression(psi, methodName.orAnonymous("class"), this)
    }

    override val methodIdentifier by lz {
        val calleeExpression = psi.calleeExpression
        when (calleeExpression) {
            null -> null
            is KtNameReferenceExpression ->
                KotlinUIdentifier(calleeExpression.getReferencedNameElement(), this)
            is KtConstructorDelegationReferenceExpression ->
                KotlinUIdentifier(calleeExpression.firstChild ?: calleeExpression, this)
            is KtConstructorCalleeExpression ->
                KotlinUIdentifier(
                    calleeExpression.constructorReferenceExpression?.getReferencedNameElement() ?: calleeExpression, this
                )
            else -> KotlinUIdentifier(calleeExpression, this)
        }
    }

    override val valueArgumentCount: Int
        get() = psi.valueArguments.size

    override val valueArguments by lz { psi.valueArguments.map { KotlinConverter.convertOrEmpty(it.getArgumentExpression(), this) } }

    override fun getArgumentForParameter(i: Int): UExpression? {
        val resolvedCall = resolvedCall
        if (resolvedCall != null) {
            val actualParamIndex = if (resolvedCall.extensionReceiver == null) i else i - 1
            if (actualParamIndex == -1) return receiver
            return getArgumentExpressionByIndex(actualParamIndex, resolvedCall, this)
        }
        val argument = valueArguments.getOrNull(i) ?: return null
        val argumentType = argument.getExpressionType()
        for (resolveResult in multiResolve()) {
            val psiMethod = resolveResult.element as? PsiMethod ?: continue
            val psiParameter = psiMethod.parameterList.parameters.getOrNull(i) ?: continue

            if (argumentType == null || psiParameter.type.isAssignableFrom(argumentType))
                return argument
        }
        return null
    }

    override fun getExpressionType(): PsiType? {
        super<KotlinUElementWithType>.getExpressionType()?.let { return it }
        for (resolveResult in multiResolve()) {
            val psiMethod = resolveResult.element
            when {
                psiMethod.isConstructor ->
                    psiMethod.containingClass?.let { return PsiTypesUtil.getClassType(it) }
                else ->
                    psiMethod.returnType?.let { return it }
            }
        }
        return null
    }

    override val typeArgumentCount: Int
        get() = psi.typeArguments.size

    override val typeArguments by lz { psi.typeArguments.map { it.typeReference.toPsiType(this, boxed = true) } }

    override val returnType: PsiType?
        get() = getExpressionType()

    override val kind: UastCallKind by lz {
        val resolvedCall = resolvedCall ?: return@lz UastCallKind.METHOD_CALL
        when {
            resolvedCall.resultingDescriptor is ConstructorDescriptor -> UastCallKind.CONSTRUCTOR_CALL
            this.isAnnotationArgumentArrayInitializer() -> UastCallKind.NESTED_ARRAY_INITIALIZER
            else -> UastCallKind.METHOD_CALL
        }
    }

    override val receiver: UExpression?
        get() {
            (uastParent as? UQualifiedReferenceExpression)?.let {
                if (it.selector == this) return it.receiver
            }

            val ktNameReferenceExpression = psi.calleeExpression as? KtNameReferenceExpression ?: return null
            val variableCallDescriptor =
                (resolvedCall as? VariableAsFunctionResolvedCall)?.variableCall?.resultingDescriptor
                    ?: (resolvedCall?.resultingDescriptor as? FunctionDescriptor)?.takeIf { it.visibility == Visibilities.LOCAL }
                    ?: return null

            // an implicit receiver for variables calls (KT-25524)
            return object : KotlinAbstractUExpression(this), UReferenceExpression {

                private val resolvedDeclaration = variableCallDescriptor.toSource()

                override val psi: KtNameReferenceExpression get() = ktNameReferenceExpression

                override val resolvedName: String? get() = (resolvedDeclaration as? PsiNamedElement)?.name

                override fun resolve(): PsiElement? = resolvedDeclaration

            }

        }

    private val multiResolved by lazy(fun(): Iterable<TypedResolveResult<PsiMethod>> {
        val contextElement = psi

        if (!Registry.`is`("kotlin.uast.multiresolve.enabled", true)) {
            val calleeExpression = contextElement.calleeExpression ?: return emptyList()
            return calleeExpression.multiResolveResults()
                .mapNotNull { it.element.safeAs<PsiMethod>()?.let { TypedResolveResult(it) } }
                .asIterable()
        }

        val calleeExpression = contextElement.calleeExpression as? KtReferenceExpression ?: return emptyList()
        val methodName = methodName ?: calleeExpression.text ?: return emptyList()
        val variants = getReferenceVariants(calleeExpression, methodName)
        return variants.flatMap {
            when (val source = it.toSource()) {
                is KtClass -> source.toLightClass()?.constructors?.asSequence().orEmpty()
                else -> resolveSource(psi, it, source)?.let { sequenceOf(it) }.orEmpty()
            }
        }.map { TypedResolveResult(it) }.asIterable()
    })

    override fun multiResolve(): Iterable<TypedResolveResult<PsiMethod>> = multiResolved


    override fun resolve(): PsiMethod? {
        val descriptor = resolvedCall?.resultingDescriptor ?: return null
        val source = descriptor.toSource()
        return resolveSource(psi, descriptor, source)
    }

    override fun accept(visitor: UastVisitor) {
        if (visitor.visitCallExpression(this)) return
        methodIdentifier?.accept(visitor)
        classReference.accept(visitor)
        valueArguments.acceptList(visitor)

        visitor.afterVisitCallExpression(this)
    }

    private fun isAnnotationArgumentArrayInitializer(): Boolean {
        // KtAnnotationEntry (or KtCallExpression when annotation is nested) -> KtValueArgumentList -> KtValueArgument -> arrayOf call
        val isAnnotationArgument = when (val elementAt2 = psi.parents.elementAtOrNull(2)) {
            is KtAnnotationEntry -> true
            is KtCallExpression -> elementAt2.getParentOfType<KtAnnotationEntry>(true, KtDeclaration::class.java) != null
            else -> false
        }
        if (!isAnnotationArgument) return false

        val resolvedCall = resolvedCall ?: return false
        return CompileTimeConstantUtils.isArrayFunctionCall(resolvedCall)
    }

    override fun convertParent(): UElement? = super.convertParent().let { result ->
        when (result) {
            is UMethod -> result.uastBody ?: result
            is UClass ->
                result.methods
                        .filterIsInstance<KotlinConstructorUMethod>()
                        .firstOrNull { it.isPrimary }
                        ?.uastBody
                ?: result
            else -> result
        }
    }

}

internal fun getArgumentExpressionByIndex(
    actualParamIndex: Int,
    resolvedCall: ResolvedCall<out CallableDescriptor>,
    parent: UElement
): UExpression? {
    val (parameter, resolvedArgument) = resolvedCall.valueArguments.entries.find { it.key.index == actualParamIndex } ?: return null
    val arguments = resolvedArgument.arguments
    if (arguments.isEmpty()) return null
    if (arguments.size == 1) {
        val argument = arguments.single()
        val expression = argument.getArgumentExpression()
        if (parameter.varargElementType != null && argument.getSpreadElement() == null) {
            return createVarargsHolder(arguments, parent)
        }
        return KotlinConverter.convertOrEmpty(expression, parent)
    }
    return createVarargsHolder(arguments, parent)
}

private fun createVarargsHolder(arguments: List<ValueArgument>, parent: UElement?): KotlinUExpressionList =
    KotlinUExpressionList(null, UastSpecialExpressionKind.VARARGS, parent).apply {
        expressions = arguments.map { KotlinConverter.convertOrEmpty(it.getArgumentExpression(), parent) }
    }
