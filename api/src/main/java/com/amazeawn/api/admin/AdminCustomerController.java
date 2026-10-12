package com.amazeawn.api.admin;

import com.amazeawn.api.auth.Role;
import com.amazeawn.api.auth.User;
import com.amazeawn.api.auth.UserRepository;
import com.amazeawn.api.config.ApiException;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/customers")
@Tag(name = "Admin: customers")
public class AdminCustomerController {

    private final UserRepository users;

    public AdminCustomerController(UserRepository users) {
        this.users = users;
    }

    public record CustomerView(UUID id, String email, String fullName, boolean enabled, Instant createdAt) {
        static CustomerView of(User u) {
            return new CustomerView(u.getId(), u.getEmail(), u.getFullName(), u.isEnabled(), u.getCreatedAt());
        }
    }

    @GetMapping
    public Page<CustomerView> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return users.findByRole(Role.CUSTOMER, PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(CustomerView::of);
    }

    @PutMapping("/{id}/disable")
    @Transactional
    public CustomerView disable(@PathVariable UUID id) {
        return setEnabled(id, false);
    }

    @PutMapping("/{id}/enable")
    @Transactional
    public CustomerView enable(@PathVariable UUID id) {
        return setEnabled(id, true);
    }

    private CustomerView setEnabled(UUID id, boolean enabled) {
        User user = users.findById(id).filter(u -> u.getRole() == Role.CUSTOMER)
                .orElseThrow(() -> ApiException.notFound("Customer"));
        user.setEnabled(enabled);
        return CustomerView.of(user);
    }
}
