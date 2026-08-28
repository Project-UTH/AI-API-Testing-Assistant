package com.aiapitesting.backend.security;

import com.aiapitesting.backend.exception.GoogleAuthFailedException;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;

/**
 * Verify Google ID token offline qua GoogleIdTokenVerifier (tu cache public key cua Google, khong
 * goi network moi lan verify). Khong dung OAuth Authorization Code day du - app chi can biet danh
 * tinh (email), khong can goi API nao khac cua Google nen khong can client_secret.
 */
@Service
public class GoogleTokenVerifierService {

    private final GoogleIdTokenVerifier verifier;

    public GoogleTokenVerifierService(@Value("${google.oauth.client-id:}") String clientId) {
        this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(clientId))
                .build();
    }

    public GoogleIdToken.Payload verify(String idTokenString) {
        GoogleIdToken idToken;
        try {
            idToken = verifier.verify(idTokenString);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            throw new GoogleAuthFailedException("Không xác thực được với Google, vui lòng thử lại");
        }

        if (idToken == null) {
            throw new GoogleAuthFailedException("Token Google không hợp lệ hoặc đã hết hạn");
        }

        GoogleIdToken.Payload payload = idToken.getPayload();
        if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
            throw new GoogleAuthFailedException("Email Google chưa được xác thực");
        }

        return payload;
    }
}
