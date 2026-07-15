package gg.grounds.keycloak.permissions.events

import io.mockk.every
import io.mockk.mockk
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.keycloak.Config
import org.keycloak.events.EventListenerProviderFactory

class PermissionsEventListenerProviderFactoryTest {
    @Test
    fun `validates required configuration and applies the default subject`() {
        val config = mockk<Config.Scope>()
        every { config.get("nats-url") } returns "nats://nats:4222"
        every { config.get("subject", any()) } answers { secondArg() }
        every { config.get("realm") } returns "grounds"
        var subject: String? = null
        val factory = PermissionsEventListenerProviderFactory { _, configuredSubject ->
            subject = configuredSubject
            IdentityChangePublisher {}
        }

        factory.init(config)

        assertEquals("minecraft-identity.changed", subject)
    }

    @Test
    fun `rejects blank required configuration`() {
        val config = mockk<Config.Scope>()
        every { config.get("nats-url") } returns " "
        every { config.get("subject", any()) } answers { secondArg() }
        every { config.get("realm") } returns "grounds"

        assertFailsWith<IllegalArgumentException> {
            PermissionsEventListenerProviderFactory { _, _ -> IdentityChangePublisher {} }
                .init(config)
        }
    }

    @Test
    fun `parses a normalized list of configured realms`() {
        assertEquals(
            setOf("grounds", "grounds-test"),
            PermissionsEventListenerProviderFactory.parseRealms(" grounds, grounds-test,grounds "),
        )
    }

    @Test
    fun `rejects an empty configured realm list`() {
        assertFailsWith<IllegalArgumentException> {
            PermissionsEventListenerProviderFactory.parseRealms(" , ")
        }
    }

    @Test
    fun `rejects subjects that cannot be used for publishing`() {
        listOf("minecraft-identity.*", "minecraft-identity.>", "minecraft identity.changed", "a..b")
            .forEach { invalidSubject ->
                val config = mockk<Config.Scope>()
                every { config.get("nats-url") } returns "nats://nats:4222"
                every { config.get("subject", any()) } returns invalidSubject
                every { config.get("realm") } returns "grounds"

                assertFailsWith<IllegalArgumentException>(invalidSubject) {
                    PermissionsEventListenerProviderFactory { _, _ ->
                            error("publisher must not be created")
                        }
                        .init(config)
                }
            }
    }

    @Test
    fun `is discoverable through the Keycloak service loader`() {
        val factories =
            ServiceLoader.load(EventListenerProviderFactory::class.java).map { it.id }.toSet()

        assertTrue(PermissionsEventListenerProviderFactory.PROVIDER_ID in factories)
    }
}
