# Keycloak Permissions Event Listener

A Keycloak event-listener plugin that publishes Minecraft identity invalidations for the Grounds permissions service through NATS JetStream.

## Behavior

The listener emits a user-scoped invalidation after the active Keycloak transaction commits when:

- a user joins or leaves a Keycloak group;
- a group is renamed or moved, including when the change affects descendant group paths;
- a user is updated, including when managed Minecraft identity attributes are cleared;
- a Minecraft federated identity is linked or unlinked;
- a user logs in, registers, or links a federated identity; or
- a user is deleted.

Events contain only the Keycloak realm ID, Keycloak user ID, and a stable reason. Failed or rolled-back Keycloak transactions do not emit events. Each publish waits for a JetStream acknowledgement. Publish failures are logged and do not fail the completed Keycloak operation. The permissions service performs scheduled reconciliation to recover changes that cannot be resolved from an individual Keycloak event.

## Build

The project requires JDK 21. Gradle toolchain downloads are disabled.

```bash
./gradlew shadowJar
```

The provider JAR is written to:

```text
build/libs/keycloak-permissions-event-listener.jar
```

## Installation

Copy the JAR into the Keycloak providers directory, rebuild Keycloak, and enable the `permissions-events` event listener in the target realm.

```bash
cp keycloak-permissions-event-listener.jar /opt/keycloak/providers/
/opt/keycloak/bin/kc.sh build
```

## Configuration

| Environment variable                                 | Required | Default                      | Description                            |
|------------------------------------------------------|----------|------------------------------|----------------------------------------|
| `KC_SPI_EVENTS_LISTENER_PERMISSIONS_EVENTS_NATS_URL` | Yes      | -                            | NATS server URL with JetStream enabled |
| `KC_SPI_EVENTS_LISTENER_PERMISSIONS_EVENTS_REALM`    | Yes      | -                            | Accepted Keycloak realm ID or name     |
| `KC_SPI_EVENTS_LISTENER_PERMISSIONS_EVENTS_SUBJECT`  | No       | `minecraft-identity.changed` | NATS subject for invalidation events   |

The configured publish subject must be retained by a JetStream stream before the listener starts publishing events.

Example:

```bash
export KC_SPI_EVENTS_LISTENER_PERMISSIONS_EVENTS_NATS_URL="nats://nats.nats.svc.cluster.local:4222"
export KC_SPI_EVENTS_LISTENER_PERMISSIONS_EVENTS_REALM="grounds"
```

The event listener is selected by its provider ID:

```text
permissions-events
```

## Event contract

```json
{
  "realmId": "grounds",
  "keycloakUserId": "d9d7d76e-03ad-4af8-bcb2-391aa5719459",
  "reason": "group_membership_changed"
}
```

## License

This project is licensed under the GNU Affero General Public License v3.0. See [LICENSE](LICENSE).
