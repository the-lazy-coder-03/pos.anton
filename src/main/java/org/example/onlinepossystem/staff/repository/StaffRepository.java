package org.example.onlinepossystem.staff.repository;

import org.example.onlinepossystem.staff.entity.Staff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@org.springframework.transaction.annotation.Transactional(readOnly = true)
public interface StaffRepository extends JpaRepository<Staff, Long> {

    Optional<Staff> findByBranch(String branch);
}
