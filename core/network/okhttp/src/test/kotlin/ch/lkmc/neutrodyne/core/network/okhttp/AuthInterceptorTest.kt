// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.Origin
import com.google.common.truth.Truth.assertThat
import mockwebserver3.MockWebServer
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Rule
import org.junit.Test

class AuthInterceptorTest {
    @get:Rule val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    private fun clients(credentials: CredentialLookup): NetworkClients = newNetworkClients(credentials)

    private fun credentialFor(
        origin: Origin,
        value: String,
    ) = CredentialLookup { o ->
        if (o == origin) value else null
    }

    @Test
    fun `adds the credential on the same origin`() {
        server.enqueue(mockResponse())
        val origin = Origin("http", "localhost", server.port)
        val clients = clients(credentialFor(origin, "Basic abc"))
        clients.api
            .newCall(Request.Builder().url(server.url("/feed.xml")).build())
            .execute()
            .close()

        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Basic abc")
    }

    @Test
    fun `adds nothing when the lookup has no credential`() {
        server.enqueue(mockResponse())
        val clients = clients(CredentialLookup { null })
        clients.api
            .newCall(Request.Builder().url(server.url("/")).build())
            .execute()
            .close()

        assertThat(server.takeRequest().headers["Authorization"]).isNull()
    }

    @Test
    fun `does not touch an Authorization header the caller set`() {
        server.enqueue(mockResponse())
        val origin = Origin("http", "localhost", server.port)
        val clients = clients(credentialFor(origin, "Basic stored"))
        val request =
            Request
                .Builder()
                .url(server.url("/"))
                .header("Authorization", "Bearer live")
                .build()
        clients.api
            .newCall(request)
            .execute()
            .close()

        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer live")
    }

    @Test
    fun `drops the credential on a cross-origin redirect hop`() {
        val second = MockWebServer()
        second.start()
        try {
            second.enqueue(mockResponse())
            server.enqueue(mockResponse(code = 302, body = "", "Location" to second.url("/landed").toString()))

            val origin = Origin("http", "localhost", server.port)
            val clients = clients(credentialFor(origin, "Basic abc"))
            clients.api
                .newCall(Request.Builder().url(server.url("/")).build())
                .execute()
                .close()

            assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Basic abc")
            assertThat(second.takeRequest().headers["Authorization"]).isNull()
        } finally {
            second.close()
        }
    }

    @Test
    fun `https to http on the same host is a different origin`() {
        val held =
            HeldCertificate
                .Builder()
                .commonName("localhost")
                .addSubjectAlternativeName("localhost")
                .build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(held).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()

        val http = MockWebServer()
        http.start()
        val https = MockWebServer()
        https.useHttps(serverCerts.sslSocketFactory())
        https.start()
        try {
            http.enqueue(mockResponse())
            https.enqueue(mockResponse(code = 302, body = "", "Location" to http.url("/landed").toString()))

            val httpsOrigin = Origin("https", "localhost", https.port)
            val clients = clients(credentialFor(httpsOrigin, "Basic abc"))
            val trusting =
                clients.api
                    .newBuilder()
                    .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
                    .build()
            trusting.newCall(Request.Builder().url(https.url("/")).build()).execute().close()

            assertThat(https.takeRequest().headers["Authorization"]).isEqualTo("Basic abc")
            assertThat(http.takeRequest().headers["Authorization"]).isNull()
        } finally {
            http.close()
            https.close()
        }
    }

    @Test
    fun `Origin-of normalizes case and default ports`() {
        assertThat(Origin.of("http://EXAMPLE.com/feed".toHttpUrl()))
            .isEqualTo(Origin("http", "example.com", 80))
        assertThat(Origin.of("https://example.com:443/feed".toHttpUrl()))
            .isEqualTo(Origin("https", "example.com", 443))
        assertThat(Origin.of("https://example.com:8443/feed".toHttpUrl()))
            .isEqualTo(Origin("https", "example.com", 8443))
    }
}
