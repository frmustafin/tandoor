package dubrava.tandoor.core.staff

import dubrava.tandoor.config.BakeryProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.core.JdbcAggregateOperations
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/** ADMIN includes everything a BAKER may do; the plan has the author and the baker as admins. */
enum class Role { BAKER, ADMIN }

/** The only place a person's name is stored, and only for staff, to tell them apart in lists. */
@Table("staff")
data class Staff(
    @Id val telegramId: Long,
    val role: Role,
    val displayName: String,
    val addedBy: Long? = null,
    val addedAt: Instant,
)

interface StaffRepository : ListCrudRepository<Staff, Long>

/** What the router needs to enforce access: who is who. */
fun interface RoleSource {
    fun roleOf(telegramId: Long): Role?
}

@Service
class StaffService(
    private val staff: StaffRepository,
    /** The id is assigned, not generated, so `save` would try an UPDATE; inserts go here. */
    private val aggregates: JdbcAggregateOperations,
    private val props: BakeryProperties,
    private val clock: Clock,
) : ApplicationRunner, RoleSource {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun roleOf(telegramId: Long): Role? = staff.findById(telegramId).orElse(null)?.role

    fun find(telegramId: Long): Staff? = staff.findById(telegramId).orElse(null)

    /** Admins first, then by the day they joined; the id breaks ties so the list never reshuffles. */
    fun all(): List<Staff> = staff.findAll().sortedWith(compareBy({ it.role != Role.ADMIN }, { it.addedAt }, { it.telegramId }))

    /** A newcomer is inserted; someone already on staff gets the new role (an invite can promote). */
    fun grant(telegramId: Long, role: Role, displayName: String, addedBy: Long?): Staff {
        val existing = find(telegramId)
        if (existing != null) return staff.save(existing.copy(role = role, displayName = displayName))
        return aggregates.insert(Staff(telegramId, role, displayName, addedBy, clock.instant()))
    }

    enum class RevokeResult { REMOVED, LAST_ADMIN, NOT_STAFF }

    /** The last admin cannot be removed: that would lock everyone out of the bot's management. */
    fun revoke(telegramId: Long): RevokeResult {
        val member = find(telegramId) ?: return RevokeResult.NOT_STAFF
        if (member.role == Role.ADMIN && staff.findAll().count { it.role == Role.ADMIN } <= 1) return RevokeResult.LAST_ADMIN
        staff.deleteById(telegramId)
        return RevokeResult.REMOVED
    }

    /** Admins listed in the configuration are created once; later changes happen in the bot. */
    override fun run(args: ApplicationArguments) {
        for (id in props.bootstrapAdmins) {
            if (staff.existsById(id)) continue
            grant(id, Role.ADMIN, displayName = "из конфигурации", addedBy = null)
            log.info("bootstrap admin {} added", id)
        }
    }
}
