package org.example.onlinepossystem.staff.api;

public interface StaffOperations {
    StaffAccount createStaff(String name, String branch, String plainPin, String branchCode);
}
