package gg.grounds.keycloak.permissions.events

import com.fasterxml.jackson.databind.ObjectMapper
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.Nats
import java.time.Duration
import java.util.concurrent.TimeUnit

class NatsIdentityChangePublisher
internal constructor(
    private val connection: Connection,
    private val subject: String,
    private val objectMapper: ObjectMapper,
) : IdentityChangePublisher {
    private val jetStream: JetStream = connection.jetStream()

    override fun publish(event: MinecraftIdentityChangedEvent) {
        requireNotNull(jetStream.publish(subject, objectMapper.writeValueAsBytes(event))) {
            "JetStream did not acknowledge the Minecraft identity invalidation"
        }
    }

    override fun close() {
        try {
            connection.drain(SHUTDOWN_TIMEOUT).get(SHUTDOWN_TIMEOUT.seconds, TimeUnit.SECONDS)
        } finally {
            connection.close()
        }
    }

    companion object {
        private val SHUTDOWN_TIMEOUT = Duration.ofSeconds(5)

        fun connect(natsUrl: String, subject: String): NatsIdentityChangePublisher =
            NatsIdentityChangePublisher(Nats.connect(natsUrl), subject, ObjectMapper())
    }
}
