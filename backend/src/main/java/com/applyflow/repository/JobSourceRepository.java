package com.applyflow.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.applyflow.entity.JobSource;

public interface JobSourceRepository extends JpaRepository<JobSource, Long> {

    Optional<JobSource> findByNameIgnoreCase(String name);

    @Query("select source from JobSource source order by lower(source.name), source.id")
    List<JobSource> findAllByOrderByNameAsc();
}
