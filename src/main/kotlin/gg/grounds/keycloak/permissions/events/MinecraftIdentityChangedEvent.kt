package gg.grounds.keycloak.permissions.events

data class MinecraftIdentityChangedEvent(
    val realmId: String,
    val keycloakUserId: String,
    val reason: String,
)
