package org.example.onlinepossystem.catalog.pizza.web;

import org.example.onlinepossystem.catalog.pizza.dto.PizzaCategorySplit;
import org.example.onlinepossystem.catalog.pizza.dto.PizzaDetail;
import org.example.onlinepossystem.catalog.pizza.dto.PriceQuoteRequest;
import org.example.onlinepossystem.catalog.pizza.dto.PriceQuoteResponse;
import org.example.onlinepossystem.catalog.pizza.service.PizzaService;
import org.example.onlinepossystem.catalog.pizza.service.InvalidPizzaSelectionException;
import org.example.onlinepossystem.catalog.pizza.service.PizzaNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;

import java.util.function.Supplier;

@RestController
@RequestMapping("/api/branches/{branchId}/pizzas")
public class PizzaController {
    private final PizzaService pizzaService;

    public PizzaController(PizzaService pizzaService) {
        this.pizzaService = pizzaService;
    }

    @GetMapping
    public PizzaCategorySplit listPizzas(@PathVariable Integer branchId) {
        return translateServiceErrors(() -> pizzaService.listPizzasByCategory(branchId));
    }

    @GetMapping("/{pizzaId}")
    public PizzaDetail getPizzaDetail(
            @PathVariable Integer branchId,
            @PathVariable Integer pizzaId,
            @RequestParam(value = "sizeCm", required = false) Integer sizeCm
    ) {
        return translateServiceErrors(() -> pizzaService.getPizzaDetail(branchId, pizzaId, sizeCm));
    }

    @PostMapping("/{pizzaId}/quote")
    public PriceQuoteResponse quotePrice(
            @PathVariable Integer branchId,
            @PathVariable Integer pizzaId,
            @Valid @RequestBody PriceQuoteRequest request
    ) {
        if (request.sizeCm() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sizeCm is required");
        }
        return translateServiceErrors(
                () -> pizzaService.quotePrice(
                        branchId,
                        pizzaId,
                        request.sizeCm(),
                        request.selectedToppingIds(),
                        request.pizzaBaseOptionId()
                )
        );
    }

    private <T> T translateServiceErrors(Supplier<T> action) {
        try {
            return action.get();
        } catch (PizzaNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (InvalidPizzaSelectionException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
