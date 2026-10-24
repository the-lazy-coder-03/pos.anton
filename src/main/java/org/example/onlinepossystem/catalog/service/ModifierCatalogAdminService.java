package org.example.onlinepossystem.catalog.service;

import org.example.onlinepossystem.catalog.entity.ModifierGroup;
import org.example.onlinepossystem.catalog.entity.ModifierOption;
import org.example.onlinepossystem.catalog.repository.ModifierGroupRepository;
import org.example.onlinepossystem.catalog.repository.ModifierOptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

@Service
public class ModifierCatalogAdminService {
    private static final Logger logger = LoggerFactory.getLogger(ModifierCatalogAdminService.class);

    private final ModifierGroupRepository modifierGroupRepository;
    private final ModifierOptionRepository modifierOptionRepository;

    public ModifierCatalogAdminService(
            ModifierGroupRepository modifierGroupRepository,
            ModifierOptionRepository modifierOptionRepository
    ) {
        this.modifierGroupRepository = modifierGroupRepository;
        this.modifierOptionRepository = modifierOptionRepository;
    }

    @Transactional
    public void saveModifierGroup(Integer id, String name, Integer minSelect, Integer maxSelect,
                                  Map<String, String> parameters, String actor) {
        ModifierGroup group = id == null
                ? new ModifierGroup()
                : modifierGroupRepository.findById(id).orElseGet(ModifierGroup::new);
        if (group.getId() == null) {
            group.setId(CatalogAdminSupport.nextId(modifierGroupRepository.findAll(), ModifierGroup::getId));
        }
        boolean required = parameters.containsKey("required");
        int min = minSelect == null ? (required ? 1 : 0) : Math.max(0, minSelect);
        int max = maxSelect == null ? Math.max(1, min) : Math.max(min, maxSelect);
        group.setName(CatalogAdminSupport.cleanText(name));
        group.setRequired(required);
        group.setMinSelect(min);
        group.setMaxSelect(max);
        modifierGroupRepository.save(group);
        logger.info("Admin action=saveModifierGroup modifierGroupId={} admin={}", group.getId(), CatalogAdminSupport.actorName(actor));
    }

    @Transactional
    public void saveModifierOption(Integer id, Integer groupId, String name, String menuItemId,
                                   BigDecimal additionalPrice, String actor) {
        ModifierOption option = id == null
                ? new ModifierOption()
                : modifierOptionRepository.findById(id).orElseGet(ModifierOption::new);
        if (option.getId() == null) {
            option.setId(CatalogAdminSupport.nextId(modifierOptionRepository.findAll(), ModifierOption::getId));
        }
        ModifierGroup group = modifierGroupRepository.findById(groupId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Modifier group not found with ID: " + groupId));
        option.setGroupId(group.getId());
        option.setName(CatalogAdminSupport.cleanText(name));
        option.setMenuItemId(parseInteger(menuItemId).orElse(null));
        option.setAdditionalPrice(additionalPrice);
        modifierOptionRepository.save(option);
        logger.info("Admin action=saveModifierOption modifierOptionId={} admin={}", option.getId(), CatalogAdminSupport.actorName(actor));
    }

    private Optional<Integer> parseInteger(String raw) {
        return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(Integer.valueOf(raw));
    }

}
