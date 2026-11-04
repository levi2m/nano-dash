package dev.nanodash.core

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The privacy promises in README.md, PRIVACY.md and SECURITY.md rest on a few lines of manifest and provider configuration,
 * so a test reads them: no network permission, one exported component, no backup, and a file provider that shares only the
 * ride folders and the cache folder for share copies. The tests run in `android/core`, so the app module is one folder over.
 */
class ManifestPolicyTest {
    private val main = File("../app/src/main")
    private val wear = File("../wear/src/main")
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)

    private fun permissions(doc: org.w3c.dom.Document) = (0 until doc.getElementsByTagName("uses-permission").length).map {
        doc.getElementsByTagName("uses-permission").item(it).attributes.getNamedItemNS(androidNs, "name").nodeValue
    }

    private fun exportedComponents(doc: org.w3c.dom.Document): Map<String, String> {
        val exported = mutableMapOf<String, String>()
        for (tag in listOf("activity", "service", "receiver", "provider")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val attrs = nodes.item(i).attributes
                exported[attrs.getNamedItemNS(androidNs, "name").nodeValue] = attrs.getNamedItemNS(androidNs, "exported")?.nodeValue ?: "missing"
            }
        }
        return exported
    }

    private fun assertNoNetworkNoBackup(doc: org.w3c.dom.Document) {
        val permissions = permissions(doc)
        assertFalse(permissions.any { it.endsWith("INTERNET") }, "permissions: $permissions")
        val app = doc.getElementsByTagName("application").item(0)
        assertEquals("false", app.attributes.getNamedItemNS(androidNs, "allowBackup").nodeValue)
    }

    @Test
    fun theAppHasNoNetworkPermissionAndNoBackup() = assertNoNetworkNoBackup(parse(File(main, "AndroidManifest.xml")))

    @Test
    fun theWatchAppHasNoNetworkPermissionAndNoBackup() = assertNoNetworkNoBackup(parse(File(wear, "AndroidManifest.xml")))

    @Test
    fun onlyTheLauncherActivityIsExported() {
        val doc = parse(File(main, "AndroidManifest.xml"))
        assertEquals(mapOf(".MainActivity" to "true", ".LoggerService" to "false", "androidx.core.content.FileProvider" to "false"), exportedComponents(doc))
    }

    @Test
    fun onlyTheWatchLauncherActivityIsExported() {
        assertEquals(mapOf(".MainActivity" to "true"), exportedComponents(parse(File(wear, "AndroidManifest.xml"))))
    }

    @Test
    fun theFileProviderSharesOnlyTheRidesAndTheCacheExports() {
        val root = parse(File(main, "res/xml/file_paths.xml")).documentElement
        val entries = (0 until root.childNodes.length).map { root.childNodes.item(it) }.filter { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
            .map { it.nodeName to it.attributes.getNamedItem("path").nodeValue }
        assertEquals(listOf("external-files-path" to "rides/", "cache-path" to "exports/"), entries)
    }
}
