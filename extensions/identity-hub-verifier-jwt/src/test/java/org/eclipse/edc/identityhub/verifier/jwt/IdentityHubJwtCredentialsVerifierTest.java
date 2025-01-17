/*
 *  Copyright (c) 2022 Microsoft Corporation
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Microsoft Corporation - initial API and implementation
 *
 */

package org.eclipse.edc.identityhub.verifier.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.eclipse.edc.iam.did.spi.credentials.CredentialsVerifier;
import org.eclipse.edc.iam.did.spi.document.DidDocument;
import org.eclipse.edc.iam.did.spi.document.Service;
import org.eclipse.edc.identityhub.client.spi.IdentityHubClient;
import org.eclipse.edc.identityhub.credentials.jwt.JwtCredentialEnvelope;
import org.eclipse.edc.identityhub.spi.credentials.model.Credential;
import org.eclipse.edc.identityhub.spi.credentials.verifier.CredentialEnvelopeVerifierRegistry;
import org.eclipse.edc.identityhub.verifier.IdentityHubCredentialsVerifier;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.identityhub.junit.testfixtures.VerifiableCredentialTestUtil.buildSignedJwt;
import static org.eclipse.edc.identityhub.junit.testfixtures.VerifiableCredentialTestUtil.generateCredential;
import static org.eclipse.edc.identityhub.junit.testfixtures.VerifiableCredentialTestUtil.generateEcKey;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentityHubJwtCredentialsVerifierTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String HUB_BASE_URL = "https://" + "http://some.test.url";
    private static final DidDocument DID_DOCUMENT = DidDocument.Builder.newInstance()
            .service(List.of(new Service("IdentityHub", "IdentityHub", HUB_BASE_URL))).build();
    private static final String ISSUER = "http://some.test.url";
    private static final String SUBJECT = "http://some.test.url";
    private final Monitor monitorMock = mock(Monitor.class);
    private final IdentityHubClient identityHubClientMock = mock(IdentityHubClient.class);
    private final JwtCredentialsVerifier jwtCredentialsVerifierMock = mock(JwtCredentialsVerifier.class);
    private final CredentialEnvelopeVerifierRegistry credentialsVerifierRegistry = mock(CredentialEnvelopeVerifierRegistry.class);
    private final CredentialsVerifier credentialsVerifier = new IdentityHubCredentialsVerifier(identityHubClientMock, monitorMock, credentialsVerifierRegistry);

    @Test
    void getVerifiedClaims_getValidClaims() {
        // Arrange
        var credential = generateCredential();
        var jws = buildSignedJwt(credential, ISSUER, SUBJECT, generateEcKey());
        setUpMocks(jws, true, true);

        // Act
        var credentials = credentialsVerifier.getVerifiedCredentials(DID_DOCUMENT);

        // Assert
        assertThat(credentials.succeeded()).isTrue();
        assertThat(credentials.getContent())
                .hasSize(1)
                .extractingByKey(credential.getId())
                .satisfies(o -> {
                    assertThat(o).isInstanceOf(Credential.class);
                    var cred = (Credential) o;
                    assertThat(cred).usingRecursiveComparison().isEqualTo(credential);
                });
    }

    @Test
    void getVerifiedClaims_filtersSignedByWrongIssuer() {

        // Arrange
        var credential = generateCredential();
        var jws = buildSignedJwt(credential, ISSUER, SUBJECT, generateEcKey());
        setUpMocks(jws, true, false);

        // Act
        var credentials = credentialsVerifier.getVerifiedCredentials(DID_DOCUMENT);

        // Assert
        assertThat(credentials.failed()).isTrue();
    }

    @Test
    void getVerifiedClaims_hubUrlNotResolved() {
        // Arrange
        var didDocument = DidDocument.Builder.newInstance().build();

        // Act
        var credentials = credentialsVerifier.getVerifiedCredentials(didDocument);

        // Assert
        assertThat(credentials.failed()).isTrue();
        assertThat(credentials.getFailureMessages()).containsExactly("Could not retrieve identity hub URL from DID document");
    }

    @Test
    void getVerifiedClaims_idHubCallFails() {

        // Arrange
        when(identityHubClientMock.getVerifiableCredentials(HUB_BASE_URL)).thenReturn(Result.failure("error"));

        // Act
        var credentials = credentialsVerifier.getVerifiedCredentials(DID_DOCUMENT);

        // Assert
        assertThat(credentials.failed()).isTrue();
    }

    @Test
    void getVerifiedClaims_verifiableCredentialsWithWrongFormat() {

        // Arrange
        var jws = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), new JWTClaimsSet.Builder().build());
        setUpMocks(jws, true, true);

        // Act
        var credentials = credentialsVerifier.getVerifiedCredentials(DID_DOCUMENT);

        // Assert
        assertThat(credentials.failed()).isTrue();
        verify(monitorMock).severe(ArgumentMatchers.<Supplier<String>>any());
    }

    @Test
    void getVerifiedClaims_verifiableCredentialsWithMissingId() {

        // Arrange
        var jwsHeader = new JWSHeader.Builder(JWSAlgorithm.ES256).build();
        var jwtClaims = new JWTClaimsSet.Builder()
                .claim("vc", Map.of("key1", "value1"))
                .issuer("test issuer")
                .subject(SUBJECT)
                .build();
        var jws = new SignedJWT(jwsHeader, jwtClaims);
        setUpMocks(jws, true, true);

        // Act
        var credentials = credentialsVerifier.getVerifiedCredentials(DID_DOCUMENT);

        // Assert
        assertThat(credentials.failed()).isTrue();
        verify(monitorMock).severe(ArgumentMatchers.<Supplier<String>>any());
    }

    private void setUpMocks(SignedJWT jws, boolean isSigned, boolean claimsValid) {
        when(credentialsVerifierRegistry.resolve("application/vc+jwt")).thenReturn(new JwtCredentialEnvelopeVerifier(jwtCredentialsVerifierMock, OBJECT_MAPPER));
        when(identityHubClientMock.getVerifiableCredentials(HUB_BASE_URL)).thenReturn(Result.success(List.of(new JwtCredentialEnvelope(jws))));
        when(jwtCredentialsVerifierMock.isSignedByIssuer(jws)).thenReturn(isSigned ? Result.success() : Result.failure("JWT not signed"));
        when(jwtCredentialsVerifierMock.verifyClaims(eq(jws), any())).thenReturn(claimsValid ? Result.success() : Result.failure("VC not valid"));
    }

}
