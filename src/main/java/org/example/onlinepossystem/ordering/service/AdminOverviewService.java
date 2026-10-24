package org.example.onlinepossystem.ordering.service;

import org.example.onlinepossystem.branch.api.BranchLookup;
import org.example.onlinepossystem.branch.api.BranchView;
import org.example.onlinepossystem.customer.api.AccountAccess;
import org.example.onlinepossystem.ordering.api.AdminOverview;
import org.example.onlinepossystem.ordering.entity.Order;
import org.example.onlinepossystem.ordering.repository.OrderRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class AdminOverviewService {
    private static final List<String> OPEN_STATUSES = List.of("Pending", "Preparing", "Created");

    private final OrderRepository orderRepository;
    private final OrderResponseMapper orderResponseMapper;
    private final OrderTotalCalculator orderTotalCalculator;
    private final BranchLookup branchLookup;

    public AdminOverviewService(OrderRepository orderRepository,
                                OrderResponseMapper orderResponseMapper,
                                OrderTotalCalculator orderTotalCalculator,
                                BranchLookup branchLookup) {
        this.orderRepository = orderRepository;
        this.orderResponseMapper = orderResponseMapper;
        this.orderTotalCalculator = orderTotalCalculator;
        this.branchLookup = branchLookup;
    }

    @Transactional(readOnly = true)
    public AdminOverview getOverview(AccountAccess access,
                                     String requestedRange,
                                     LocalDate requestedStart,
                                     LocalDate requestedEnd,
                                     Integer requestedBranchId) {
        Integer branchId = resolveBranch(access, requestedBranchId);
        DateWindow window = dateWindow(requestedRange, requestedStart, requestedEnd);
        LocalDateTime start = window.start().atStartOfDay();
        LocalDateTime endExclusive = window.end().plusDays(1).atStartOfDay();

        List<Order> periodOrders = branchId == null
                ? orderRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(start, endExclusive)
                : orderRepository.findByBranchIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                        branchId, start, endExclusive);
        List<Order> openOrders = branchId == null
                ? orderRepository.findByStatusInOrderByCreatedAtDesc(OPEN_STATUSES)
                : orderRepository.findByBranchIdAndStatusInOrderByCreatedAtDesc(branchId, OPEN_STATUSES);

        Metrics totals = metrics(periodOrders, openOrders);
        List<AdminOverview.BranchSummary> branchSummaries = availableBranches(access, branchId).stream()
                .map(branch -> {
                    List<Order> branchPeriod = periodOrders.stream()
                            .filter(order -> branch.id().equals(order.getBranchId()))
                            .toList();
                    List<Order> branchOpen = openOrders.stream()
                            .filter(order -> branch.id().equals(order.getBranchId()))
                            .toList();
                    Metrics metrics = metrics(branchPeriod, branchOpen);
                    return new AdminOverview.BranchSummary(
                            branch.id(), branch.name(), metrics.pending(), metrics.preparing(),
                            metrics.completed(), metrics.rejected(), metrics.revenue()
                    );
                })
                .toList();

        String branchName = branchId == null ? "All Branches" : branchLookup.requireById(branchId).name();
        return new AdminOverview(
                window.range(), window.start(), window.end(), branchId, branchName,
                totals.pending(), totals.preparing(), totals.completed(), totals.rejected(),
                totals.revenue(), totals.average(), branchSummaries
        );
    }

    private Metrics metrics(List<Order> periodOrders, List<Order> openOrders) {
        long pending = countStatus(openOrders, "Pending");
        long preparing = countStatus(openOrders, "Preparing");
        List<Order> completedOrders = periodOrders.stream()
                .filter(order -> status(order).equals("completed"))
                .toList();
        long rejected = countStatus(periodOrders, "Rejected");
        BigDecimal revenue = completedOrders.stream()
                .map(orderResponseMapper::toDto)
                .map(orderTotalCalculator::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal average = completedOrders.isEmpty()
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : revenue.divide(BigDecimal.valueOf(completedOrders.size()), 2, RoundingMode.HALF_UP);
        return new Metrics(pending, preparing, completedOrders.size(), rejected, revenue, average);
    }

    private long countStatus(List<Order> orders, String status) {
        String normalized = status.toLowerCase(Locale.ROOT);
        return orders.stream().filter(order -> status(order).equals(normalized)).count();
    }

    private String status(Order order) {
        String value = order.getStatus();
        if (value == null || value.isBlank() || value.equalsIgnoreCase("created")) {
            return "pending";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private Integer resolveBranch(AccountAccess access, Integer requestedBranchId) {
        if (!access.isAdmin()) {
            throw new AccessDeniedException("Admin access is required.");
        }
        if (access.branchId() != null) {
            if (requestedBranchId != null && !access.branchId().equals(requestedBranchId)) {
                throw new AccessDeniedException("This admin account cannot view the requested branch.");
            }
            return access.branchId();
        }
        if (requestedBranchId != null) {
            branchLookup.requireById(requestedBranchId);
        }
        return requestedBranchId;
    }

    private List<BranchView> availableBranches(AccountAccess access, Integer branchId) {
        if (branchId != null) {
            return List.of(branchLookup.requireById(branchId));
        }
        return access.isSuperAdmin() ? branchLookup.findAll() : List.of();
    }

    private DateWindow dateWindow(String requestedRange, LocalDate requestedStart, LocalDate requestedEnd) {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Africa/Johannesburg"));
        String range = requestedRange == null || requestedRange.isBlank()
                ? "today"
                : requestedRange.trim().toLowerCase(Locale.ROOT);
        return switch (range) {
            case "7d" -> new DateWindow("7d", today.minusDays(6), today);
            case "30d" -> new DateWindow("30d", today.minusDays(29), today);
            case "custom" -> customWindow(requestedStart, requestedEnd);
            default -> new DateWindow("today", today, today);
        };
    }

    private DateWindow customWindow(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("Custom reporting requires both start and end dates.");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("Reporting start date must not be after the end date.");
        }
        return new DateWindow("custom", start, end);
    }

    private record DateWindow(String range, LocalDate start, LocalDate end) {
    }

    private record Metrics(long pending, long preparing, long completed, long rejected,
                           BigDecimal revenue, BigDecimal average) {
    }
}
