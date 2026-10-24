package org.example.onlinepossystem.driver;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Map;
import java.util.NoSuchElementException;

@Controller
public class DriverDispatchController {
    private final DriverDispatchService service;

    public DriverDispatchController(DriverDispatchService service) {
        this.service = service;
    }

    @GetMapping("/driver")
    public String driverPage() { return "driver"; }

    @GetMapping("/api/driver")
    @ResponseBody
    public DriverDispatchService.DriverDashboard driverDashboard(Authentication authentication) {
        return service.driverDashboard(authentication);
    }

    @PostMapping("/api/driver/clock-in")
    @ResponseBody
    public DriverDispatchService.Shift clockIn(Authentication authentication) { return service.clockIn(authentication); }

    @PostMapping("/api/driver/clock-out")
    @ResponseBody
    public DriverDispatchService.Shift clockOut(Authentication authentication) { return service.clockOut(authentication); }

    @PostMapping("/api/driver/claim")
    @ResponseBody
    public DriverDispatchService.DriverDashboard claim(Authentication authentication) { return service.claimNext(authentication); }

    @PostMapping("/api/driver/orders/{orderId}/pickup")
    @ResponseBody
    public DriverDispatchService.DriverDashboard pickup(@PathVariable long orderId, Authentication authentication) {
        return service.pickup(authentication, orderId);
    }

    @PostMapping("/api/driver/orders/{orderId}/delivered")
    @ResponseBody
    public DriverDispatchService.DriverDashboard delivered(@PathVariable long orderId, Authentication authentication) {
        return service.delivered(authentication, orderId);
    }

    @PostMapping("/api/driver/returning")
    @ResponseBody
    public DriverDispatchService.Shift returning(Authentication authentication) { return service.returning(authentication); }

    @GetMapping("/api/admin/dispatch")
    @ResponseBody
    public DriverDispatchService.ManagerDashboard managerDashboard(@RequestParam(required = false) Integer branchId,
                                                                    Authentication authentication) {
        return service.managerDashboard(authentication, branchId);
    }

    @GetMapping("/api/admin/dispatch/settlements")
    @ResponseBody
    public java.util.List<DriverDispatchService.Settlement> settlements(
            @RequestParam(required = false) Integer branchId, Authentication authentication) {
        return service.settlements(authentication, branchId);
    }

    @PostMapping("/api/admin/dispatch/drivers")
    @ResponseBody
    public DriverDispatchService.Profile promote(@RequestBody PromoteRequest request, Authentication authentication) {
        return service.promote(authentication, request.email(), request.branchId());
    }

    @PutMapping("/api/admin/dispatch/drivers/{driverId}/rates")
    @ResponseBody
    public DriverDispatchService.Profile rates(@PathVariable long driverId, @RequestBody RatesRequest request,
                                               Authentication authentication) {
        return service.updateRates(authentication, driverId, request.branchId(),
                request.hourlyRate(), request.perDeliveryRate());
    }

    @PostMapping("/api/admin/dispatch/assignments")
    @ResponseBody
    public DriverDispatchService.ManagerDashboard assign(@RequestBody AssignmentRequest request,
                                                           Authentication authentication) {
        return service.assign(authentication, request.orderId(), request.driverId(), request.branchId());
    }

    @PostMapping("/api/admin/dispatch/assignments/batch")
    @ResponseBody
    public DriverDispatchService.ManagerDashboard assignBatch(@RequestBody BatchAssignmentRequest request,
                                                                Authentication authentication) {
        return service.assignBatch(authentication, request.orderIds(), request.driverId(), request.branchId());
    }

    @PostMapping("/api/admin/dispatch/assignments/{orderId}/cancel-rejected")
    @ResponseBody
    public DriverDispatchService.ManagerDashboard cancelRejected(@PathVariable long orderId,
            @RequestParam(required = false) Integer branchId, Authentication authentication) {
        return service.cancelRejected(authentication, orderId, branchId);
    }

    @GetMapping("/api/admin/dispatch/shifts/{shiftId}/cash-up")
    @ResponseBody
    public DriverDispatchService.CashUpPreview preview(@PathVariable long shiftId,
            @RequestParam(required = false) Integer branchId,
            @RequestParam(required = false) LocalTime clockInTime,
            @RequestParam(required = false) LocalTime clockOutTime, Authentication authentication) {
        return service.preview(authentication, shiftId, branchId, clockInTime, clockOutTime);
    }

    @PostMapping("/api/admin/dispatch/shifts/{shiftId}/cash-up")
    @ResponseBody
    public DriverDispatchService.CashUpResult cashUp(@PathVariable long shiftId, @RequestBody CashUpRequest request,
                                                      Authentication authentication) {
        return service.cashUp(authentication, shiftId, request.branchId(), request.clockInTime(),
                request.clockOutTime(), request.cashHandedIn(), request.cashPaidOut(),
                request.varianceReason(), request.timeAdjustmentReason());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class,
            NoSuchElementException.class, EmptyResultDataAccessException.class})
    @ResponseBody
    public ResponseEntity<Map<String, String>> validation(Exception exception) {
        HttpStatus status = exception instanceof NoSuchElementException
                || exception instanceof EmptyResultDataAccessException ? HttpStatus.NOT_FOUND
                : exception instanceof IllegalStateException ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("error", exception.getMessage()));
    }

    public record PromoteRequest(String email, Integer branchId) {}
    public record RatesRequest(Integer branchId, BigDecimal hourlyRate, BigDecimal perDeliveryRate) {}
    public record AssignmentRequest(Integer branchId, long orderId, long driverId) {}
    public record BatchAssignmentRequest(Integer branchId, java.util.List<Long> orderIds, long driverId) {}
    public record CashUpRequest(Integer branchId, LocalTime clockInTime, LocalTime clockOutTime,
                                BigDecimal cashHandedIn, BigDecimal cashPaidOut, String varianceReason,
                                String timeAdjustmentReason) {}
}
