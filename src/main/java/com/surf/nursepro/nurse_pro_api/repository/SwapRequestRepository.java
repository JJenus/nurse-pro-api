package com.surf.nursepro.nurse_pro_api.repository;

import com.surf.nursepro.nurse_pro_api.entity.SwapRequest;
import com.surf.nursepro.nurse_pro_api.enums.SwapRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SwapRequestRepository extends JpaRepository<SwapRequest, String> {
    List<SwapRequest> findByStatus(SwapRequestStatus status);
}