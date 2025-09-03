package com.surf.nursepro.nurse_pro_api.repository;

import com.surf.nursepro.nurse_pro_api.entity.WorkloadData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkloadDataRepository extends JpaRepository<WorkloadData, String> {
    List<WorkloadData> findByNurseIdAndMonthAndYear(String nurseId, int month, int year);
}