package gg.grounds.keycloak.permissions.events

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.api.PublishAck
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals

class NatsIdentityChangePublisherTest {
    @Test
    fun `publishes the minimal event contract as json`() {
        val connection = mockk<Connection>(relaxed = true)
        val jetStream = mockk<JetStream>()
        val acknowledgement = mockk<PublishAck>()
        val payload = slot<ByteArray>()
        every { connection.jetStream() } returns jetStream
        every { jetStream.publish("minecraft-identity.changed", capture(payload)) } returns
            acknowledgement
        val event =
            MinecraftIdentityChangedEvent(
                realmId = "grounds",
                keycloakUserId = "user-1",
                reason = "identity_refreshed",
            )

        NatsIdentityChangePublisher(connection, "minecraft-identity.changed", ObjectMapper())
            .publish(event)

        verify(exactly = 1) {
            jetStream.publish("minecraft-identity.changed", any<ByteArray>())
        }
        assertEquals(
            setOf("realmId", "keycloakUserId", "reason"),
            ObjectMapper().readTree(payload.captured).fieldNames().asSequence().toSet(),
        )
    }

    @Test
    fun `drains the connection before closing`() {
        val connection = mockk<Connection>(relaxed = true)
        every { connection.jetStream() } returns mockk()
        every { connection.drain(any<Duration>()) } returns CompletableFuture.completedFuture(true)

        NatsIdentityChangePublisher(connection, "minecraft-identity.changed", ObjectMapper())
            .close()

        verifyOrder {
            connection.drain(any<Duration>())
            connection.close()
        }
    }
}
