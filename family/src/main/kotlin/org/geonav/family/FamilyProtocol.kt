package org.geonav.family

/**
 * Wire-level constants for the sharing protocol.
 *
 * The protocol is unchanged from Luogo-FOSS: same relay REST endpoints, same WebSocket
 * frame shape, same invite payload, protocol version 1. That keeps KinNav able to talk to
 * an existing self-hosted Luogo relay, and lets Luogo and KinNav users in the same group
 * still read each other's locations.
 */
object FamilyProtocol {

    /** Bumped only when the relay API or frame format changes. */
    const val VERSION = 1

    /** Default rendezvous relay. Any self-hosted relay can be substituted in settings. */
    const val DEFAULT_RELAY_URL = "https://relay.luogo.app"

    /** Emitted invite prefix. */
    const val INVITE_PREFIX = "geonav-invite-key:"

    /** Accepted invite prefix from upstream Luogo, so old invite codes still join. */
    const val LEGACY_INVITE_PREFIX = "luogo-invite-key:"

    const val INVITE_VERSION = "v1"
}
