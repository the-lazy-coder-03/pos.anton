package org.example.onlinepossystem.special.entity;

import jakarta.persistence.*;
import org.example.onlinepossystem.branch.entity.Branch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "special")
public class Special {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "special_id")
    private Long id;
    @Column(nullable = false, unique = true) private String code;
    @ManyToOne @JoinColumn(name = "branch_id", nullable = false) private Branch branch;
    @Column(nullable = false) private String name;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(name = "bundle_price", nullable = false, precision = 10, scale = 2) private BigDecimal bundlePrice;
    @Column(nullable = false) private boolean active = true;
    @Column(nullable = false) private boolean archived;
    @Column(name = "starts_on") private LocalDate startsOn;
    @Column(name = "ends_on") private LocalDate endsOn;
    @Column(name = "sort_order", nullable = false) private int sortOrder;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "special_day", joinColumns = @JoinColumn(name = "special_id"))
    @Column(name = "day_of_week")
    private Set<Integer> days = new LinkedHashSet<>();

    @OneToMany(mappedBy = "special", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder, id")
    private Set<SpecialComponent> components = new LinkedHashSet<>();

    @OneToMany(mappedBy = "special", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder, id")
    private Set<SpecialAddon> addons = new LinkedHashSet<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Branch getBranch() { return branch; }
    public void setBranch(Branch branch) { this.branch = branch; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getBundlePrice() { return bundlePrice; }
    public void setBundlePrice(BigDecimal bundlePrice) { this.bundlePrice = bundlePrice; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
    public LocalDate getStartsOn() { return startsOn; }
    public void setStartsOn(LocalDate startsOn) { this.startsOn = startsOn; }
    public LocalDate getEndsOn() { return endsOn; }
    public void setEndsOn(LocalDate endsOn) { this.endsOn = endsOn; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public Set<Integer> getDays() { return days; }
    public void setDays(Set<Integer> days) { this.days = days; }
    public Set<SpecialComponent> getComponents() { return components; }
    public void setComponents(Set<SpecialComponent> components) { this.components = components; }
    public Set<SpecialAddon> getAddons() { return addons; }
    public void setAddons(Set<SpecialAddon> addons) { this.addons = addons; }
    public void addComponent(SpecialComponent component) { components.add(component); component.setSpecial(this); }
    public void addAddon(SpecialAddon addon) { addons.add(addon); addon.setSpecial(this); }
}
