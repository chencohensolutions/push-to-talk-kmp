import Foundation
import Security

/// The server's side of a TLS handshake, for the shared code's checks (IosMtlsHttpClientFactory): its certificate, and
/// whether its chain ends at the server's own CA.
enum ServerTrust {
    static func certificate(_ challenge: URLAuthenticationChallenge) -> Data? {
        guard let trust = challenge.protectionSpace.serverTrust,
              let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
              let leaf = chain.first
        else { return nil }
        return SecCertificateCopyData(leaf) as Data
    }

    /// Whether the chain ends at one of `cas` and the certificate is for a server (the SSL policy without a host
    /// name checks serverAuth), at whatever address it was reached.
    static func chains(_ challenge: URLAuthenticationChallenge, to cas: [Data]) -> Bool {
        guard let trust = challenge.protectionSpace.serverTrust else { return false }
        let anchors = cas.compactMap { SecCertificateCreateWithData(nil, $0 as CFData) }
        guard !anchors.isEmpty else { return false }
        guard SecTrustSetPolicies(trust, SecPolicyCreateSSL(true, nil)) == errSecSuccess,
              SecTrustSetAnchorCertificates(trust, anchors as CFArray) == errSecSuccess,
              SecTrustSetAnchorCertificatesOnly(trust, true) == errSecSuccess
        else { return false }
        var error: CFError?
        return SecTrustEvaluateWithError(trust, &error)
    }

    static func credential(_ challenge: URLAuthenticationChallenge) -> URLCredential? {
        challenge.protectionSpace.serverTrust.map { URLCredential(trust: $0) }
    }
}
