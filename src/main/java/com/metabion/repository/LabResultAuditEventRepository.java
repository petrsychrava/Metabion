package com.metabion.repository;

import com.metabion.domain.LabResultAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LabResultAuditEventRepository extends JpaRepository<LabResultAuditEvent, Long> {
}
