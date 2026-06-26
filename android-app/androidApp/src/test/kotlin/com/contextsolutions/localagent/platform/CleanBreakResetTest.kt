package com.contextsolutions.localagent.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Drives the pure core of the L4 clean-break graceful reset ([CleanBreakReset.reset]) against temp
 * files. The contract: fire (wipe legacy + orphaned DB) **only** for a pre-L4 install — a legacy
 * androidx artifact present AND no key in the new store — and never touch a fresh or already-migrated
 * install.
 */
class CleanBreakResetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String, content: String = "x"): File =
        tmp.newFile(name).apply { writeText(content) }

    private fun dbWithSidecars(): File {
        val db = file("local_agent.db")
        file("local_agent.db-wal")
        file("local_agent.db-shm")
        return db
    }

    @Test
    fun `pre-L4 install — legacy prefs present and no key — wipes everything`() {
        val prefs = file("local_agent_secure_prefs.xml")
        val identity = file("relay_identity.x25519.enc")
        val db = dbWithSidecars()

        val fired = CleanBreakReset.reset(prefs, identity, db, hasDbKey = false)

        assertTrue(fired)
        assertFalse(prefs.exists())
        assertFalse(identity.exists())
        assertFalse(db.exists())
        assertFalse(File(db.path + "-wal").exists())
        assertFalse(File(db.path + "-shm").exists())
    }

    @Test
    fun `pre-L4 install — only legacy identity present — still fires`() {
        val prefs = File(tmp.root, "local_agent_secure_prefs.xml") // absent
        val identity = file("relay_identity.x25519.enc")
        val db = dbWithSidecars()

        assertTrue(CleanBreakReset.reset(prefs, identity, db, hasDbKey = false))
        assertFalse(identity.exists())
        assertFalse(db.exists())
    }

    @Test
    fun `already-migrated install — key present — never fires even with legacy files`() {
        val prefs = file("local_agent_secure_prefs.xml")
        val identity = file("relay_identity.x25519.enc")
        val db = dbWithSidecars()

        val fired = CleanBreakReset.reset(prefs, identity, db, hasDbKey = true)

        assertFalse(fired)
        assertTrue(prefs.exists())
        assertTrue(identity.exists())
        assertTrue(db.exists())
    }

    @Test
    fun `fresh install — no legacy files — no-op`() {
        val prefs = File(tmp.root, "local_agent_secure_prefs.xml") // absent
        val identity = File(tmp.root, "relay_identity.x25519.enc") // absent
        val db = file("local_agent.db") // a freshly-minted encrypted DB could exist on a genuine first run

        val fired = CleanBreakReset.reset(prefs, identity, db, hasDbKey = false)

        assertFalse(fired)
        assertTrue(db.exists())
    }
}
