package com.surf.nursepro.nurse_pro_api.repository;

import com.surf.nursepro.nurse_pro_api.entity.Shift;
import com.surf.nursepro.nurse_pro_api.enums.ShiftType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ShiftRepository extends JpaRepository<Shift, String> {
    Optional<Shift> findBySchedule_IdAndDateAndTypeAndDepartment(String scheduleId, LocalDate date,
                                                                  ShiftType type, String department);

    @Query("select sh from Shift sh where :nurseId member of sh.assignedNurses and sh.date between :from and :to")
    List<Shift> findForNurseBetween(@Param("nurseId") String nurseId, @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);

    @Query("select count(sh) from Shift sh where :nurseId member of sh.assignedNurses and sh.date >= :from")
    long countUpcomingForNurse(@Param("nurseId") String nurseId, @Param("from") LocalDate from);
}
