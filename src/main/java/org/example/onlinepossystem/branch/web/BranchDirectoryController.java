package org.example.onlinepossystem.branch.web;
import java.util.List;
import org.example.onlinepossystem.branch.api.BranchLookup;
import org.example.onlinepossystem.branch.api.BranchView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
/** Public store names and IDs only; business administration remains separately Owner-authorized. */
@RestController
public class BranchDirectoryController {
 private final BranchLookup branches;
 public BranchDirectoryController(BranchLookup branches){this.branches=branches;}
 @GetMapping("/api/branches") public List<BranchView> stores(){return branches.findAll();}
}
