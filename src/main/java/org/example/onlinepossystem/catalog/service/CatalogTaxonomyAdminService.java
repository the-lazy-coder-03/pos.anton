package org.example.onlinepossystem.catalog.service;

import org.example.onlinepossystem.catalog.entity.Ingredient;
import org.example.onlinepossystem.catalog.entity.MenuCategory;
import org.example.onlinepossystem.catalog.entity.PizzaCategory;
import org.example.onlinepossystem.catalog.entity.PizzaSize;
import org.example.onlinepossystem.catalog.entity.PriceCategory;
import org.example.onlinepossystem.catalog.repository.IngredientRepository;
import org.example.onlinepossystem.catalog.repository.MenuCategoryRepository;
import org.example.onlinepossystem.catalog.repository.PizzaCategoryRepository;
import org.example.onlinepossystem.catalog.repository.PizzaSizeRepository;
import org.example.onlinepossystem.catalog.repository.PriceCategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class CatalogTaxonomyAdminService {
    private static final Logger logger = LoggerFactory.getLogger(CatalogTaxonomyAdminService.class);

    private final PizzaCategoryRepository pizzaCategoryRepository;
    private final MenuCategoryRepository menuCategoryRepository;
    private final PizzaSizeRepository pizzaSizeRepository;
    private final PriceCategoryRepository priceCategoryRepository;
    private final IngredientRepository ingredientRepository;

    public CatalogTaxonomyAdminService(
            PizzaCategoryRepository pizzaCategoryRepository,
            MenuCategoryRepository menuCategoryRepository,
            PizzaSizeRepository pizzaSizeRepository,
            PriceCategoryRepository priceCategoryRepository,
            IngredientRepository ingredientRepository
    ) {
        this.pizzaCategoryRepository = pizzaCategoryRepository;
        this.menuCategoryRepository = menuCategoryRepository;
        this.pizzaSizeRepository = pizzaSizeRepository;
        this.priceCategoryRepository = priceCategoryRepository;
        this.ingredientRepository = ingredientRepository;
    }

    @Transactional
    public void savePizzaCategory(Integer id, String name, Map<String, String> parameters, String actor) {
        PizzaCategory category = id == null
                ? new PizzaCategory()
                : pizzaCategoryRepository.findById(id).orElseGet(PizzaCategory::new);
        if (category.getId() == null) {
            category.setId(CatalogAdminSupport.nextId(pizzaCategoryRepository.findAll(), PizzaCategory::getId));
            category.setSortOrder(pizzaCategoryRepository.nextSortOrder());
        }
        category.setName(CatalogAdminSupport.cleanText(name));
        category.setActive(parameters.containsKey("active"));
        pizzaCategoryRepository.save(category);
        logger.info("Admin action=savePizzaCategory pizzaCategoryId={} admin={}", category.getId(), CatalogAdminSupport.actorName(actor));
    }

    @Transactional
    public void saveMenuCategory(Integer id, String name, Map<String, String> parameters, String actor) {
        MenuCategory category = id == null
                ? new MenuCategory()
                : menuCategoryRepository.findById(id).orElseGet(MenuCategory::new);
        if (category.getId() == null) {
            category.setId(CatalogAdminSupport.nextId(menuCategoryRepository.findAll(), MenuCategory::getId));
            category.setSortOrder(menuCategoryRepository.nextSortOrder());
        }
        category.setName(CatalogAdminSupport.cleanText(name));
        category.setActive(parameters.containsKey("active"));
        menuCategoryRepository.save(category);
        logger.info("Admin action=saveMenuCategory menuCategoryId={} admin={}", category.getId(), CatalogAdminSupport.actorName(actor));
    }

    @Transactional
    public void savePizzaSize(Integer id, Integer cm, Map<String, String> parameters, String actor) {
        PizzaSize size = id == null ? new PizzaSize() : pizzaSizeRepository.findById(id).orElseGet(PizzaSize::new);
        if (size.getId() == null) {
            size.setId(CatalogAdminSupport.nextId(pizzaSizeRepository.findAll(), PizzaSize::getId));
            size.setSortOrder(pizzaSizeRepository.nextSortOrder());
        }
        size.setCm(cm);
        size.setActive(parameters.containsKey("active"));
        pizzaSizeRepository.save(size);
        logger.info("Admin action=savePizzaSize pizzaSizeId={} admin={}", size.getId(), CatalogAdminSupport.actorName(actor));
    }

    @Transactional
    public void savePriceCategory(Integer id, String name, Map<String, String> parameters, String actor) {
        PriceCategory category = id == null
                ? new PriceCategory()
                : priceCategoryRepository.findById(id).orElseGet(PriceCategory::new);
        if (category.getId() == null) {
            category.setId(CatalogAdminSupport.nextId(priceCategoryRepository.findAll(), PriceCategory::getId));
            category.setSortOrder(priceCategoryRepository.nextSortOrder());
        }
        category.setName(CatalogAdminSupport.cleanText(name));
        category.setActive(parameters.containsKey("active"));
        priceCategoryRepository.save(category);
        logger.info("Admin action=savePriceCategory priceCategoryId={} admin={}", category.getId(), CatalogAdminSupport.actorName(actor));
    }

    @Transactional
    public void saveIngredient(Integer id, String name, Integer priceCategoryId,
                               Map<String, String> parameters, String actor) {
        Ingredient ingredient = id == null
                ? new Ingredient()
                : ingredientRepository.findById(id).orElseGet(Ingredient::new);
        if (ingredient.getId() == null) {
            ingredient.setId(CatalogAdminSupport.nextId(ingredientRepository.findAll(), Ingredient::getId));
        }
        PriceCategory priceCategory = priceCategoryRepository.findById(priceCategoryId)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "Price category not found with ID: " + priceCategoryId));
        ingredient.setName(CatalogAdminSupport.cleanText(name));
        ingredient.setPriceCategory(priceCategory);
        ingredient.setActive(parameters.containsKey("active"));
        ingredient.setSeasonal(parameters.containsKey("seasonal"));
        ingredientRepository.save(ingredient);
        logger.info("Admin action=saveIngredient ingredientId={} admin={}", ingredient.getId(), CatalogAdminSupport.actorName(actor));
    }
}
