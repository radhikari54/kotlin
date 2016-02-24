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

package org.jetbrains.kotlin.idea.intentions

import com.intellij.codeInsight.daemon.impl.quickfix.CreateClassKind
import com.intellij.codeInsight.intention.impl.CreateClassDialog
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.core.overrideImplement.ImplementMembersHandler
import org.jetbrains.kotlin.idea.quickfix.unblockDocument
import org.jetbrains.kotlin.idea.refactoring.getOrCreateKotlinFile
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.resolve.ModifiersChecker

const val IMPL_SUFFIX = "Impl"

class CreateKotlinSubClassIntention : SelfTargetingIntention<KtClass>(KtClass::class.java,
                                                                      "Create Kotlin subclass") {

    override fun isApplicableTo(element: KtClass, caretOffset: Int): Boolean {
        val baseClass = element
        if (baseClass.isEnum() || baseClass.isAnnotation() || baseClass.name == null) {
            return false
        }
        if (!baseClass.isInterface() && !baseClass.isSealed() && !baseClass.isAbstract() && !baseClass.hasModifier(KtTokens.OPEN_KEYWORD)) {
            return false
        }
        val primaryConstructor = baseClass.getPrimaryConstructor()
        if (!baseClass.isInterface() && primaryConstructor != null) {
            val constructors = baseClass.getSecondaryConstructors() + primaryConstructor
            if (constructors.none() {
                !it.isPrivate() &&
                it.getValueParameters().all { it.hasDefaultValue() }
            }) {
                // At this moment we require non-private default constructor
                // TODO: handle non-private constructors with parameters
                return false
            }
        }

        val endOffset = baseClass.getBody()?.lBrace?.startOffset ?: baseClass.endOffset
        if (caretOffset >= endOffset) {
            return false
        }

        text = getImplementTitle(baseClass)
        return true
    }

    private fun getImplementTitle(baseClass: KtClass) =
            when {
                baseClass.isInterface() -> "Implement interface"
                baseClass.isAbstract() -> "Implement abstract class"
                baseClass.isSealed() -> "Implement sealed class"
                else /* open class */ -> "Create subclass"
            }

    override fun applyTo(element: KtClass, editor: Editor?) {
        val baseClass = element
        if (baseClass.isSealed()) {
            createSealedSubclass(baseClass, editor)
        }
        else {
            createExternalSubclass(baseClass, editor)
        }
    }

    private fun defaultTargetName(klass: KtClass) = "${klass.name!!}$IMPL_SUFFIX"

    private fun defaultClassHeader(klass: KtClass) = "class ${defaultTargetName(klass)}"

    private fun createSealedSubclass(sealedClass: KtClass, editor: Editor?) {
        val classFromText = KtPsiFactory(sealedClass.project).createClass(defaultClassHeader(sealedClass))
        val body = sealedClass.getOrCreateBody()
        val rbrace = body.node.findChildByType(KtTokens.RBRACE)
        setClassHeader(sealedClass.project, sealedClass, body.addBefore(classFromText, rbrace!!.psi) as KtClass, editor)
    }

    private fun createExternalSubclass(baseClass: KtClass, editor: Editor?) {
        var container: KtClassOrObject = baseClass
        var name = baseClass.name!!
        var visibility = ModifiersChecker.resolveVisibilityFromModifiers(baseClass, Visibilities.PUBLIC)
        while (!container.isPrivate() && !container.isProtected()) {
            val parent = container.containingClassOrObject
            if (parent != null) {
                val parentName = parent.name
                if (parentName != null) {
                    container = parent
                    name = "$parentName.$name"
                    val parentVisibility = ModifiersChecker.resolveVisibilityFromModifiers(parent, visibility)
                    if (Visibilities.compare(parentVisibility, visibility) ?: 0 < 0) {
                        visibility = parentVisibility
                    }
                }
            }
            if (container != parent) {
                break
            }
        }
        val factory = KtPsiFactory(baseClass.project)
        if (container.containingClassOrObject == null && !ApplicationManager.getApplication().isUnitTestMode) {
            val dlg = chooseSubclassToCreate(baseClass) ?: return
            val targetName = dlg.className
            val file = getOrCreateKotlinFile("$targetName.kt", dlg.targetDirectory)!!
            file.add(factory.createClass("class $targetName"))
            setClassHeader(baseClass.project, baseClass, file.getChildOfType<KtClass>()!!, editor)
        }
        else {
            val classFromText = factory.createClass(defaultClassHeader(baseClass))
            setClassHeader(baseClass.project, baseClass, container.parent.addAfter(classFromText, container) as KtClass,
                           editor, name, visibility)
        }
    }

    private fun chooseSubclassToCreate(baseClass: KtClass): CreateClassDialog? {
        val sourceDir = baseClass.containingFile.containingDirectory

        val aPackage = JavaDirectoryService.getInstance().getPackage(sourceDir)
        val dialog = object : CreateClassDialog(
                baseClass.project, text,
                defaultTargetName(baseClass),
                aPackage?.qualifiedName ?: "",
                CreateClassKind.CLASS, true,
                ModuleUtilCore.findModuleForPsiElement(baseClass)
        ) {
            override fun getBaseDir(packageName: String) = sourceDir

            override fun reportBaseInTestSelectionInSource() = true
        }
        return if (!dialog.showAndGet() || dialog.targetDirectory == null) null else dialog
    }

    private fun setClassHeader(
            project: Project,
            baseClass: KtClass,
            targetClass: KtClass,
            editor: Editor?,
            name: String = baseClass.name!!,
            defaultVisibility: Visibility = ModifiersChecker.resolveVisibilityFromModifiers(baseClass, Visibilities.PUBLIC)
    ) {
        val factory = KtPsiFactory(project)
        val typeParameterList = baseClass.typeParameterList
        if (typeParameterList != null) {
            targetClass.add(factory.createTypeParameterList(typeParameterList.text))
        }
        val superTypeEntry: KtSuperTypeListEntry
        if (baseClass.isInterface()) {
            superTypeEntry = factory.createSuperTypeEntry(name)
        }
        else {
            if (defaultVisibility != Visibilities.PUBLIC) {
                KtPsiUtil.replaceModifierList(targetClass, factory.createModifierList(defaultVisibility.name))
            }
            superTypeEntry = factory.createSuperTypeCallEntry("$name()")
        }
        if (typeParameterList != null) {
            val typeArgumentsString = typeParameterList.parameters.map { it.name }.joinToString()
            val typeArguments = factory.createTypeArguments("<$typeArgumentsString>")
            if (baseClass.isInterface()) {
                superTypeEntry.add(typeArguments)
            }
            else {
                superTypeEntry.addAfter(typeArguments, superTypeEntry.getChildOfType<KtConstructorCalleeExpression>())
            }
        }
        targetClass.addSuperTypeListEntry(superTypeEntry)
        chooseAndImplementMethods(project, targetClass, editor)
    }

    private fun chooseAndImplementMethods(project: Project, targetClass: KtClass, editor: Editor?) {
        editor?.unblockDocument()
        targetClass.analyze()
        editor!!.caretModel.moveToOffset(targetClass.textRange.startOffset)
        ImplementMembersHandler().invoke(project, editor, targetClass.containingFile)
    }
}