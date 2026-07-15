package gg.grounds.keycloak.permissions.events

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.keycloak.events.Event
import org.keycloak.events.EventType
import org.keycloak.events.admin.AdminEvent
import org.keycloak.events.admin.OperationType
import org.keycloak.events.admin.ResourceType
import org.keycloak.models.GroupModel
import org.keycloak.models.KeycloakSession
import org.keycloak.models.KeycloakTransaction
import org.keycloak.models.KeycloakTransactionManager
import org.keycloak.models.RealmModel
import org.keycloak.models.UserModel

class PermissionsEventListenerProviderTest {
    private val published = mutableListOf<MinecraftIdentityChangedEvent>()
    private val publisher = IdentityChangePublisher(published::add)
    private val transactionManager = mockk<KeycloakTransactionManager>()
    private val session = mockk<KeycloakSession>()
    private val enlisted = slot<KeycloakTransaction>()
    private val attributes = mutableMapOf<String, Any>()

    init {
        every { session.transactionManager } returns transactionManager
        every { transactionManager.enlistAfterCompletion(capture(enlisted)) } just runs
        every { session.getAttribute(any()) } answers { attributes[firstArg()] }
        every { session.setAttribute(any(), any()) } answers
            {
                attributes[firstArg()] = secondArg()
            }
    }

    @Test
    fun `publishes group membership changes only after commit`() {
        provider()
            .onEvent(
                adminEvent(
                    ResourceType.GROUP_MEMBERSHIP,
                    OperationType.CREATE,
                    "users/user-1/groups/group-1",
                ),
                false,
            )

        assertTrue(published.isEmpty())

        commit()

        assertEquals(
            listOf(
                MinecraftIdentityChangedEvent(
                    realmId = REALM_ID,
                    keycloakUserId = "user-1",
                    reason = IdentityChangeReason.GROUP_MEMBERSHIP_CHANGED.value,
                )
            ),
            published,
        )
    }

    @Test
    fun `does not publish rolled back changes`() {
        provider()
            .onEvent(
                adminEvent(
                    ResourceType.GROUP_MEMBERSHIP,
                    OperationType.DELETE,
                    "users/user-1/groups/group-1",
                ),
                false,
            )

        enlisted.captured.begin()
        enlisted.captured.rollback()

        assertTrue(published.isEmpty())
    }

    @Test
    fun `deduplicates multiple changes for the same user in one transaction`() {
        val provider = provider()
        provider.onEvent(
            adminEvent(
                ResourceType.GROUP_MEMBERSHIP,
                OperationType.CREATE,
                "users/user-1/groups/group-1",
            ),
            false,
        )
        provider.onEvent(loginEvent("user-1"))

        commit()

        assertEquals(1, published.size)
        assertEquals("user-1", published.single().keycloakUserId)
    }

    @Test
    fun `publishes successful login registration and identity link events`() {
        listOf(EventType.LOGIN, EventType.REGISTER, EventType.FEDERATED_IDENTITY_LINK).forEach {
            type ->
            val fixture = Fixture()
            fixture.provider().onEvent(fixture.userEvent(type, "user-${type.name.lowercase()}"))
            fixture.commit()
            assertEquals(
                IdentityChangeReason.IDENTITY_REFRESHED.value,
                fixture.published.single().reason,
            )
        }
    }

    @Test
    fun `publishes minecraft attribute updates and user deletion`() {
        val provider = provider()
        provider.onEvent(
            adminEvent(ResourceType.USER, OperationType.UPDATE, "users/user-1").apply {
                representation = """{"attributes":{"minecraft_java_uuid":["uuid"]}}"""
            },
            false,
        )
        provider.onEvent(
            adminEvent(ResourceType.USER, OperationType.DELETE, "users/user-2"),
            false,
        )

        commit()

        assertEquals(
            setOf(
                "user-1" to IdentityChangeReason.MINECRAFT_IDENTITY_CHANGED.value,
                "user-2" to IdentityChangeReason.USER_DELETED.value,
            ),
            published.map { it.keycloakUserId to it.reason }.toSet(),
        )
    }

    @Test
    fun `publishes user updates when minecraft attributes are cleared`() {
        provider()
            .onEvent(
                adminEvent(ResourceType.USER, OperationType.UPDATE, "users/user-1").apply {
                    representation = """{"attributes":{}}"""
                },
                false,
            )

        commit()

        assertEquals(
            IdentityChangeReason.MINECRAFT_IDENTITY_CHANGED.value,
            published.single().reason,
        )
    }

    @Test
    fun `publishes federated identity link and unlink admin events`() {
        val provider = provider()
        provider.onEvent(
            adminEvent(
                ResourceType.USER,
                OperationType.CREATE,
                "users/user-1/federated-identity/minecraft",
            ),
            false,
        )
        provider.onEvent(
            adminEvent(
                ResourceType.USER,
                OperationType.DELETE,
                "users/user-2/federated-identity/minecraft",
            ),
            false,
        )

        commit()

        assertEquals(
            setOf("user-1", "user-2"),
            published.mapTo(mutableSetOf()) { it.keycloakUserId },
        )
        assertTrue(
            published.all {
                it.reason == IdentityChangeReason.MINECRAFT_IDENTITY_CHANGED.value
            }
        )
    }

    @Test
    fun `ignores unrelated resources and other realms`() {
        val provider = provider()
        provider.onEvent(
            adminEvent(ResourceType.CLIENT, OperationType.UPDATE, "clients/client-1"),
            false,
        )
        provider.onEvent(
            adminEvent(
                ResourceType.GROUP_MEMBERSHIP,
                OperationType.CREATE,
                "users/user-2/groups/group-1",
                "other",
            ),
            false,
        )

        assertTrue(published.isEmpty())
        assertTrue(!enlisted.isCaptured)
    }

    @Test
    fun `accepts events from every configured realm`() {
        val provider = provider(setOf(REALM_ID, "grounds-test"))

        provider.onEvent(
            adminEvent(
                ResourceType.GROUP_MEMBERSHIP,
                OperationType.CREATE,
                "users/user-2/groups/group-1",
                "grounds-test",
            ),
            false,
        )

        commit()

        assertEquals("grounds-test", published.single().realmId)
        assertEquals("user-2", published.single().keycloakUserId)
    }

    @Test
    fun `does not accept an unconfigured realm with a colliding name`() {
        provider(setOf(REALM_ID))
            .onEvent(
                adminEvent(
                    ResourceType.GROUP_MEMBERSHIP,
                    OperationType.CREATE,
                    "users/user-2/groups/group-1",
                    realmId = "other-realm-id",
                    realmName = REALM_ID,
                ),
                false,
            )

        assertTrue(published.isEmpty())
        assertTrue(!enlisted.isCaptured)
    }

    @Test
    fun `captures affected group members before transaction completion`() {
        val realm = mockk<RealmModel>()
        val group = mockk<GroupModel>()
        val first = mockk<UserModel>()
        val second = mockk<UserModel>()
        every { session.realms().getRealm(REALM_ID) } returns realm
        every { session.groups().getGroupById(realm, "group-1") } returns group
        every { group.id } returns "group-1"
        every { group.subGroupsStream } returns Stream.empty()
        every { session.users().getGroupMembersStream(realm, group) } returns
            Stream.of(first, second)
        every { first.id } returns "user-1"
        every { second.id } returns "user-2"

        provider()
            .onEvent(
                adminEvent(ResourceType.GROUP, OperationType.UPDATE, "groups/group-1"),
                false,
            )

        commit()

        assertEquals(
            setOf("user-1", "user-2"),
            published.mapTo(mutableSetOf()) { it.keycloakUserId },
        )
        assertTrue(published.all { it.reason == IdentityChangeReason.GROUP_CHANGED.value })
    }

    @Test
    fun `leaves deleted groups to scheduled reconciliation`() {
        provider()
            .onEvent(
                adminEvent(ResourceType.GROUP, OperationType.DELETE, "groups/group-1").apply {
                    representation = """{"id":"group-1","name":"Developer"}"""
                },
                false,
            )

        assertTrue(published.isEmpty())
        assertTrue(!enlisted.isCaptured)
        verify(exactly = 0) { session.realms() }
    }

    @Test
    fun `captures members of descendant groups after parent changes`() {
        val realm = mockk<RealmModel>()
        val parent = mockk<GroupModel>()
        val child = mockk<GroupModel>()
        val grandchild = mockk<GroupModel>()
        val parentMember = mockk<UserModel>()
        val grandchildMember = mockk<UserModel>()
        every { session.realms().getRealm(REALM_ID) } returns realm
        every { session.groups().getGroupById(realm, "parent") } returns parent
        every { parent.id } returns "parent"
        every { child.id } returns "child"
        every { grandchild.id } returns "grandchild"
        every { parent.subGroupsStream } returns Stream.of(child)
        every { child.subGroupsStream } returns Stream.of(grandchild)
        every { grandchild.subGroupsStream } returns Stream.empty()
        every { session.users().getGroupMembersStream(realm, parent) } returns
            Stream.of(parentMember)
        every { session.users().getGroupMembersStream(realm, child) } returns Stream.empty()
        every { session.users().getGroupMembersStream(realm, grandchild) } returns
            Stream.of(grandchildMember)
        every { parentMember.id } returns "user-parent"
        every { grandchildMember.id } returns "user-grandchild"

        provider()
            .onEvent(
                adminEvent(ResourceType.GROUP, OperationType.UPDATE, "groups/parent"),
                false,
            )

        commit()

        assertEquals(
            setOf("user-parent", "user-grandchild"),
            published.mapTo(mutableSetOf()) { it.keycloakUserId },
        )
    }

    @Test
    fun `uses the moved group id from the admin representation`() {
        val realm = mockk<RealmModel>()
        val group = mockk<GroupModel>()
        val member = mockk<UserModel>()
        every { session.realms().getRealm(REALM_ID) } returns realm
        every { session.groups().getGroupById(realm, "child-group") } returns group
        every { group.id } returns "child-group"
        every { group.subGroupsStream } returns Stream.empty()
        every { session.users().getGroupMembersStream(realm, group) } returns Stream.of(member)
        every { member.id } returns "user-1"

        provider()
            .onEvent(
                adminEvent(ResourceType.GROUP, OperationType.UPDATE, "groups/parent/children")
                    .apply {
                        representation = """{"id":"child-group","name":"Developer"}"""
                    },
                false,
            )

        commit()

        assertEquals("user-1", published.single().keycloakUserId)
    }

    @Test
    fun `publishes no partial fanout when group member collection fails`() {
        val realm = mockk<RealmModel>()
        val group = mockk<GroupModel>()
        val first = mockk<UserModel>()
        val second = mockk<UserModel>()
        var reportedFailures = 0
        every { session.realms().getRealm(REALM_ID) } returns realm
        every { session.groups().getGroupById(realm, "group-1") } returns group
        every { group.id } returns "group-1"
        every { group.subGroupsStream } returns Stream.empty()
        every { session.users().getGroupMembersStream(realm, group) } returns
            Stream.of(first, second)
        every { first.id } returns "user-1"
        every { second.id } throws IllegalStateException("database unavailable")

        PermissionsEventListenerProvider(
                session,
                setOf(REALM_ID),
                publisher,
                publishFailureReporter = { _, _ -> },
                groupLookupFailureReporter = { _, _, _ -> reportedFailures++ },
            )
            .onEvent(
                adminEvent(ResourceType.GROUP, OperationType.UPDATE, "groups/group-1"),
                false,
            )

        assertTrue(published.isEmpty())
        assertTrue(!enlisted.isCaptured)
        assertEquals(1, reportedFailures)
    }

    @Test
    fun `does not fail the transaction when publishing fails`() {
        var reportedFailures = 0
        val failingProvider =
            PermissionsEventListenerProvider(
                session,
                setOf(REALM_ID),
                IdentityChangePublisher { error("nats unavailable") },
                publishFailureReporter = { _, _ -> reportedFailures++ },
                groupLookupFailureReporter = { _, _, _ -> },
            )
        failingProvider.onEvent(loginEvent("user-1"))

        commit()

        assertTrue(published.isEmpty())
        assertEquals(1, reportedFailures)
    }

    @Test
    fun `does not publish a malformed fallback when group lookup fails`() {
        val realm = mockk<RealmModel>()
        var reportedFailures = 0
        every { session.realms().getRealm(REALM_ID) } returns realm
        every { session.groups().getGroupById(realm, "missing") } returns null

        PermissionsEventListenerProvider(
                session,
                setOf(REALM_ID),
                publisher,
                publishFailureReporter = { _, _ -> },
                groupLookupFailureReporter = { _, _, _ -> reportedFailures++ },
            )
            .onEvent(
                adminEvent(ResourceType.GROUP, OperationType.UPDATE, "groups/missing"),
                false,
            )

        assertTrue(published.isEmpty())
        assertTrue(!enlisted.isCaptured)
        assertEquals(1, reportedFailures)
    }

    private fun provider(configuredRealmIds: Set<String> = setOf(REALM_ID)) =
        PermissionsEventListenerProvider(
            session,
            configuredRealmIds,
            publisher,
            publishFailureReporter = { _, _ -> },
            groupLookupFailureReporter = { _, _, _ -> },
        )

    private fun commit() {
        enlisted.captured.begin()
        enlisted.captured.commit()
    }

    private fun adminEvent(
        resourceType: ResourceType,
        operationType: OperationType,
        resourcePath: String,
        realmId: String = REALM_ID,
        realmName: String = realmId,
    ) =
        AdminEvent().apply {
            this.realmId = realmId
            this.realmName = realmName
            this.resourceType = resourceType
            this.operationType = operationType
            this.resourcePath = resourcePath
        }

    private fun loginEvent(userId: String) = userEvent(EventType.LOGIN, userId)

    private fun userEvent(type: EventType, userId: String) =
        Event().apply {
            this.type = type
            this.realmId = REALM_ID
            this.realmName = REALM_ID
            this.userId = userId
        }

    private class Fixture {
        val published = mutableListOf<MinecraftIdentityChangedEvent>()
        private val transactionManager = mockk<KeycloakTransactionManager>()
        private val session = mockk<KeycloakSession>()
        private val enlisted = slot<KeycloakTransaction>()
        private val attributes = mutableMapOf<String, Any>()

        init {
            every { session.transactionManager } returns transactionManager
            every { transactionManager.enlistAfterCompletion(capture(enlisted)) } just runs
            every { session.getAttribute(any()) } answers { attributes[firstArg()] }
            every { session.setAttribute(any(), any()) } answers
                {
                    attributes[firstArg()] = secondArg()
                }
        }

        fun provider() =
            PermissionsEventListenerProvider(
                session,
                setOf(REALM_ID),
                IdentityChangePublisher(published::add),
                publishFailureReporter = { _, _ -> },
                groupLookupFailureReporter = { _, _, _ -> },
            )

        fun userEvent(type: EventType, userId: String) =
            Event().apply {
                this.type = type
                this.realmId = REALM_ID
                this.realmName = REALM_ID
                this.userId = userId
            }

        fun commit() {
            enlisted.captured.begin()
            enlisted.captured.commit()
        }
    }

    private companion object {
        const val REALM_ID = "grounds"
    }
}
