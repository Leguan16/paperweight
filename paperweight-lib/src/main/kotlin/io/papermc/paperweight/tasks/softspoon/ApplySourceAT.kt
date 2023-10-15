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
import org.openrewrite.java.tree.J.ClassDeclaration
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

    val parser =
        Java17Parser.builder().classpath(listOf(Path.of("D:\\IdeaProjects\\Paper\\.gradle\\caches\\paperweight\\taskCache\\codebook-minecraft.jar")))
            .build()
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
        var actualClassDecl = classDecl
        var innerClassName: String? = null
        val type = actualClassDecl.type ?: return actualClassDecl
        var className = type.fullyQualifiedName
        var atClass = ats.getClass(className).getOrNull()
        if (atClass == null) {
            // consider unknown types
            className = cursor.firstEnclosing(J.CompilationUnit::class.java).packageDeclaration.packageName + "." + actualClassDecl.name.simpleName
            atClass = ats.getClass(className).getOrNull()
            if (atClass == null) {
                // consider inner classes
                for (it in actualClassDecl.body.statements) {
                    if (it is ClassDeclaration) {
                        atClass = ats.getClass(className + "$" + it.simpleName).getOrNull()
                        if (atClass != null) {
                            className = className + "$" + it.simpleName
                            innerClassName = it.simpleName
                            actualClassDecl = it
                            break
                        }
                    }
                }
                if (atClass == null) {
                    atErrors.add("Can't find AT for class ${type.fullyQualifiedName} (${actualClassDecl.name.simpleName})")
                    return classDecl
                }
            }
        }
        // store stuff so we can see that it was used
        val fields = HashSet(atClass.fields.keys)
        val methods = HashSet(atClass.methods.keys)

        val newStatements = actualClassDecl.body.statements.map { statement ->
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
                    resultStatement = handleFieldVisibility(resultStatement, atField, fieldName, className)
                    // handle final removal
                    resultStatement = handleFieldFinalRemoval(resultStatement, atField, fieldName, className)

                    resultStatement
                }

                is J.MethodDeclaration -> {
                    // handle methods
                    val methodSignature = methods.find { atMethod ->
                        val methodType = statement.methodType
                        val returnType = methodType?.returnType
                        val atReturnType = atMethod.descriptor.returnType

                        // first check name
                        if (atMethod.name != methodType?.name?.replace("<constructor>", "<init>")) {
                            return@find false
                        }

                        // then check params
                        // TODO compare param types
                        if (atMethod.descriptor.paramTypes.size != methodType?.parameterTypes?.size) {
                            return@find false
                        }

                        // no need to compare return type for ctors
                        if (atMethod.name == "<init>") {
                            return@find true
                        }

                        // then check return type
                        if (atReturnType is ObjectType && returnType is JavaType.Class) {
                            atReturnType.className.replace('/', '.') == returnType.fullyQualifiedName
                        } else if (atReturnType is BaseType && returnType is Primitive) {
                            Primitive.fromKeyword(atReturnType.name.lowercase()) == returnType
                        } else if (atReturnType is VoidType && returnType is Primitive) {
                            returnType == Primitive.Void
                        } else if (atReturnType is ObjectType && returnType is JavaType.Parameterized) {
                            atReturnType.className.replace('/', '.') == returnType.fullyQualifiedName
                        } else if (atReturnType is ObjectType && returnType is JavaType.GenericTypeVariable) {
                            atReturnType.className == "java/lang/Object"
                        } else {
                            false
                        }

                    } ?: return@map statement
                    val atMethod = atClass.getMethod(methodSignature)

                    // mark method as used
                    methods.remove(methodSignature)

                    var resultStatement = statement
                    // handle private/package-private -> public
                    resultStatement = handleMethodVisibility(resultStatement, atMethod, methodSignature, className)
                    // handle final removal
                    resultStatement = handleMethodFinalRemoval(resultStatement, atMethod, methodSignature, className)

                    resultStatement
                }

                else -> {
                    statement
                }
            }
        }

        // check if we have unused field ATs
        if (fields.isNotEmpty()) {
            atErrors.add("Fields $fields in class $className don't exist!")
        }
        // check if we have unused method ATs
        if (methods.isNotEmpty()) {
            atErrors.add("Methods $methods in class $className don't exist!")
        }

        // fix inner classes
        return if (className.contains("$")) {
            classDecl.withBody(classDecl.body.withStatements(classDecl.body.statements.map {
                if (it is ClassDeclaration && it.simpleName == innerClassName) {
                    var resultClass: ClassDeclaration = it.withBody(it.body.withStatements(newStatements))
                    resultClass = handleClassVisibility(resultClass, atClass, className)
                    resultClass = handleClassFinalRemoval(resultClass, atClass, className)
                    resultClass
                } else {
                    it
                }
            }))
        } else {
            var resultClass = classDecl.withBody(classDecl.body.withStatements(newStatements))
            resultClass = handleClassVisibility(resultClass, atClass, className)
            resultClass = handleClassFinalRemoval(resultClass, atClass, className)
            resultClass
        }
    }

    private fun handleClassFinalRemoval(classDecl: ClassDeclaration, atClass: AccessTransformSet.Class, className: String): ClassDeclaration {
        if (atClass.get().final == ModifierChange.REMOVE) {
            if (!classDecl.hasModifier(J.Modifier.Type.Final)) {
                atErrors.add("Class $className is already not final!")
                return classDecl
            }
            val newModifiers = ArrayList(classDecl.modifiers)
            newModifiers.removeIf { it.type == J.Modifier.Type.Final }
            return classDecl.withModifiers(newModifiers)
        } else if (atClass.get().final != ModifierChange.NONE) {
            atErrors.add("Unsupported modifier type for class $className: ${atClass.get().final}")
        }
        return classDecl
    }

    private fun handleClassVisibility(classDecl: ClassDeclaration, atClass: AccessTransformSet.Class, className: String): ClassDeclaration {
        if (atClass.get().access == AccessChange.PUBLIC) {
            if (classDecl.hasModifier(J.Modifier.Type.Public)) {
                if (atClass.get().final == ModifierChange.NONE) {
                    atErrors.add("Class $className is already public!")
                }
                return classDecl
            } else if (classDecl.hasModifier(J.Modifier.Type.Private) || classDecl.hasModifier(J.Modifier.Type.Protected)) {
                // replace the private with a new public modifier
                return classDecl.withModifiers(classDecl.modifiers.map { mod ->
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
                    classDecl.modifiers
                )
                // fix space
                if (newModifiers.size > 1) {
                    newModifiers[1] = newModifiers[1].withPrefix(newModifiers[1].prefix.withWhitespace(" "))
                    return classDecl.withModifiers(newModifiers)
                } else {
                    return classDecl.withModifiers(newModifiers).annotations.withKind(classDecl.annotations.kind.withPrefix(Space.SINGLE_SPACE))
                }
            }
        } else if (atClass.get().access != AccessChange.NONE){
            atErrors.add("Unsupported access type for class $className: ${atClass.get().access}")
            return classDecl
        }
        return classDecl
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
                if (atMethod.final == ModifierChange.NONE) {
                    atErrors.add("Method $methodSignature in class $className is already public!")
                }
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
                    return statement.withModifiers(newModifiers)
                } else {
                    // name for ctors, return type for other
                    return statement.withModifiers(newModifiers).withName(statement.name.withPrefix(Space.SINGLE_SPACE)).withReturnTypeExpression(statement.returnTypeExpression?.withPrefix(Space.SINGLE_SPACE))
                }
            }
        } else if (atMethod.access == AccessChange.PRIVATE) {
            if (statement.hasModifier(J.Modifier.Type.Private)) {
                if (atMethod.final == ModifierChange.NONE) {
                    atErrors.add("Method $atMethod in class $className is already private!")
                }
                return statement
            }
        } else if (atMethod.access == AccessChange.PROTECTED) {
            if (statement.hasModifier(J.Modifier.Type.Protected)) {
                if (atMethod.final == ModifierChange.NONE) {
                    atErrors.add("Method $atMethod in class $className is already protected!")
                }
                return statement
            }
        }

        if (atMethod.access != AccessChange.NONE) {
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
                if (atField.final == ModifierChange.NONE) {
                    atErrors.add("Field $fieldName in class $className is already public!")
                }
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
                    return statement.withModifiers(newModifiers)
                } else {
                    return statement.withModifiers(newModifiers).withTypeExpression(statement.typeExpression?.withPrefix(Space.SINGLE_SPACE))
                }
            }
        } else if (atField.access == AccessChange.PRIVATE) {
            if (statement.hasModifier(J.Modifier.Type.Private)) {
                if (atField.final == ModifierChange.NONE) {
                    atErrors.add("Field $fieldName in class $className is already private!")
                }
                return statement
            }
        } else if (atField.access == AccessChange.PROTECTED) {
            if (statement.hasModifier(J.Modifier.Type.Protected)) {
                if (atField.final == ModifierChange.NONE) {
                    atErrors.add("Field $fieldName in class $className is already protected!")
                }
                return statement
            }
        }

        if (atField.access != AccessChange.NONE) {
            atErrors.add("Unsupported access type for field $fieldName in class $className: ${atField.access}")
        }
        return statement
    }
}
