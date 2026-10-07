package com.threeastudio.gitclonepush.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Guards source distribution policy; APK signing is checked separately after packaging. */
class DistributionSecurityPolicyTest {
    private val main = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
    private val android = "http://schemas.android.com/apk/res/android"
    private fun xml(path: String): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(File(main, path)).documentElement

    @Test fun manifestDisablesBackupAndCleartext() {
        val app = xml("AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttributeNS(android, "allowBackup"))
        assertEquals("false", app.getAttributeNS(android, "usesCleartextTraffic"))
        assertEquals("@xml/network_security_config", app.getAttributeNS(android, "networkSecurityConfig"))
    }

    @Test fun permissionsRemainFocusedAndCallbackAndProviderRemainProtected() {
        val manifest = xml("AndroidManifest.xml")
        val permissions = manifest.getElementsByTagName("uses-permission")
        val names = (0 until permissions.length).map { (permissions.item(it) as Element).getAttributeNS(android, "name") }.toSet()
        assertEquals(setOf("android.permission.INTERNET", "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE"), names)
        val provider = manifest.getElementsByTagName("provider").item(0) as Element
        assertEquals("android.permission.MANAGE_DOCUMENTS", provider.getAttributeNS(android, "permission"))
        assertEquals("true", provider.getAttributeNS(android, "grantUriPermissions"))
        val callbacks = manifest.getElementsByTagName("data")
        assertTrue((0 until callbacks.length).any {
            val callback = callbacks.item(it) as Element
            callback.getAttributeNS(android, "scheme") == "gitclonepush" &&
                callback.getAttributeNS(android, "host") == "oauth" && callback.getAttributeNS(android, "path") == "/callback"
        })
    }

    @Test fun networkPolicyUsesOnlySystemTrustAndNoCleartextOrDebugOverride() {
        val config = xml("res/xml/network_security_config.xml")
        val base = config.getElementsByTagName("base-config").item(0) as Element
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        val certificates = config.getElementsByTagName("certificates")
        assertEquals(1, certificates.length)
        assertEquals("system", (certificates.item(0) as Element).getAttribute("src"))
        assertEquals(0, config.getElementsByTagName("debug-overrides").length)
    }

    @Test fun backupAndDeviceTransferExcludeAllPrivateDataDomains() {
        val domains = setOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
        fun assertExclusions(element: Element) {
            val exclusions = element.getElementsByTagName("exclude")
            assertEquals(domains, (0 until exclusions.length).map {
                val entry = exclusions.item(it) as Element
                assertEquals(".", entry.getAttribute("path"))
                entry.getAttribute("domain")
            }.toSet())
            assertFalse(element.getElementsByTagName("include").length > 0)
        }
        assertExclusions(xml("res/xml/backup_rules.xml"))
        val extraction = xml("res/xml/data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            assertExclusions(extraction.getElementsByTagName(section).item(0) as Element)
        }
    }
}
