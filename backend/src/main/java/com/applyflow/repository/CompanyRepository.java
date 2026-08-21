package com.applyflow.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.applyflow.entity.Company;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    java.util.Optional<Company> findByIdAndOwnerId(Long id, Long ownerId);

    List<Company> findAllByOwnerIdAndNameIgnoreCaseOrderByIdAsc(Long ownerId, String name);

    @Query("""
            select company
            from Company company
            where company.owner.id = :ownerId
              and lower(company.name) like lower(concat(concat('%', :query), '%'))
            order by lower(company.name), company.id
            """)
    List<Company> findByNameContainingIgnoreCaseOrderByName(
            @Param("query") String query,
            @Param("ownerId") Long ownerId,
            Pageable pageable
    );

    @Query("""
            select company
            from Company company
            where company.owner.id = :ownerId
            order by lower(company.name), company.id
            """)
    List<Company> findAllOrderByNameIgnoreCase(@Param("ownerId") Long ownerId, Pageable pageable);
}
