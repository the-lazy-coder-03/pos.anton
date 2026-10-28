package org.example.onlinepossystem.branch.service;

import org.example.onlinepossystem.branch.api.BranchLookup;
import org.example.onlinepossystem.branch.api.BranchView;
import org.example.onlinepossystem.branch.entity.Branch;
import org.example.onlinepossystem.branch.repository.BranchRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class BranchService implements BranchLookup {
    private final BranchRepository branchRepository;

    public BranchService(BranchRepository branchRepository) {
        this.branchRepository = branchRepository;
    }

    @Override
    public Optional<BranchView> findByName(String name) {
        return branchRepository.findByName(name).map(this::toView);
    }

    @Override
    public BranchView requireByName(String name) {
        return findByName(name)
                .orElseThrow(() -> new java.util.NoSuchElementException("Branch not found: " + name));
    }

    @Override
    public BranchView requireById(Integer id) {
        return branchRepository.findById(id)
                .map(this::toView)
                .orElseThrow(() -> new java.util.NoSuchElementException("Branch not found with ID: " + id));
    }

    @Override
    public List<BranchView> findAll() {
        return branchRepository.findAll().stream().filter(Branch::isActive)
                .sorted(java.util.Comparator.comparing(Branch::getId)).map(this::toView).toList();
    }

    @Override
    public boolean hasAnyBranches() {
        return branchRepository.count() > 0;
    }

    @Override
    public BranchView ensureBranch(Integer id, String name) {
        return branchRepository.findByName(name)
                .map(this::toView)
                .orElseGet(() -> toView(branchRepository.save(new Branch(id, name))));
    }

    private BranchView toView(Branch branch) {
        return new BranchView(branch.getId(), branch.getName());
    }
}
