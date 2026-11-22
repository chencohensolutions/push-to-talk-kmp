package com.chencohen.ptt.enrollment

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.Optional
import com.chencohen.ptt.graphql.EnrollDeviceMutation
import com.chencohen.ptt.graphql.EnrollWithInviteMutation
import com.chencohen.ptt.graphql.EnrollmentPreviewQuery
import com.chencohen.ptt.graphql.InvitePreviewQuery
import com.chencohen.ptt.graphql.type.InviteEnrollInput
import com.chencohen.ptt.graphql.type.InviteRejectedReason
import com.chencohen.ptt.graphql.type.EnrollDeviceInput
import com.chencohen.ptt.graphql.type.EnrollmentRejectedReason
import com.chencohen.ptt.network.describe
import kotlin.io.encoding.Base64
import com.chencohen.ptt.graphql.type.KeyProtection as GraphQlKeyProtection

/** The server turned the request down; see `EnrollmentRejectedReason` in the server schema. */
internal class EnrollmentRejectedException(val reason: EnrollmentRejectedReason) : Exception("Enrollment rejected: $reason")

/** Anything other than an answer from the server: TLS, network, HTTP or GraphQL errors. */
internal class EnrollmentTransportException(message: String) : Exception(message)

internal data class EnrollmentPreview(val user: String, val expiresAt: String)

internal class IssuedCertificate(
    val user: String,
    val deviceName: String,
    /** 1 to 127, unique among the user's devices. */
    val deviceNumber: Int,
    val certificateDer: ByteArray,
    val expiresAt: String,
    /** The CA that signs the server's certificates, PEM; null when the server didn't say. */
    val caCertificate: String? = null,
)

internal suspend fun previewEnrollment(apollo: ApolloClient, code: String): EnrollmentPreview {
    val result = apollo.query(EnrollmentPreviewQuery(code)).execute().dataOrThrow().enrollmentPreview
    result.onEnrollmentRejected?.let { throw EnrollmentRejectedException(it.reason) }
    val preview = result.onEnrollmentPreview ?: throw EnrollmentTransportException("Unexpected answer ${result.__typename}")
    return EnrollmentPreview(preview.user, preview.expiresAt)
}

internal suspend fun enrollDevice(
    apollo: ApolloClient,
    code: String,
    deviceName: String,
    csrDer: ByteArray,
    protection: KeyProtection,
): IssuedCertificate {
    val input = EnrollDeviceInput(
        code = code,
        deviceName = deviceName,
        csr = Base64.encode(csrDer),
        keyProtection = GraphQlKeyProtection.safeValueOf(protection.name),
    )
    val result = apollo.mutation(EnrollDeviceMutation(input)).execute().dataOrThrow().enrollDevice
    result.onEnrollmentRejected?.let { throw EnrollmentRejectedException(it.reason) }
    val enrolled = result.onEnrolledDevice ?: throw EnrollmentTransportException("Unexpected answer ${result.__typename}")
    return IssuedCertificate(
        enrolled.user, enrolled.deviceName, enrolled.deviceNumber, Base64.decode(enrolled.certificate), enrolled.expiresAt,
        enrolled.caCertificate,
    )
}

/** The server turned an invite down; see `InviteRejectedReason` in the server schema. */
internal class InviteRejectedException(val reason: InviteRejectedReason) : Exception("Invite rejected: $reason")

internal data class InvitePreview(
    val inviter: String,
    val inviterDisplayName: String?,
    val email: String?,
    val expiresAt: String,
)

internal suspend fun previewInvite(apollo: ApolloClient, code: String): InvitePreview {
    val result = apollo.query(InvitePreviewQuery(code)).execute().dataOrThrow().invitePreview
    result.onInviteRejected?.let { throw InviteRejectedException(it.reason) }
    val preview = result.onInvitePreview ?: throw EnrollmentTransportException("Unexpected answer ${result.__typename}")
    return InvitePreview(preview.inviter, preview.inviterDisplayName, preview.email, preview.expiresAt)
}

/** The certificate, and the inviter's pairing token (null when the server couldn't make one). */
internal class InviteIssued(val certificate: IssuedCertificate, val inviter: String, val pairingToken: String?)

internal suspend fun enrollWithInvite(
    apollo: ApolloClient,
    code: String,
    /** The six digits the inviter sent beside the link. */
    passcode: String,
    /** The invite's email, or for an invite to anyone the friend's own (null: none). */
    email: String?,
    displayName: String,
    deviceName: String,
    csrDer: ByteArray,
    protection: KeyProtection,
): InviteIssued {
    val input = InviteEnrollInput(
        code = code,
        passcode = passcode,
        email = Optional.presentIfNotNull(email),
        displayName = displayName,
        deviceName = deviceName,
        csr = Base64.encode(csrDer),
        keyProtection = GraphQlKeyProtection.safeValueOf(protection.name),
    )
    val result = apollo.mutation(EnrollWithInviteMutation(input)).execute().dataOrThrow().enrollWithInvite
    result.onInviteRejected?.let { throw InviteRejectedException(it.reason) }
    val enrolled = result.onInviteEnrolled ?: throw EnrollmentTransportException("Unexpected answer ${result.__typename}")
    val d = enrolled.device
    return InviteIssued(
        IssuedCertificate(d.user, d.deviceName, d.deviceNumber, Base64.decode(d.certificate), d.expiresAt, d.caCertificate),
        enrolled.pairing.inviter,
        enrolled.pairing.pairingToken?.token,
    )
}

private fun <D : Operation.Data> ApolloResponse<D>.dataOrThrow(): D {
    exception?.let { throw EnrollmentTransportException(it.describe()) }
    errors?.firstOrNull()?.let { throw EnrollmentTransportException(it.message) }
    return data ?: throw EnrollmentTransportException("Empty response")
}
