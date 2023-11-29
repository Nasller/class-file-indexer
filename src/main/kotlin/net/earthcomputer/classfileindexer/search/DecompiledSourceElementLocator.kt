package net.earthcomputer.classfileindexer.search

import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import net.earthcomputer.classfileindexer.isDescriptorOfMethodType
import net.earthcomputer.classfileindexer.isDescriptorOfType
import net.earthcomputer.classfileindexer.withSlowOperationsIfNecessary

open class DecompiledSourceElementLocator<T : PsiElement>(
    val className: String,
    private val location: String,
    val index: Int
) : JavaRecursiveElementVisitor() {
    private class ClassScope(val className: String, var anonymousClassIndex: Int = 0)

    private val classScopeStack = java.util.ArrayDeque<ClassScope>()
    private var foundElement: T? = null
    private var foundCount = 0
    val locationName = location.substringBefore(':')
    val locationDesc = location.substringAfter(':')
    val locationIsMethod = locationDesc.contains("(")
    private var constructorCallsThis = false

    override fun toString() = "${javaClass.simpleName}($className, $location, $index)"

    open fun findElement(clazz: PsiClass): T? {
        foundElement = null
        foundCount = 0
        constructorCallsThis = false
        withSlowOperationsIfNecessary {
            clazz.accept(this)
        }
        return foundElement
    }

    protected fun matchElement(element: T) {
        if (isInLocation(element) && foundCount++ == index) {
            foundElement = element
        }
    }

    private fun isInClassLocation(): Boolean {
        return classScopeStack.descendingIterator().asSequence()
            .joinToString("\$") { it.className } == className
    }

    private fun isInLocation(element: PsiElement): Boolean {
        val parent = PsiTreeUtil.getParentOfType(
            element,
            PsiMethod::class.java,
            PsiField::class.java,
            PsiRecordComponent::class.java,
            PsiClass::class.java,
            PsiClassInitializer::class.java
        ) ?: return false
        if (!isInClassLocation()) {
            return false
        }
        when (parent) {
            is PsiMethod -> {
                if (!locationIsMethod) {
                    return false
                }
                if (parent.isConstructor) {
                    if (locationName != "<init>") {
                        return false
                    }
                } else {
                    if (locationName != parent.name) {
                        return false
                    }
                }
                return isDescriptorOfMethodType(locationDesc, parent)
            }
            is PsiField -> {
                val initializer = parent.initializer
                val enumConstantArgs = (parent as? PsiEnumConstant)?.argumentList
                val isInInitializer = !PsiUtil.isCompileTimeConstant(parent) &&
                    (
                        (initializer != null && PsiTreeUtil.isAncestor(initializer, element, false)) ||
                            (enumConstantArgs != null && PsiTreeUtil.isAncestor(enumConstantArgs, element, false))
                        )
                if (isInInitializer) {
                    if (!locationIsMethod) {
                        return false
                    }
                    val isStatic = parent.hasModifierProperty(PsiModifier.STATIC) || parent is PsiEnumConstant
                    return if (isStatic) {
                        locationName == "<clinit>"
                    } else {
                        locationName == "<init>" && !constructorCallsThis
                    }
                } else {
                    if (locationIsMethod || locationName.isEmpty()) {
                        return false
                    }
                    return parent.name == locationName && isDescriptorOfType(locationDesc, parent.type)
                }
            }
            is PsiRecordComponent -> {
                if (locationIsMethod || locationName.isEmpty()) {
                    return false
                }
                return parent.name == locationName && isDescriptorOfType(locationDesc, parent.type)
            }
            is PsiClass -> {
                return locationName.isEmpty()
            }
            is PsiClassInitializer -> {
                if (!locationIsMethod) {
                    return false
                }
                val isStatic = parent.hasModifierProperty(PsiModifier.STATIC)
                return if (isStatic) {
                    locationName == "<clinit>"
                } else {
                    locationName == "<init>" && !constructorCallsThis
                }
            }
            else -> throw AssertionError()
        }
    }

    override fun visitAnonymousClass(clazz: PsiAnonymousClass) {
        classScopeStack.push(ClassScope("${++classScopeStack.peek().anonymousClassIndex}"))
        try {
            super.visitAnonymousClass(clazz)
        } finally {
            classScopeStack.pop()
        }
    }

    override fun visitClass(clazz: PsiClass) {
        classScopeStack.push(ClassScope(clazz.name ?: return))
        try {
            if (locationIsMethod && locationName == "<init>" && isInClassLocation()) {
                for (constructor in clazz.constructors) {
                    if (isDescriptorOfMethodType(locationDesc, constructor)) {
                        val firstStatement = constructor.body?.statements?.getOrNull(0) ?: break
                        val firstExpression = (firstStatement as? PsiExpressionStatement)?.expression ?: break
                        val firstMethodExpression = (firstExpression as? PsiMethodCallExpression)?.methodExpression ?: break
                        val methodName = firstMethodExpression.referenceName ?: break
                        if (methodName == "this") {
                            constructorCallsThis = true
                        }
                        break
                    }
                }
            }
            super.visitClass(clazz)
        } finally {
            classScopeStack.pop()
        }
    }
}