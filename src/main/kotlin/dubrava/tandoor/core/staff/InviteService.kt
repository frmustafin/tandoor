package dubrava.tandoor.core.staff

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

/**
 * One-time invitation links, so an admin never has to ask anyone for a Telegram ID. The token
 * is 128 random bits; the link is `t.me/<bot>?start=invite_<token>` and dies on first use or
 * after [TTL]. The table has an assigned key, so this is plain SQL.
 */
@Service
class InviteService(
    private val jdbc: JdbcClient,
    private val staff: StaffService,
    private val clock: Clock,
) {

    data class Invite(val token: String, val role: Role, val expiresAt: Instant)

    fun create(role: Role, createdBy: Long): Invite {
        val invite = Invite(token = newToken(), role = role, expiresAt = clock.instant() + TTL)
        jdbc.sql("INSERT INTO invites (token, role, created_by, expires_at) VALUES (:token, :role, :by, :expires)")
            .param("token", invite.token).param("role", role.name).param("by", createdBy)
            .param("expires", OffsetDateTime.ofInstant(invite.expiresAt, ZoneOffset.UTC))
            .update()
        return invite
    }

    /** Grants the invite's role and returns it; null when the token is unknown, used, or expired. */
    fun redeem(token: String, userId: Long, displayName: String): Role? {
        val now = clock.instant()
        val row = jdbc.sql("SELECT role, created_by, expires_at, used_by FROM invites WHERE token = :token")
            .param("token", token)
            .query { rs, _ ->
                Row(
                    role = Role.valueOf(rs.getString("role")),
                    createdBy = rs.getLong("created_by"),
                    expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
                    used = rs.getLong("used_by").let { !rs.wasNull() },
                )
            }
            .optional().orElse(null) ?: return null
        if (row.used || row.expiresAt < now) return null
        // The guard in WHERE makes two simultaneous taps on the same link grant the role once.
        val claimed = jdbc.sql("UPDATE invites SET used_by = :user, used_at = :now WHERE token = :token AND used_by IS NULL")
            .param("user", userId).param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).param("token", token)
            .update()
        if (claimed != 1) return null
        staff.grant(userId, row.role, displayName, addedBy = row.createdBy)
        return row.role
    }

    private data class Row(val role: Role, val createdBy: Long, val expiresAt: Instant, val used: Boolean)

    companion object {
        val TTL: Duration = Duration.ofHours(24)
        const val START_PREFIX = "invite_"
        private val random = SecureRandom()

        /** 22 URL-safe characters: fits Telegram's `start` payload (64 chars of [A-Za-z0-9_-]). */
        fun newToken(): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(16).also(random::nextBytes))
    }
}
