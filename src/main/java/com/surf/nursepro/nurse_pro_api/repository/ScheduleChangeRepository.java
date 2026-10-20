package com.surf.nursepro.nurse_pro_api.repository;

import com.surf.nursepro.nurse_pro_api.entity.ScheduleChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScheduleChangeRepository extends JpaRepository<ScheduleChange, String> {
    List<ScheduleChange> findByScheduleIdOrderByCreatedAtDesc(String scheduleId);
}
