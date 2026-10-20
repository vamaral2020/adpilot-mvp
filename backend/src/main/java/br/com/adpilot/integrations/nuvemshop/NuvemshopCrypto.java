package br.com.adpilot.integrations.nuvemshop;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class NuvemshopCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final NuvemshopSettings settings;

    public NuvemshopCrypto(NuvemshopSettings settings) {
        this.settings = settings;
    }

    private SecretKeySpec key() {
        if (!settings.validKey()) {
            throw new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE,
                    "NUVEMSHOP_ENCRYPTION_NOT_CONFIGURED", "Configure uma chave AES-256 da integracao.");
        }
        return new SecretKeySpec(Base64.getDecoder().decode(settings.encryptionKey()), "AES");
    }

    public byte[] encrypt(String token) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] cipherText = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + cipherText.length).put(iv).put(cipherText).array();
        } catch (GeneralSecurityException e) {
            throw new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE,
                    "NUVEMSHOP_ENCRYPTION_ERROR", "Nao foi possivel proteger a credencial da loja.");
        }
    }

    public String decrypt(byte[] encrypted) {
        if (encrypted == null || encrypted.length < 29) {
            throw new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE,
                    "NUVEMSHOP_INVALID_CREDENTIAL", "Credencial da loja corrompida.");
        }
        try {
            byte[] iv = new byte[12];
            System.arraycopy(encrypted, 0, iv, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted, 12, encrypted.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE,
                    "NUVEMSHOP_DECRYPTION_ERROR", "Nao foi possivel ler a credencial da loja.");
        }
    }
}
