package com.surf.nursepro.nurse_pro_api.repository;

import com.surf.nursepro.nurse_pro_api.entity.ScheduleConflict;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScheduleConflictRepository extends JpaRepository<ScheduleConflict, String> {
    List<ScheduleConflict> findByScheduleId(String scheduleId);

    // Derived deletes load and remove each entity, which also clears the suggestions collection table.
    void deleteByScheduleId(String scheduleId);

    void deleteByShiftId(String shiftId);
}
