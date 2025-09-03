package com.surf.nursepro.nurse_pro_api.repository;

import com.surf.nursepro.nurse_pro_api.entity.Nurse;
import com.surf.nursepro.nurse_pro_api.enums.ExperienceLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NurseRepository extends JpaRepository<Nurse, String>, JpaSpecificationExecutor<Nurse> {
    List<Nurse> findByDepartment(String department);
    List<Nurse> findByExperienceLevel(ExperienceLevel experienceLevel);
    List<Nurse> findBySpecializationsContaining(String specialization);
}