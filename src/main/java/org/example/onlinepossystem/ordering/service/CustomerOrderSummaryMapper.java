package org.example.onlinepossystem.ordering.service;

import org.example.onlinepossystem.ordering.api.CustomerOrderSummary;
import org.example.onlinepossystem.ordering.dto.OrderResponseDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class CustomerOrderSummaryMapper {
    private final OrderTotalCalculator orderTotalCalculator;

    public CustomerOrderSummaryMapper(OrderTotalCalculator orderTotalCalculator) {
        this.orderTotalCalculator = orderTotalCalculator;
    }

    public CustomerOrderSummary toSummary(OrderResponseDTO order) {
        return new CustomerOrderSummary(
                order.getId(),
                order.getCreatedAt(),
                order.getBranchName(),
                order.getStatus(),
                order.getOrderType(),
                itemLines(order),
                orderTotalCalculator.total(order)
        );
    }

    private List<String> itemLines(OrderResponseDTO order) {
        List<String> lines = new ArrayList<>();
        for (OrderResponseDTO.SpecialItemDTO item : safeList(order.getSpecialItems())) {
            String selections = safeList(item.getSelections()).stream()
                    .map(value -> display(value.getLabel(), "Selection") + ": " + display(value.getProductName(), "Item")
                            + (value.getPizzaSizeCm() == null ? "" : " (" + value.getPizzaSizeCm() + "cm)"))
                    .collect(Collectors.joining(", "));
            lines.add(withExtras(quantity(item.getQuantity()) + " x " + display(item.getName(), "Special"), selections));
        }
        for (OrderResponseDTO.MenuItemDTO item : safeList(order.getMenuItems())) {
            String line = quantity(item.getQty()) + " x " + display(item.getMenuItemName(), "Menu item");
            lines.add(withExtras(line, menuExtras(item)));
        }
        for (OrderResponseDTO.PizzaItemDTO item : safeList(order.getPizzaItems())) {
            String name = display(item.getPizzaName(), "Pizza");
            if (item.getPizzaSizeCm() != null) {
                name += " (" + item.getPizzaSizeCm() + "cm)";
            }
            lines.add(withExtras(quantity(item.getQty()) + " x " + name, pizzaExtras(item)));
        }
        return lines;
    }

    private String menuExtras(OrderResponseDTO.MenuItemDTO item) {
        return safeList(item.getExtras()).stream()
                .map(extra -> quantity(extra.getQty()) + " x " + display(extra.getName(), "Extra"))
                .collect(Collectors.joining(", "));
    }

    private String pizzaExtras(OrderResponseDTO.PizzaItemDTO item) {
        List<String> extras = new ArrayList<>();
        if (item.getPizzaBaseOptionName() != null && !item.getPizzaBaseOptionName().isBlank()) {
            extras.add(item.getPizzaBaseOptionName());
        }
        extras.addAll(safeList(item.getExtras()).stream()
                .map(extra -> quantity(extra.getQty()) + " x " + display(extra.getIngredientName(), "Extra"))
                .toList());
        return String.join(", ", extras);
    }

    private String withExtras(String line, String extras) {
        return extras == null || extras.isBlank() ? line : line + " - Extras: " + extras;
    }

    private int quantity(Integer value) {
        return value == null || value < 1 ? 1 : value;
    }

    private String display(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
