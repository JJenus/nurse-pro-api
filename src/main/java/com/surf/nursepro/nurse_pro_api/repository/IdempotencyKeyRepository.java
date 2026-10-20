package com.surf.nursepro.nurse_pro_api.repository;

import com.surf.nursepro.nurse_pro_api.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {
    Optional<IdempotencyKey> findByScopeAndKeyValue(String scope, String keyValue);
}
