package gg.grounds.keycloak.permissions.events

fun interface IdentityChangePublisher : AutoCloseable {
    fun publish(event: MinecraftIdentityChangedEvent)

    override fun close() = Unit
}
