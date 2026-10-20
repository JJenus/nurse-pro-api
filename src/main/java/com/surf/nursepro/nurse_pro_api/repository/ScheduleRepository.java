package com.surf.nursepro.nurse_pro_api.repository;

import com.surf.nursepro.nurse_pro_api.entity.Schedule;
import com.surf.nursepro.nurse_pro_api.enums.ScheduleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, String> {
    Optional<Schedule> findByMonthAndYear(int month, int year);

    boolean existsByMonthAndYear(int month, int year);
    List<Schedule> findByMonthInAndYear(List<Integer> months, int year);

    @Query("select s from Schedule s where (:year is null or s.year = :year) and (:month is null or s.month = :month) "
            + "and (:status is null or s.status = :status) order by s.year desc, s.month desc")
    List<Schedule> search(@Param("year") Integer year, @Param("month") Integer month,
                          @Param("status") ScheduleStatus status);
}
