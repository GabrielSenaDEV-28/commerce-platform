package com.gabrielsena.commerce.merchant.api;

import com.gabrielsena.commerce.merchant.application.OnboardMerchantResult;
import com.gabrielsena.commerce.auth.application.RegisterMerchant;
import com.gabrielsena.commerce.auth.application.RegisterMerchantCommand;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/merchants")
public class MerchantController {

    private final RegisterMerchant registerMerchant;

    public MerchantController(RegisterMerchant registerMerchant) {
        this.registerMerchant = registerMerchant;
    }

    @PostMapping
    public ResponseEntity<OnboardMerchantResponse> onboard(@Valid @RequestBody OnboardMerchantRequest request) {
        OnboardMerchantResult result = registerMerchant.execute(
                new RegisterMerchantCommand(
                        request.merchantName(),
                        request.storeName(),
                        request.subdomain(),
                        request.email(),
                        request.password()
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(OnboardMerchantResponse.from(result));
    }
}
