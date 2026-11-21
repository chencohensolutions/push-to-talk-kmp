package com.chencohen.ptt.messaging

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.Optional
import com.chencohen.ptt.crypto.PrekeyBundle
import com.chencohen.ptt.crypto.PublicPrekey
import com.chencohen.ptt.crypto.SignedPublicPrekey
import com.chencohen.ptt.graphql.AcceptInviteMutation
import com.chencohen.ptt.graphql.BlockMutation
import com.chencohen.ptt.graphql.CreateInviteMutation
import com.chencohen.ptt.graphql.ConfirmPairMutation
import com.chencohen.ptt.graphql.ContactsQuery
import com.chencohen.ptt.graphql.MyIdentityKeyQuery
import com.chencohen.ptt.graphql.PairingTokenMutation
import com.chencohen.ptt.graphql.PrekeyBundlesQuery
import com.chencohen.ptt.graphql.PrekeyCountQuery
import com.chencohen.ptt.graphql.ReplaceIdentityKeyMutation
import com.chencohen.ptt.graphql.UnblockMutation
import com.chencohen.ptt.graphql.UnpairMutation
import com.chencohen.ptt.graphql.UploadPrekeysMutation
import com.chencohen.ptt.graphql.type.PairState
import com.chencohen.ptt.graphql.type.PrekeyInput
import com.chencohen.ptt.graphql.type.SignedPrekeyInput
import com.chencohen.ptt.graphql.type.UploadPrekeysInput
import com.chencohen.ptt.server.ServerHttp
import kotlin.io.encoding.Base64

/** How many one-time prekeys the server holds for this phone, and when to upload more. */
data class PrekeyCount(val oneTimePrekeys: Int, val oneTimeKyberPrekeys: Int, val refillBelow: Int)

/** An `uploadPrekeys` call: the identity, and what to add or replace (null: keep what the server has). */
internal class PrekeyUpload(
    val identityKey: ByteArray,
    val registrationId: Int,
    val signedPrekey: SignedPublicPrekey?,
    val kyberLastResort: SignedPublicPrekey?,
    val prekeys: List<PublicPrekey>,
    val kyberPrekeys: List<SignedPublicPrekey>,
)

/** `prekeyBundles`: the user's identity key as the server has it, and one bundle per active device. */
internal class Bundles(val user: String, val identityKey: ByteArray, val devices: List<PrekeyBundle>)

/** A paired user as the server lists it (`contacts`). */
internal class ServerContact(
    val user: String,
    val identityKey: ByteArray?,
    val since: String,
    val blocked: Boolean,
    val devices: Map<Int, Int>,
    /** The name they gave for themselves when they joined by an invite; null otherwise. */
    val displayName: String? = null,
)

internal class ServerContacts(val contacts: List<ServerContact>, val blocked: List<String>)

/** A GraphQL error the server answered with; [code] is its `extensions.code` (docs/messaging.md). */
class ApiException(val code: String?, message: String) : Exception(message)

/** The server's error codes the phone acts on. */
internal object ApiCodes {
    const val MISSING_KEYS = "MISSING_KEYS"
    const val IDENTITY_KEY_MISMATCH = "IDENTITY_KEY_MISMATCH"
    const val OTHER_DEVICES_ACTIVE = "OTHER_DEVICES_ACTIVE"
    const val UNKNOWN_USER = "UNKNOWN_USER"
    const val NOT_PAIRED = "NOT_PAIRED"
    const val BLOCKED = "BLOCKED"
    const val NO_PAIRING = "NO_PAIRING"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val NOT_ENROLLED = "NOT_ENROLLED"
}

/** The keys, pairing and contacts part of the server's API; [ApolloMessagingApi] in the app, a fake in tests. */
internal interface MessagingApi {
    /** `me.identityKey`: the user's identity key on the server; null before any of its phones uploaded one. */
    suspend fun myIdentityKey(): ByteArray?

    suspend fun uploadPrekeys(upload: PrekeyUpload): PrekeyCount

    suspend fun prekeyCount(): PrekeyCount

    suspend fun prekeyBundles(user: String, routingToken: String?): Bundles

    suspend fun replaceIdentityKey(identityKey: ByteArray): Boolean

    /** A pairing token and when it expires (RFC 3339). */
    suspend fun pairingToken(): Pair<String, String>

    /** True when the pair is made (`PAIRED`), false while the other user hasn't confirmed (`WAITING`). */
    suspend fun confirmPair(user: String): Boolean

    suspend fun unpair(user: String): Boolean

    suspend fun block(user: String): Boolean

    suspend fun unblock(user: String): Boolean

    suspend fun contacts(): ServerContacts

    /** An invite from this user (for [email] when not null): its code, and when it expires (RFC 3339). */
    suspend fun createInvite(email: String?): Pair<String, String>

    /** Redeems an invite on this enrolled phone: the inviter's pairing token. */
    suspend fun acceptInvite(code: String): InvitePairing
}

/** What redeeming an invite gave: the inviter, and their pairing token (null when the server couldn't make one). */
internal class InvitePairing(val inviter: String, val token: String?)

/** acceptInvite was turned down; [reason] is the server's `InviteRejectedReason`. */
class InviteRefusedException(val reason: String) : Exception("Invite refused: $reason")

/** [MessagingApi] over the app's mTLS GraphQL client (and its finder, which follows the server to a new address). */
internal class ApolloMessagingApi(private val http: ServerHttp) : MessagingApi {
    private suspend fun <D : Operation.Data> run(block: suspend (ApolloClient) -> ApolloResponse<D>): D {
        val response = http.graphql(block) ?: throw ApiException(ApiCodes.NOT_ENROLLED, "This phone isn't registered")
        response.exception?.let { throw it }
        response.errors?.firstOrNull()?.let { error ->
            throw ApiException(error.extensions?.get("code") as? String, error.message)
        }
        return response.data ?: throw ApiException(null, "The server answered nothing")
    }

    override suspend fun myIdentityKey(): ByteArray? =
        run { it.query(MyIdentityKeyQuery()).execute() }.me.identityKey?.let(Base64::decode)

    override suspend fun uploadPrekeys(upload: PrekeyUpload): PrekeyCount {
        val input = UploadPrekeysInput(
            identityKey = Base64.encode(upload.identityKey),
            registrationId = upload.registrationId,
            signedPrekey = Optional.presentIfNotNull(upload.signedPrekey?.toInput()),
            kyberLastResortPrekey = Optional.presentIfNotNull(upload.kyberLastResort?.toInput()),
            oneTimePrekeys = Optional.presentIfNotNull(upload.prekeys.takeIf { it.isNotEmpty() }?.map { PrekeyInput(it.keyId, Base64.encode(it.publicKey)) }),
            oneTimeKyberPrekeys = Optional.presentIfNotNull(upload.kyberPrekeys.takeIf { it.isNotEmpty() }?.map { it.toInput() }),
        )
        val count = run { it.mutation(UploadPrekeysMutation(input)).execute() }.uploadPrekeys
        return PrekeyCount(count.oneTimePrekeys, count.oneTimeKyberPrekeys, count.refillBelow)
    }

    override suspend fun prekeyCount(): PrekeyCount {
        val count = run { it.query(PrekeyCountQuery()).execute() }.prekeyCount
        return PrekeyCount(count.oneTimePrekeys, count.oneTimeKyberPrekeys, count.refillBelow)
    }

    override suspend fun prekeyBundles(user: String, routingToken: String?): Bundles {
        val answer = run { it.query(PrekeyBundlesQuery(user, Optional.presentIfNotNull(routingToken))).execute() }.prekeyBundles
        return Bundles(
            user = answer.user,
            identityKey = Base64.decode(answer.identityKey),
            devices = answer.devices.map { d ->
                PrekeyBundle(
                    device = d.deviceNumber,
                    registrationId = d.registrationId,
                    signedPrekey = SignedPublicPrekey(d.signedPrekey.keyId, Base64.decode(d.signedPrekey.publicKey), Base64.decode(d.signedPrekey.signature)),
                    prekey = d.prekey?.let { PublicPrekey(it.keyId, Base64.decode(it.publicKey)) },
                    kyberPrekey = SignedPublicPrekey(d.kyberPrekey.keyId, Base64.decode(d.kyberPrekey.publicKey), Base64.decode(d.kyberPrekey.signature)),
                )
            },
        )
    }

    override suspend fun replaceIdentityKey(identityKey: ByteArray): Boolean =
        run { it.mutation(ReplaceIdentityKeyMutation(Base64.encode(identityKey))).execute() }.replaceIdentityKey

    override suspend fun pairingToken(): Pair<String, String> {
        val token = run { it.mutation(PairingTokenMutation()).execute() }.pairingToken
        return token.token to token.expiresAt
    }

    override suspend fun confirmPair(user: String): Boolean =
        run { it.mutation(ConfirmPairMutation(user)).execute() }.confirmPair == PairState.PAIRED

    override suspend fun unpair(user: String): Boolean = run { it.mutation(UnpairMutation(user)).execute() }.unpair

    override suspend fun block(user: String): Boolean = run { it.mutation(BlockMutation(user)).execute() }.block

    override suspend fun unblock(user: String): Boolean = run { it.mutation(UnblockMutation(user)).execute() }.unblock

    override suspend fun contacts(): ServerContacts {
        val data = run { it.query(ContactsQuery()).execute() }
        return ServerContacts(
            contacts = data.contacts.map { c ->
                ServerContact(
                    user = c.user,
                    displayName = c.displayName,
                    identityKey = c.identityKey?.let(Base64::decode),
                    since = c.since,
                    blocked = c.blocked,
                    devices = c.devices.associate { it.deviceNumber to it.registrationId },
                )
            },
            blocked = data.blocked,
        )
    }

    override suspend fun createInvite(email: String?): Pair<String, String> {
        val created = run { it.mutation(CreateInviteMutation(Optional.presentIfNotNull(email))).execute() }.createInvite
        return created.code to created.invite.expiresAt
    }

    override suspend fun acceptInvite(code: String): InvitePairing {
        val answer = run { it.mutation(AcceptInviteMutation(code)).execute() }.acceptInvite
        answer.onInviteRejected?.let { throw InviteRefusedException(it.reason.rawValue) }
        val pairing = answer.onInvitePairing ?: throw ApiException(null, "Unexpected answer ${answer.__typename}")
        return InvitePairing(pairing.inviter, pairing.pairingToken?.token)
    }

    private fun SignedPublicPrekey.toInput() = SignedPrekeyInput(keyId, Base64.encode(publicKey), Base64.encode(signature))
}
