package com.gabrielsena.commerce.merchant.api;

import com.gabrielsena.commerce.merchant.application.OnboardMerchant;
import com.gabrielsena.commerce.merchant.application.OnboardMerchantCommand;
import com.gabrielsena.commerce.merchant.application.OnboardMerchantResult;
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

    private final OnboardMerchant onboardMerchant;

    public MerchantController(OnboardMerchant onboardMerchant) {
        this.onboardMerchant = onboardMerchant;
    }

    @PostMapping
    public ResponseEntity<OnboardMerchantResponse> onboard(@Valid @RequestBody OnboardMerchantRequest request) {
        OnboardMerchantResult result = onboardMerchant.execute(
                new OnboardMerchantCommand(
                        request.merchantName(),
                        request.storeName(),
                        request.subdomain()
                )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(OnboardMerchantResponse.from(result));
    }
}
