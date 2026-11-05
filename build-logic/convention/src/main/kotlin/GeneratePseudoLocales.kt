// SPDX-License-Identifier: Unlicense
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Compose's single-variant resource tree is replaced only in explicitly isolated test builds. */
@CacheableTask
abstract class GeneratePseudoLocales : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val resourceDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val source = sourceDirectory.get().asFile
        val output = resourceDirectory.get().asFile
        output.deleteRecursively()
        source.copyRecursively(output)
        for (file in source
            .resolve(
                "values",
            ).listFiles()
            .orEmpty()
            .filter { it.extension == "xml" }
            .sortedBy { it.name }) {
            for (locale in PseudoLocale.entries) {
                val factory =
                    DocumentBuilderFactory.newInstance().apply {
                        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    }
                val doc = factory.newDocumentBuilder().parse(file)

                fun visit(node: Node) {
                    if (node.nodeType == Node.TEXT_NODE && !node.nodeValue.isNullOrBlank()) {
                        node.nodeValue = pseudoLocalize(node.nodeValue, locale)
                    } else {
                        for (i in 0 until node.childNodes.length) visit(node.childNodes.item(i))
                    }
                }
                val resources = doc.documentElement.childNodes
                for (i in 0 until resources.length) {
                    val node = resources.item(i)
                    if (node.nodeType == Node.ELEMENT_NODE &&
                        node.attributes.getNamedItem("translatable")?.nodeValue != "false"
                    ) {
                        visit(node)
                    }
                }
                val target = File(output, "values-${locale.qualifier}/${file.name}")
                target.parentFile.mkdirs()
                TransformerFactory.newInstance().newTransformer().transform(DOMSource(doc), StreamResult(target))
            }
        }
    }
}

internal enum class PseudoLocale(
    val qualifier: String,
) {
    EXPANDED("en-rXA"),
    RTL("ar-rXB"),
}

/** Keep format arguments, escaped characters and URLs intact so stress builds remain usable. */
internal fun pseudoLocalize(
    text: String,
    locale: PseudoLocale,
): String {
    val transformed =
        buildString {
            var position = 0
            for (token in PSEUDO_PROTECTED.findAll(text)) {
                append(pseudoLetters(text.substring(position, token.range.first)))
                append(token.value)
                position = token.range.last + 1
            }
            append(pseudoLetters(text.substring(position)))
        }
    return when (locale) {
        PseudoLocale.EXPANDED -> "[$transformed${" ~".repeat((text.length / 8).coerceAtLeast(1))}]"
        PseudoLocale.RTL -> "\u202e$transformed\u202c"
    }
}

private fun pseudoLetters(text: String): String =
    text
        .map { char ->
            val index = PSEUDO_ASCII.indexOf(char)
            if (index < 0) char else PSEUDO_ACCENTED[index]
        }.joinToString("")

private const val PSEUDO_ASCII = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val PSEUDO_ACCENTED = "àƀçđëƒğħïĵķľɱñöþɋřšţüṽŵẋÿžÀƁÇĐËƑĞĦÏĴĶĽṀÑÖÞɊŘŠŢÜṼŴẊŸŽ"
private val PSEUDO_PROTECTED =
    Regex("%(?:[0-9]+\\$)?[-#+ 0,(<]*[0-9]*(?:\\.[0-9]+)?[a-zA-Z%]|\\\\[nt'\"\\\\]|https?://\\S+")
