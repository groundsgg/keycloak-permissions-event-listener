package gg.grounds.keycloak.permissions.events

enum class IdentityChangeReason(val value: String, internal val priority: Int) {
    IDENTITY_REFRESHED("identity_refreshed", 0),
    GROUP_MEMBERSHIP_CHANGED("group_membership_changed", 1),
    GROUP_CHANGED("group_changed", 2),
    MINECRAFT_IDENTITY_CHANGED("minecraft_identity_changed", 3),
    USER_DELETED("user_deleted", 4),
}
