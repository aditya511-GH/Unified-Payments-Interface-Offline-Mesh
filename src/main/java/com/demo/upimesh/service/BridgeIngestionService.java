package com.demo.upimesh.service;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Orchestrates the full server-side pipeline for one inbound packet from a
 * bridge node:
 *
 *   1. Hash the ciphertext.
 *   2. Try to claim that hash via the idempotency cache.
 *      - If already claimed: this is a duplicate. Drop it.
 *   3. Decrypt the ciphertext with the server's private key.
 *      - If decryption fails: tampered or junk. Reject.
 *   4. Check freshness — reject if signedAt is too old (replay protection).
 *   5. Hand off to SettlementService for the actual debit/credit.
 */
@Service
public class BridgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(BridgeIngestionService.class);

    @Autowired private HybridCryptoService crypto;
    @Autowired private IdempotencyService idempotency;
    @Autowired private SettlementService settlement;
    @Autowired private AccountRepository accounts;
    @Autowired private TransactionRepository transactions;

    @Value("${upi.mesh.packet-max-age-seconds:86400}")
    private long maxAgeSeconds;

    @Value("${upi.demo.expected-pin-hash:03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4}")
    private String expectedPinHash;

    public IngestResult ingest(MeshPacket packet, String bridgeNodeId, int hopCount) {
        try {
            if (packet == null || packet.getCiphertext() == null || packet.getCiphertext().isBlank()) {
                return IngestResult.invalid("?", "invalid_packet");
            }
            String packetHash = crypto.hashCiphertext(packet.getCiphertext());

            // Durable lookup protects against repeats after a process restart.
            if (transactions.existsByPacketHash(packetHash)) {
                return IngestResult.duplicate(packetHash);
            }

            // ---- Decrypt ----
            PaymentInstruction instruction;
            try {
                instruction = crypto.decrypt(packet.getCiphertext());
            } catch (Exception e) {
                log.warn("Decryption failed for packet {}: {}",
                        packetHash.substring(0, 12) + "...", e.getMessage());
                return IngestResult.invalid(packetHash, "decryption_failed");
            }

            String invalidReason = validate(instruction);
            if (invalidReason != null) return IngestResult.invalid(packetHash, invalidReason);

            // ---- Freshness check (replay protection) ----
            long ageSeconds = (Instant.now().toEpochMilli() - instruction.getSignedAt()) / 1000;
            if (ageSeconds > maxAgeSeconds) {
                log.warn("Packet {} too old ({}s), rejected",
                        packetHash.substring(0, 12) + "...", ageSeconds);
                return IngestResult.invalid(packetHash, "stale_packet");
            }
            if (ageSeconds < -300) { // small clock-skew tolerance
                return IngestResult.invalid(packetHash, "future_dated");
            }

            // ---- Idempotency gate ----
            if (!idempotency.claim(packetHash) || transactions.existsByPacketHash(packetHash)) {
                log.info("DUPLICATE packet {} from bridge {} — dropped",
                        packetHash.substring(0, 12) + "...", bridgeNodeId);
                return IngestResult.duplicate(packetHash);
            }

            if (!pinMatches(instruction.getPinHash())) {
                Transaction rejected = settlement.reject(instruction, packetHash, bridgeNodeId,
                        hopCount, "AUTHENTICATION_FAILED");
                return IngestResult.rejected(packetHash, rejected, "authentication_failed");
            }

            // ---- Settle ----
            Transaction tx = settlement.settle(instruction, packetHash, bridgeNodeId, hopCount);
            return tx.getStatus() == Transaction.Status.SETTLED
                    ? IngestResult.settled(packetHash, tx)
                    : IngestResult.rejected(packetHash, tx, tx.getRejectionReason());

        } catch (DataIntegrityViolationException e) {
            try {
                String hash = crypto.hashCiphertext(packet.getCiphertext());
                if (transactions.existsByPacketHash(hash)) return IngestResult.duplicate(hash);
            } catch (Exception ignored) {
                // Preserve a generic error response if the duplicate check also fails.
            }
            log.error("Database integrity failure during packet ingestion", e);
            return IngestResult.error("database_integrity_error");
        } catch (Exception e) {
            log.error("Ingestion error: {}", e.getMessage(), e);
            return IngestResult.error("internal_error");
        }
    }

    private String validate(PaymentInstruction instruction) {
        if (instruction == null || instruction.getSenderVpa() == null || instruction.getSenderVpa().isBlank()
                || instruction.getReceiverVpa() == null || instruction.getReceiverVpa().isBlank()
                || instruction.getAmount() == null || instruction.getNonce() == null || instruction.getNonce().isBlank()
                || instruction.getSignedAt() == null || instruction.getPinHash() == null
                || !instruction.getPinHash().matches("[0-9a-fA-F]{64}")) {
            return "invalid_instruction";
        }
        BigDecimal amount = instruction.getAmount();
        if (amount.signum() <= 0 || amount.scale() > 2 || amount.compareTo(new BigDecimal("1000.00")) > 0) {
            return "invalid_amount";
        }
        if (instruction.getSenderVpa().equals(instruction.getReceiverVpa())) return "same_sender_and_receiver";
        if (!accounts.existsById(instruction.getSenderVpa())) return "unknown_sender";
        if (!accounts.existsById(instruction.getReceiverVpa())) return "unknown_receiver";
        return null;
    }

    private boolean pinMatches(String candidateHash) {
        try {
            return MessageDigest.isEqual(HexFormat.of().parseHex(expectedPinHash),
                    HexFormat.of().parseHex(candidateHash));
        } catch (IllegalArgumentException e) {
            log.error("Configured sandbox PIN hash is invalid");
            return false;
        }
    }

    public record IngestResult(String outcome, String packetHash, String reason, Long transactionId) {
        public static IngestResult settled(String hash, Transaction tx) {
            return new IngestResult("SETTLED", hash, null, tx.getId());
        }
        public static IngestResult rejected(String hash, Transaction tx, String reason) {
            return new IngestResult("REJECTED", hash, reason, tx.getId());
        }
        public static IngestResult duplicate(String hash) {
            return new IngestResult("DUPLICATE_DROPPED", hash, null, null);
        }
        public static IngestResult invalid(String hash, String reason) {
            return new IngestResult("INVALID", hash, reason, null);
        }
        public static IngestResult error(String reason) {
            return new IngestResult("ERROR", "?", reason, null);
        }
    }
}
