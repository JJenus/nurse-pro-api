package com.surf.nursepro.nurse_pro_api.repository;

import com.surf.nursepro.nurse_pro_api.entity.SwapRequest;
import com.surf.nursepro.nurse_pro_api.enums.SwapRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SwapRequestRepository extends JpaRepository<SwapRequest, String> {
    List<SwapRequest> findByStatus(SwapRequestStatus status);

    List<SwapRequest> findAllByOrderByCreatedAtDesc();

    List<SwapRequest> findByStatusOrderByCreatedAtDesc(SwapRequestStatus status);

    /** Row lock so concurrent approve/reject/cancel calls on the same request run one after another. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SwapRequest r where r.id = :id")
    Optional<SwapRequest> findByIdForUpdate(@Param("id") String id);

    boolean existsByShiftIdAndRequesterIdAndStatus(String shiftId, String requesterId, SwapRequestStatus status);

    @Query("select r from SwapRequest r where r.status = :status and (r.shiftId in :shiftIds or r.targetShiftId in :shiftIds)")
    List<SwapRequest> findOpenForShifts(@Param("status") SwapRequestStatus status,
                                        @Param("shiftIds") Collection<String> shiftIds);
}
