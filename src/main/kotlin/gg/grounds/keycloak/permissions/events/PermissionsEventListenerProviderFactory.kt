package gg.grounds.keycloak.permissions.events

import org.keycloak.Config
import org.keycloak.events.EventListenerProvider
import org.keycloak.events.EventListenerProviderFactory
import org.keycloak.models.KeycloakSession
import org.keycloak.models.KeycloakSessionFactory

class PermissionsEventListenerProviderFactory
internal constructor(private val publisherFactory: (String, String) -> IdentityChangePublisher) :
    EventListenerProviderFactory {
    constructor() : this(NatsIdentityChangePublisher::connect)

    private lateinit var realm: String
    private lateinit var publisher: IdentityChangePublisher

    override fun create(session: KeycloakSession): EventListenerProvider =
        PermissionsEventListenerProvider(session, realm, publisher)

    override fun init(config: Config.Scope) {
        val natsUrl = required(config.get("nats-url"), "nats-url")
        realm = required(config.get("realm"), "realm")
        val subject = required(config.get("subject", DEFAULT_SUBJECT), "subject")
        require(PUBLISH_SUBJECT.matches(subject)) {
            "Permissions event listener subject must be a valid publish subject"
        }
        publisher = publisherFactory(natsUrl, subject)
    }

    override fun postInit(factory: KeycloakSessionFactory) = Unit

    override fun close() {
        if (::publisher.isInitialized) publisher.close()
    }

    override fun getId(): String = PROVIDER_ID

    private fun required(value: String?, property: String): String =
        requireNotNull(value?.trim()?.takeIf(String::isNotEmpty)) {
            "Permissions event listener configuration is required: $property"
        }

    companion object {
        const val PROVIDER_ID = "permissions-events"
        const val DEFAULT_SUBJECT = "minecraft-identity.changed"
        private val PUBLISH_SUBJECT = Regex("^[^\\s.*>]+(?:\\.[^\\s.*>]+)*$")
    }
}
