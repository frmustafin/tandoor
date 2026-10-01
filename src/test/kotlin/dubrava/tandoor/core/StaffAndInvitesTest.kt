package dubrava.tandoor.core

import dubrava.tandoor.core.staff.InviteService
import dubrava.tandoor.core.staff.Role
import dubrava.tandoor.core.staff.StaffService
import dubrava.tandoor.core.staff.StaffService.RevokeResult
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Roles and invitations with a movable clock; 1001 is the bootstrap admin from the test profile. */
@SpringBootTest
@Import(BatchServiceTest.Fakes::class)
class StaffAndInvitesTest {

    @Autowired lateinit var staff: StaffService
    @Autowired lateinit var invites: InviteService
    @Autowired lateinit var clock: MutableClock
    @Autowired lateinit var jdbc: JdbcClient

    private val bootstrapAdmin = 1001L
    private val anna = 2002L

    @BeforeEach
    fun reset() {
        jdbc.sql("DELETE FROM invites").update()
        jdbc.sql("DELETE FROM staff WHERE telegram_id <> :keep").param("keep", bootstrapAdmin).update()
        // The bootstrap admin was created by whichever context started first, on its own clock; pin the fixture.
        jdbc.sql("UPDATE staff SET added_at = :at WHERE telegram_id = :id")
            .param("at", java.time.OffsetDateTime.ofInstant(BatchServiceTest.START, java.time.ZoneOffset.UTC)).param("id", bootstrapAdmin).update()
        clock.set(BatchServiceTest.START)
    }

    @Test
    fun `an invitation grants its role once`() {
        val invite = invites.create(Role.BAKER, createdBy = bootstrapAdmin)
        assertEquals(22, invite.token.length)
        assertEquals(BatchServiceTest.START + Duration.ofHours(24), invite.expiresAt)

        assertEquals(Role.BAKER, invites.redeem(invite.token, anna, "Анна"))
        assertEquals(Role.BAKER, staff.roleOf(anna))
        assertEquals("Анна", staff.find(anna)!!.displayName)
        assertEquals(bootstrapAdmin, staff.find(anna)!!.addedBy)

        assertNull(invites.redeem(invite.token, 3003, "Борис"), "a used link is dead")
        assertNull(staff.roleOf(3003))
    }

    @Test
    fun `an expired or unknown invitation grants nothing`() {
        val invite = invites.create(Role.ADMIN, createdBy = bootstrapAdmin)
        clock.advance(Duration.ofHours(24).plusMinutes(1))
        assertNull(invites.redeem(invite.token, anna, "Анна"))
        assertNull(invites.redeem("no-such-token", anna, "Анна"))
        assertNull(staff.roleOf(anna))
    }

    @Test
    fun `tokens are unpredictable`() {
        assertNotEquals(InviteService.newToken(), InviteService.newToken())
        assertTrue(InviteService.newToken().all { it.isLetterOrDigit() || it == '-' || it == '_' }, "fits a start payload")
    }

    @Test
    fun `an invitation can promote a baker, revocation removes the role, the last admin stays`() {
        clock.advance(Duration.ofMinutes(1)) // the bootstrap admin joined at START; Anna must come later
        invites.redeem(invites.create(Role.BAKER, bootstrapAdmin).token, anna, "Анна")
        assertEquals(Role.ADMIN, invites.redeem(invites.create(Role.ADMIN, bootstrapAdmin).token, anna, "Анна"))
        assertEquals(listOf(bootstrapAdmin, anna), staff.all().map { it.telegramId }, "admins first, by join date")

        assertEquals(RevokeResult.REMOVED, staff.revoke(bootstrapAdmin), "another admin remains")
        assertEquals(RevokeResult.LAST_ADMIN, staff.revoke(anna))
        assertEquals(Role.ADMIN, staff.roleOf(anna))
        assertEquals(RevokeResult.NOT_STAFF, staff.revoke(bootstrapAdmin))

        staff.grant(bootstrapAdmin, Role.ADMIN, "из конфигурации", addedBy = null) // put the fixture back
    }
}
