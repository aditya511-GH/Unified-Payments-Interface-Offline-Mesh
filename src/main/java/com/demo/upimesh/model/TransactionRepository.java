package com.demo.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findAllByOrderByIdDesc();
    boolean existsByPacketHash(String packetHash);
    long countByStatus(Transaction.Status status);
}
