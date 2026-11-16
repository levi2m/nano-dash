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
    fun onlyTheLauncherAndTheHealthRationaleActivitiesAreExported() {
        val doc = parse(File(main, "AndroidManifest.xml"))
        assertEquals(
            mapOf(".MainActivity" to "true", ".HealthRationaleActivity" to "true", ".LoggerService" to "false", "androidx.core.content.FileProvider" to "false"),
            exportedComponents(doc),
        )
        // The rationale screen answers only Health Connect's own action.
        val activities = doc.getElementsByTagName("activity")
        val rationale = (0 until activities.length).map { activities.item(it) }
            .single { it.attributes.getNamedItemNS(androidNs, "name").nodeValue == ".HealthRationaleActivity" } as org.w3c.dom.Element
        val actions = rationale.getElementsByTagName("action")
        assertEquals(listOf("android.health.connect.action.SHOW_PERMISSIONS_RATIONALE"), (0 until actions.length).map { actions.item(it).attributes.getNamedItemNS(androidNs, "name").nodeValue })
    }

    @Test
    fun theAppReadsHeartRateAndNothingElseFromHealthConnect() {
        val health = permissions(parse(File(main, "AndroidManifest.xml"))).filter { it.startsWith("android.permission.health.") }
        assertEquals(listOf("android.permission.health.READ_HEART_RATE"), health)
    }

    @Test
    fun theWatchExportsOnlyItsLauncherAndATileOnlyTheSystemCanBind() {
        val doc = parse(File(wear, "AndroidManifest.xml"))
        assertEquals(mapOf(".MainActivity" to "true", ".RideTile" to "true"), exportedComponents(doc))
        // Only the system's tile host holds this permission, so no other app can bind the tile service.
        val tile = doc.getElementsByTagName("service").item(0).attributes
        assertEquals("com.google.android.wearable.permission.BIND_TILE_PROVIDER", tile.getNamedItemNS(androidNs, "permission")?.nodeValue)
    }

    @Test
    fun theFileProviderSharesOnlyTheRidesAndTheCacheExports() {
        val root = parse(File(main, "res/xml/file_paths.xml")).documentElement
        val entries = (0 until root.childNodes.length).map { root.childNodes.item(it) }.filter { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
            .map { it.nodeName to it.attributes.getNamedItem("path").nodeValue }
        assertEquals(listOf("external-files-path" to "rides/", "cache-path" to "exports/"), entries)
    }
}
