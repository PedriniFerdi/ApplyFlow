package com.applyflow.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.applyflow.entity.Technology;

public interface TechnologyRepository extends JpaRepository<Technology, Long> {

    @Query("""
            select technology from Technology technology
            where lower(technology.name) = lower(:name)
              and (technology.owner is null or technology.owner.id = :ownerId)
            """)
    Optional<Technology> findVisibleByNameIgnoreCase(
            @Param("ownerId") Long ownerId,
            @Param("name") String name
    );

    @Query("""
            select technology from Technology technology
            where technology.owner is null or technology.owner.id = :ownerId
            order by lower(technology.name), technology.id
            """)
    List<Technology> findAllVisibleByOrderByNameAsc(@Param("ownerId") Long ownerId);

    @Query("""
            select technology from Technology technology
            where technology.id in :ids
              and (technology.owner is null or technology.owner.id = :ownerId)
            """)
    List<Technology> findAllVisibleByIdIn(
            @Param("ownerId") Long ownerId,
            @Param("ids") java.util.Collection<Long> ids
    );
}
