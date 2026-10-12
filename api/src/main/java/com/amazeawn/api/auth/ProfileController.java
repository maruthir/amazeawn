package com.amazeawn.api.auth;

import com.amazeawn.api.config.ApiException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Profile")
public class ProfileController {

    private final UserRepository users;
    private final AddressRepository addresses;

    public ProfileController(UserRepository users, AddressRepository addresses) {
        this.users = users;
        this.addresses = addresses;
    }

    public record ProfileView(UUID id, String email, String fullName, Role role, Instant createdAt) {
        static ProfileView of(User u) {
            return new ProfileView(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.getCreatedAt());
        }
    }

    public record ProfileUpdate(@NotBlank String fullName) {}

    public record AddressRequest(@NotBlank String label, @NotBlank String line1, String line2, @NotBlank String city,
                                 String region, @NotBlank String postalCode, @NotBlank String country, boolean isDefault) {}

    public record AddressView(UUID id, String label, String line1, String line2, String city, String region,
                              String postalCode, String country, boolean isDefault) {
        static AddressView of(Address a) {
            return new AddressView(a.getId(), a.getLabel(), a.getLine1(), a.getLine2(), a.getCity(), a.getRegion(),
                    a.getPostalCode(), a.getCountry(), a.isDefault());
        }
    }

    @GetMapping
    public ProfileView me() {
        return ProfileView.of(users.findById(CurrentUser.id()).orElseThrow(() -> ApiException.notFound("User")));
    }

    @PutMapping
    @Transactional
    public ProfileView update(@Valid @RequestBody ProfileUpdate req) {
        User user = users.findById(CurrentUser.id()).orElseThrow(() -> ApiException.notFound("User"));
        user.setFullName(req.fullName());
        return ProfileView.of(user);
    }

    @GetMapping("/addresses")
    public List<AddressView> addresses() {
        return addresses.findByUserIdOrderByIsDefaultDescLabelAsc(CurrentUser.id()).stream().map(AddressView::of).toList();
    }

    @PostMapping("/addresses")
    @Transactional
    public ResponseEntity<AddressView> addAddress(@Valid @RequestBody AddressRequest req) {
        Address address = new Address(CurrentUser.id());
        apply(address, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(AddressView.of(addresses.save(address)));
    }

    @PutMapping("/addresses/{id}")
    @Transactional
    public AddressView updateAddress(@PathVariable UUID id, @Valid @RequestBody AddressRequest req) {
        Address address = addresses.findByIdAndUserId(id, CurrentUser.id()).orElseThrow(() -> ApiException.notFound("Address"));
        apply(address, req);
        return AddressView.of(address);
    }

    @DeleteMapping("/addresses/{id}")
    @Transactional
    public ResponseEntity<Void> deleteAddress(@PathVariable UUID id) {
        Address address = addresses.findByIdAndUserId(id, CurrentUser.id()).orElseThrow(() -> ApiException.notFound("Address"));
        addresses.delete(address);
        return ResponseEntity.noContent().build();
    }

    private void apply(Address address, AddressRequest req) {
        if (req.isDefault()) {
            addresses.findFirstByUserIdAndIsDefaultTrue(address.getUserId())
                    .filter(existing -> !existing.getId().equals(address.getId()))
                    .ifPresent(existing -> existing.setDefault(false));
        }
        address.setLabel(req.label());
        address.setLine1(req.line1());
        address.setLine2(req.line2());
        address.setCity(req.city());
        address.setRegion(req.region());
        address.setPostalCode(req.postalCode());
        address.setCountry(req.country());
        address.setDefault(req.isDefault());
    }
}
