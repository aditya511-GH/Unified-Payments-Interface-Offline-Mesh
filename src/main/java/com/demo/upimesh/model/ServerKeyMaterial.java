package com.demo.upimesh.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Sandbox RSA key material persisted so encrypted offline packets survive app restarts. */
@Entity
@Table(name = "mesh_server_key")
public class ServerKeyMaterial {
    @Id
    private String id;

    @Column(nullable = false, length = 4096)
    private String publicKey;

    @Column(nullable = false, length = 4096)
    private String privateKey;

    protected ServerKeyMaterial() {}

    public ServerKeyMaterial(String id, String publicKey, String privateKey) {
        this.id = id;
        this.publicKey = publicKey;
        this.privateKey = privateKey;
    }

    public String getPublicKey() { return publicKey; }
    public String getPrivateKey() { return privateKey; }
}
