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

package org.jetbrains.kotlin.codegen.state

import org.jetbrains.kotlin.codegen.OwnerKind
import org.jetbrains.kotlin.codegen.signature.BothSignatureWriter
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor


fun createSignatureWriter(f: FunctionDescriptor, kind: OwnerKind): BothSignatureWriter {
    if (kind == OwnerKind.DEFAULT_IMPLS) {
        val classDescriptor = f.containingDeclaration as ClassDescriptor
        if (f.typeParameters.isNotEmpty() && classDescriptor.declaredTypeParameters.isNotEmpty()) {
            val functionTypeParameters = f.typeParameters.map { it.name.asString() }.toMutableSet()
            val interfaceTypeParameters = classDescriptor.declaredTypeParameters.map { it.name.asString() }
            val clashedInterfaceTypeParameters = interfaceTypeParameters.intersect(functionTypeParameters)

            if (clashedInterfaceTypeParameters.isNotEmpty()) {
                val mappingForInterfaceTypeParameters = clashedInterfaceTypeParameters.associateBy ({ it }) {
                    var index = 1
                    var newNamePrefix = it + "_I"
                    while (functionTypeParameters.contains(newNamePrefix + index)) {
                        index++
                    }
                    functionTypeParameters.add(newNamePrefix + index)
                    newNamePrefix + index
                } + (interfaceTypeParameters - functionTypeParameters).associateBy { it }

                return BothSignatureWriter(BothSignatureWriter.Mode.METHOD) { mappingForInterfaceTypeParameters[it]!! }
            }
        }
    }

    return BothSignatureWriter(BothSignatureWriter.Mode.METHOD)
}