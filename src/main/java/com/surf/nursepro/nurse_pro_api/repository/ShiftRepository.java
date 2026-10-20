package com.surf.nursepro.nurse_pro_api.repository;

import com.surf.nursepro.nurse_pro_api.entity.Shift;
import com.surf.nursepro.nurse_pro_api.enums.ShiftType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface ShiftRepository extends JpaRepository<Shift, String> {
    Optional<Shift> findBySchedule_IdAndDateAndTypeAndDepartment(String scheduleId, LocalDate date,
                                                                  ShiftType type, String department);
}
