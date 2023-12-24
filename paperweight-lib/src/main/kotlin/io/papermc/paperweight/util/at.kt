import org.cadixdev.at.AccessChange
import org.cadixdev.at.AccessTransform
import org.cadixdev.at.ModifierChange

public fun atFromString(input: String): AccessTransform {
    var last = input.length - 1

    val final: ModifierChange
    if (input[last] == 'f') {
        final = if (input[--last] == '-') ModifierChange.REMOVE else ModifierChange.ADD
    } else {
        final = ModifierChange.NONE
    }

    val access = when (input.substring(0, last)) {
        "public" -> AccessChange.PUBLIC
        "protected" -> AccessChange.PROTECTED
        "private" -> AccessChange.PRIVATE
        else -> AccessChange.NONE
    }

    println("input = $input")
    println(input.substring(0, last))
    println("access = $access, final = $final")

    return AccessTransform.of(access, final)
}

public fun atToString(at: AccessTransform): String {
    val access = when (at.access) {
        AccessChange.PRIVATE -> "private"
        AccessChange.PROTECTED -> "protected"
        AccessChange.PUBLIC -> "public"
        else -> ""
    }
    val final = when (at.final) {
        ModifierChange.REMOVE -> "-f"
        ModifierChange.ADD -> "+f"
        else -> ""
    }
    return access + final
}
