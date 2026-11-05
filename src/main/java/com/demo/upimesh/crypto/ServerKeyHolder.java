package com.demo.upimesh.crypto;

import jakarta.annotation.PostConstruct;
import com.demo.upimesh.model.ServerKeyMaterial;
import com.demo.upimesh.model.ServerKeyMaterialRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Holds the server's RSA keypair.
 *
 * In production, the private key would live in an HSM (Hardware Security Module)
 * or at least a KMS like AWS KMS / HashiCorp Vault. NEVER in the JAR or source.
 *
 * The sandbox stores its keypair in the configured database so queued offline
 * packets survive normal service restarts. Only the public key is exposed via
 * /api/server-key; production deployments must use a dedicated key manager.
 */
@Component
public class ServerKeyHolder {

    private static final Logger log = LoggerFactory.getLogger(ServerKeyHolder.class);

    private KeyPair keyPair;

    @Autowired private ServerKeyMaterialRepository keyMaterialRepository;

    @PostConstruct
    public void init() throws Exception {
        var existing = keyMaterialRepository.findById("active");
        if (existing.isPresent()) {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            byte[] publicBytes = Base64.getDecoder().decode(existing.get().getPublicKey());
            byte[] privateBytes = Base64.getDecoder().decode(existing.get().getPrivateKey());
            this.keyPair = new KeyPair(
                    factory.generatePublic(new X509EncodedKeySpec(publicBytes)),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes)));
            log.info("Loaded persistent sandbox RSA keypair");
            return;
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        this.keyPair = gen.generateKeyPair();
        keyMaterialRepository.save(new ServerKeyMaterial(
                "active",
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())));
        log.info("Server RSA keypair generated (2048-bit). Public key fingerprint: {}",
                getPublicKeyBase64().substring(0, 32) + "...");
    }

    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }

    public PrivateKey getPrivateKey() {
        return keyPair.getPrivate();
    }

    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }
}
