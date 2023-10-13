package io.papermc.paperweight.tasks.softspoon

import io.papermc.paperweight.tasks.*
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.*
import kotlin.jvm.optionals.getOrNull
import org.cadixdev.at.AccessChange
import org.cadixdev.at.AccessTransform
import org.cadixdev.at.AccessTransformSet
import org.cadixdev.at.ModifierChange
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.bombe.type.BaseType
import org.cadixdev.bombe.type.ObjectType
import org.cadixdev.bombe.type.VoidType
import org.cadixdev.bombe.type.signature.MethodSignature
import org.gradle.api.tasks.TaskAction
import org.openrewrite.ExecutionContext
import org.openrewrite.InMemoryExecutionContext
import org.openrewrite.Recipe
import org.openrewrite.Tree
import org.openrewrite.TreeVisitor
import org.openrewrite.internal.InMemoryLargeSourceSet
import org.openrewrite.internal.ListUtils
import org.openrewrite.java.Java17Parser
import org.openrewrite.java.JavaIsoVisitor
import org.openrewrite.java.tree.J
import org.openrewrite.java.tree.JavaType
import org.openrewrite.java.tree.JavaType.Primitive
import org.openrewrite.java.tree.Space
import org.openrewrite.marker.Markers


abstract class ApplySourceAT : BaseTask() {

    @TaskAction
    fun run() {

    }
}

fun main() {
    val ats =
        AccessTransformFormats.FML.read(Path.of("D:\\IntellijProjects\\PaperClean\\.gradle\\caches\\paperweight\\taskCache\\mergeAdditionalAts.at"))
    val root = Path.of("D:\\IdeaProjects\\Paper\\Paper-Server\\src\\vanilla\\java\\")

    val classes = ats.classes.map {
        val path = if (it.key.contains("craftbukkit") || it.key.contains("com/mojang/brigadier")) {
            null
        } else if (it.key.contains("$")) {
            root.resolve(it.key.split("\$")[0] + ".java")
        } else {
            root.resolve(it.key + ".java")
        }
        if (path != null && !path.isRegularFile()) {
            println("Invalid AT $it: File $path does not exist!")
            null
        } else {
            path
        }
    }.filterNotNull().toSet()

    //val classes = listOf(root.resolve("net/minecraft/CrashReport.java"))

    val parser = Java17Parser.builder().build()
    val ctx = InMemoryExecutionContext { it.printStackTrace() }

    val parsed = parser.parse(classes, root, ctx).toList()
    val largeSourceSet = InMemoryLargeSourceSet(parsed)
    val recipe = ATRecipe(ats)
    val results = recipe.run(largeSourceSet, ctx).changeset.allResults
    if (recipe.atErrors.size > 0) {
        println("Encountered AT ${recipe.atErrors.size} error(s)!")
        recipe.atErrors.forEach(::println)
    }
    results.forEach {
        val after = it.after
        if (after != null) {
            println("writing ${after.sourcePath}")
            root.resolve(after.sourcePath).writeText(after.printAll())
        } else {
            println("Can't write ${it.before?.sourcePath} because after source file doesnt exist?!")
            println(it.diff())
        }
    }
}

class ATRecipe(val ats: AccessTransformSet) : Recipe() {
    val atErrors = CopyOnWriteArrayList<String>()

    override fun getDisplayName(): String {
        return "AT Recipe"
    }

    override fun getDescription(): String {
        return "Recipe that applies ATs"
    }

    override fun getVisitor(): TreeVisitor<*, ExecutionContext> {
        return ATVisitor(ats, atErrors)
    }

    override fun maxCycles() = 1

}

class ATVisitor(private val ats: AccessTransformSet, private val atErrors: MutableList<String>) : JavaIsoVisitor<ExecutionContext>() {

    override fun visitClassDeclaration(classDecl: J.ClassDeclaration, p: ExecutionContext): J.ClassDeclaration {
        val type = classDecl.type ?: return classDecl
        val atClass = ats.getClass(type.fullyQualifiedName).getOrNull()
        if (atClass == null) {
            // TODO consider inner classes
            atErrors.add("Can't find AT for class ${type.fullyQualifiedName} (${classDecl.name.simpleName})")
            return classDecl
        }
        // store stuff so we can see that it was sued
        val fields = HashSet(atClass.fields.keys)
        val methods = HashSet(atClass.methods.keys)

        val newStatements = classDecl.body.statements.map { statement ->
            when (statement) {
                is J.VariableDeclarations -> {
                    // handle fields
                    val fieldName = fields.find { atField -> statement.variables.any { field -> field.simpleName == atField } }
                        ?: return@map statement
                    val atField = atClass.getField(fieldName)
                    // mark field as used
                    fields.remove(fieldName)

                    var resultStatement = statement
                    // handle private/package-private -> public
                    resultStatement = handleFieldVisibility(resultStatement, atField, fieldName, type.fullyQualifiedName)
                    // handle final removal
                    resultStatement = handleFieldFinalRemoval(resultStatement, atField, fieldName, type.fullyQualifiedName)

                    resultStatement
                }

                is J.MethodDeclaration -> {
                    // handle methods
                    val methodSignature = methods.find { atMethod ->
                        val methodType = statement.methodType
                        val returnType = methodType?.returnType
                        val atReturnType = atMethod.descriptor.returnType

                        // first check name
                        if ( atMethod.name != methodType?.name) {
                            return@find false
                        }

                        // then check params
                        // TODO compare param types
                        if (atMethod.descriptor.paramTypes.size != methodType?.parameterTypes?.size) {
                            return@find false
                        }

                        // then check return type
                        if (atReturnType is ObjectType && returnType is JavaType.Class) {
                            atReturnType.className.replace('/', '.') == returnType.fullyQualifiedName
                        } else if (atReturnType is BaseType && returnType is JavaType.Primitive) {
                            Primitive.fromKeyword(atReturnType.name.lowercase()) == returnType
                        } else if (atReturnType is VoidType && returnType is JavaType.Primitive) {
                            returnType == Primitive.Void
                        } else {
                            false
                        }

                    } ?: return@map statement
                    val atMethod = atClass.getMethod(methodSignature)

                    // mark method as used
                    methods.remove(methodSignature)

                    var resultStatement = statement
                    // handle private/package-private -> public
                    resultStatement = handleMethodVisibility(resultStatement, atMethod, methodSignature, type.fullyQualifiedName)
                    // handle final removal
                    resultStatement = handleMethodFinalRemoval(resultStatement, atMethod, methodSignature, type.fullyQualifiedName)

                    resultStatement
                }

                else -> {
                    statement
                }
            }
        }

        // check if we have unused field ATs
        if (fields.isNotEmpty()) {
            atErrors.add("Fields $fields in class ${type.fullyQualifiedName} don't exist!")
        }
        // check if we have unused method ATs
        if (methods.isNotEmpty()) {
            atErrors.add("Methods $methods in class ${type.fullyQualifiedName} don't exist!")
        }

        return classDecl.withBody(classDecl.body.withStatements(newStatements))
    }

    private fun handleMethodFinalRemoval(
        statement: J.MethodDeclaration,
        atMethod: AccessTransform,
        methodSignature: MethodSignature,
        className: String
    ): J.MethodDeclaration {
        if (atMethod.final == ModifierChange.REMOVE) {
            if (!statement.hasModifier(J.Modifier.Type.Final)) {
                atErrors.add("Method $methodSignature in class $className is already not final!")
                return statement
            }
            val newModifiers = ArrayList(statement.modifiers)
            newModifiers.removeIf { it.type == J.Modifier.Type.Final }
            return statement.withModifiers(newModifiers)
        } else if (atMethod.final != ModifierChange.NONE) {
            atErrors.add("Unsupported modifier type for method $methodSignature in class $className: ${atMethod.final}")
        }
        return statement
    }

    private fun handleMethodVisibility(
        statement: J.MethodDeclaration,
        atMethod: AccessTransform,
        methodSignature: MethodSignature,
        className: String
    ): J.MethodDeclaration {
        if (atMethod.access == AccessChange.PUBLIC) {
            if (statement.hasModifier(J.Modifier.Type.Public)) {
                atErrors.add("Method $methodSignature in class $className is already public!")
                return statement
            } else if (statement.hasModifier(J.Modifier.Type.Private) || statement.hasModifier(J.Modifier.Type.Protected)) {
                // replace the private with a new public modifier
                return statement.withModifiers(statement.modifiers.map { mod ->
                    if (mod.type == J.Modifier.Type.Private || mod.type == J.Modifier.Type.Protected) {
                        J.Modifier(
                            Tree.randomId(), mod.prefix, Markers.EMPTY, null, J.Modifier.Type.Public, emptyList()
                        )
                    } else {
                        mod
                    }
                })
            } else {
                // package private -> add new modifier at the start
                val newModifiers = ListUtils.concat(
                    J.Modifier(
                        Tree.randomId(), Space.EMPTY, Markers.EMPTY, null, J.Modifier.Type.Public, emptyList()
                    ),
                    statement.modifiers
                )
                // fix space
                if (newModifiers.size > 1) {
                    newModifiers[1] = newModifiers[1].withPrefix(newModifiers[1].prefix.withWhitespace(" "))
                }
                return statement.withModifiers(newModifiers)
            }
        } else if (atMethod.access != AccessChange.NONE) {
            atErrors.add("Unsupported access type for method $methodSignature in class $className: ${atMethod.access}")
        }
        return statement
    }

    private fun handleFieldFinalRemoval(
        statement: J.VariableDeclarations,
        atField: AccessTransform,
        fieldName: String,
        className: String
    ): J.VariableDeclarations {
        if (atField.final == ModifierChange.REMOVE) {
            if (!statement.hasModifier(J.Modifier.Type.Final)) {
                atErrors.add("Field $fieldName in class $className is already not final!")
                return statement
            }
            val newModifiers = ArrayList(statement.modifiers)
            newModifiers.removeIf { it.type == J.Modifier.Type.Final }
            return statement.withModifiers(newModifiers)
        } else if (atField.final != ModifierChange.NONE) {
            atErrors.add("Unsupported modifier type for field $fieldName in class $className: ${atField.final}")
        }
        return statement
    }

    private fun handleFieldVisibility(
        statement: J.VariableDeclarations,
        atField: AccessTransform,
        fieldName: String,
        className: String
    ): J.VariableDeclarations {
        if (atField.access == AccessChange.PUBLIC) {
            if (statement.hasModifier(J.Modifier.Type.Public)) {
                atErrors.add("Field $fieldName in class $className is already public!")
                return statement
            } else if (statement.hasModifier(J.Modifier.Type.Private) || statement.hasModifier(J.Modifier.Type.Protected)) {
                // replace the private with a new public modifier
                return statement.withModifiers(statement.modifiers.map { mod ->
                    if (mod.type == J.Modifier.Type.Private || mod.type == J.Modifier.Type.Protected) {
                        J.Modifier(
                            Tree.randomId(), mod.prefix, Markers.EMPTY, null, J.Modifier.Type.Public, emptyList()
                        )
                    } else {
                        mod
                    }
                })
            } else {
                // package private -> add new modifier at the start
                val newModifiers = ListUtils.concat(
                    J.Modifier(
                        Tree.randomId(), Space.EMPTY, Markers.EMPTY, null, J.Modifier.Type.Public, emptyList()
                    ),
                    statement.modifiers
                )
                // fix space
                if (newModifiers.size > 1) {
                    newModifiers[1] = newModifiers[1].withPrefix(newModifiers[1].prefix.withWhitespace(" "))
                }
                return statement.withModifiers(newModifiers)
            }
        } else if (atField.access != AccessChange.NONE) {
            atErrors.add("Unsupported access type for field $fieldName in class $className: ${atField.access}")
        }
        return statement
    }
}
