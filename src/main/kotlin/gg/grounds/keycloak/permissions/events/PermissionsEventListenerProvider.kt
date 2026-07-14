package gg.grounds.keycloak.permissions.events

import com.fasterxml.jackson.databind.ObjectMapper
import org.jboss.logging.Logger
import org.keycloak.events.Event
import org.keycloak.events.EventListenerProvider
import org.keycloak.events.EventType
import org.keycloak.events.admin.AdminEvent
import org.keycloak.events.admin.OperationType
import org.keycloak.events.admin.ResourceType
import org.keycloak.models.AbstractKeycloakTransaction
import org.keycloak.models.GroupModel
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel

class PermissionsEventListenerProvider
internal constructor(
    private val session: KeycloakSession,
    private val configuredRealm: String,
    private val publisher: IdentityChangePublisher,
    private val publishFailureReporter: (MinecraftIdentityChangedEvent, Exception) -> Unit =
        ::reportPublishFailure,
    private val groupLookupFailureReporter: (String, String, Throwable?) -> Unit =
        ::reportGroupLookupFailure,
) : EventListenerProvider {
    override fun onEvent(event: Event) {
        if (!matchesRealm(event.realmId, event.realmName) || event.userId.isNullOrBlank()) return
        if (event.type !in REFRESH_EVENTS) return

        schedule(event.realmId, event.userId, IdentityChangeReason.IDENTITY_REFRESHED)
    }

    override fun onEvent(event: AdminEvent, includeRepresentation: Boolean) {
        if (!matchesRealm(event.realmId, event.realmName)) return

        when (event.resourceType) {
            ResourceType.GROUP_MEMBERSHIP -> scheduleGroupMembership(event)
            ResourceType.GROUP -> scheduleGroupMembers(event)
            ResourceType.USER -> scheduleUserChange(event)
            else -> Unit
        }
    }

    override fun close() = Unit

    private fun scheduleGroupMembership(event: AdminEvent) {
        if (event.operationType !in setOf(OperationType.CREATE, OperationType.DELETE)) return
        val userId = resourceId(event.resourcePath, "users") ?: return
        schedule(event.realmId, userId, IdentityChangeReason.GROUP_MEMBERSHIP_CHANGED)
    }

    private fun scheduleGroupMembers(event: AdminEvent) {
        // Keycloak emits group deletion events after removing the group and its memberships.
        // Scheduled reconciliation handles deletions because user-scoped fanout is no longer
        // possible.
        if (event.operationType !in setOf(OperationType.UPDATE, OperationType.ACTION)) return
        val groupId =
            representedId(event.representation)
                ?: resourceId(event.resourcePath, "groups")
                ?: return
        val realm = session.realms().getRealm(event.realmId)
        val group = realm?.let { session.groups().getGroupById(it, groupId) }
        if (realm == null || group == null) {
            groupLookupFailureReporter(event.realmId, groupId, null)
            return
        }

        val affectedUserIds =
            runCatching { collectAffectedUserIds(realm, group) }
                .getOrElse { exception ->
                    groupLookupFailureReporter(event.realmId, groupId, exception)
                    return
                }
        affectedUserIds.forEach { userId ->
            schedule(event.realmId, userId, IdentityChangeReason.GROUP_CHANGED)
        }
    }

    private fun collectAffectedUserIds(realm: RealmModel, changedGroup: GroupModel): Set<String> {
        val pendingGroups = ArrayDeque<GroupModel>().apply { add(changedGroup) }
        val visitedGroupIds = mutableSetOf<String>()
        val affectedUserIds = linkedSetOf<String>()

        while (pendingGroups.isNotEmpty()) {
            val group = pendingGroups.removeFirst()
            if (!visitedGroupIds.add(group.id)) continue

            session.users().getGroupMembersStream(realm, group).use { members ->
                members.map { it.id }.filter(String::isNotBlank).forEach(affectedUserIds::add)
            }
            group.subGroupsStream.use { subGroups -> subGroups.forEach(pendingGroups::addLast) }
        }

        return affectedUserIds
    }

    private fun scheduleUserChange(event: AdminEvent) {
        val userId = resourceId(event.resourcePath, "users") ?: return
        when {
            isFederatedIdentityPath(event.resourcePath) ->
                schedule(event.realmId, userId, IdentityChangeReason.MINECRAFT_IDENTITY_CHANGED)
            event.operationType == OperationType.DELETE ->
                schedule(event.realmId, userId, IdentityChangeReason.USER_DELETED)
            event.operationType == OperationType.UPDATE ->
                schedule(event.realmId, userId, IdentityChangeReason.MINECRAFT_IDENTITY_CHANGED)
        }
    }

    private fun schedule(realmId: String, userId: String, reason: IdentityChangeReason) {
        val pending =
            session.getAttribute(PENDING_CHANGES_ATTRIBUTE) as? PendingIdentityChanges
                ?: PendingIdentityChanges(publisher, publishFailureReporter).also {
                    session.setAttribute(PENDING_CHANGES_ATTRIBUTE, it)
                    session.transactionManager.enlistAfterCompletion(it)
                }
        pending.add(realmId, userId, reason)
    }

    private fun matchesRealm(realmId: String?, realmName: String?): Boolean =
        configuredRealm == realmId || configuredRealm == realmName

    private fun resourceId(resourcePath: String?, collection: String): String? {
        val segments = resourcePath?.split('/')?.filter(String::isNotBlank) ?: return null
        val collectionIndex = segments.indexOf(collection)
        return segments.getOrNull(collectionIndex + 1)?.takeIf(String::isNotBlank)
    }

    private fun isFederatedIdentityPath(resourcePath: String?): Boolean =
        resourcePath?.split('/')?.any { it == "federated-identity" } == true

    private fun representedId(representation: String?): String? =
        representation
            ?.takeIf(String::isNotBlank)
            ?.let { value ->
                runCatching { OBJECT_MAPPER.readTree(value).path("id").textValue() }.getOrNull()
            }
            ?.takeIf(String::isNotBlank)

    private class PendingIdentityChanges(
        private val publisher: IdentityChangePublisher,
        private val publishFailureReporter: (MinecraftIdentityChangedEvent, Exception) -> Unit,
    ) : AbstractKeycloakTransaction() {
        private val changes = linkedMapOf<Pair<String, String>, IdentityChangeReason>()

        fun add(realmId: String, userId: String, reason: IdentityChangeReason) {
            changes.merge(realmId to userId, reason) { current, candidate ->
                if (candidate.priority > current.priority) candidate else current
            }
        }

        override fun commitImpl() {
            changes.forEach { (identity, reason) ->
                val (realmId, userId) = identity
                val event =
                    MinecraftIdentityChangedEvent(
                        realmId = realmId,
                        keycloakUserId = userId,
                        reason = reason.value,
                    )
                try {
                    publisher.publish(event)
                } catch (exception: Exception) {
                    publishFailureReporter(event, exception)
                }
            }
            changes.clear()
        }

        override fun rollbackImpl() {
            changes.clear()
        }
    }

    private companion object {
        val OBJECT_MAPPER = ObjectMapper()
        const val PENDING_CHANGES_ATTRIBUTE =
            "gg.grounds.keycloak.permissions.events.pending-identity-changes"
        val REFRESH_EVENTS =
            setOf(EventType.LOGIN, EventType.REGISTER, EventType.FEDERATED_IDENTITY_LINK)
    }
}

private fun reportPublishFailure(event: MinecraftIdentityChangedEvent, exception: Exception) {
    Logger.getLogger(PermissionsEventListenerProvider::class.java)
        .errorf(
            exception,
            "Minecraft identity invalidation publish failed (realmId=%s, keycloakUserId=%s, reason=%s)",
            event.realmId,
            event.keycloakUserId,
            event.reason,
        )
}

private fun reportGroupLookupFailure(realmId: String, groupId: String, exception: Throwable?) {
    val logger = Logger.getLogger(PermissionsEventListenerProvider::class.java)
    val message =
        "Failed to capture affected group members (realmId=%s, groupId=%s, reason=group_lookup_failed)"
    if (exception == null) {
        logger.warnf(message, realmId, groupId)
    } else {
        logger.warnf(exception, message, realmId, groupId)
    }
}
