package com.applyflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "companies")
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private UserAccount owner;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 500)
    private String website;

    @Enumerated(EnumType.STRING)
    @Column(name = "company_type", nullable = false, length = 20)
    private CompanyType companyType;

    @Column(length = 120)
    private String industry;

    protected Company() {
    }

    public Company(UserAccount owner, String name, String website, CompanyType companyType, String industry) {
        this.owner = owner;
        this.name = name;
        this.website = website;
        this.companyType = companyType;
        this.industry = industry;
    }

    public Long getId() {
        return id;
    }

    public UserAccount getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public String getWebsite() {
        return website;
    }

    public CompanyType getCompanyType() {
        return companyType;
    }

    public String getIndustry() {
        return industry;
    }

    public void updateDetails(String website, CompanyType companyType, String industry) {
        this.website = website;
        this.companyType = companyType;
        this.industry = industry;
    }
}
