package dev.unison.server

import kotlin.test.*

/** Who may pause a sync or start it again. */
class SyncRightsTest {
    @Test fun `members start a sync, only admins restart or pause one`() {
        requireSyncRights("member", restart = false)
        requireSyncRights("admin", restart = false)
        requireSyncRights("admin", restart = true)
        requireSyncRights("admin", restart = false, pause = true)
        assertFailsWith<IllegalArgumentException> { requireSyncRights("member", restart = true) }
        assertFailsWith<IllegalArgumentException> { requireSyncRights("member", restart = false, pause = true) }
    }
}
