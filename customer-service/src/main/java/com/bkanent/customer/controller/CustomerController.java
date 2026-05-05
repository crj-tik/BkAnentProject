package com.bkanent.customer.controller;

import com.bkanent.customer.entity.CustomerEntity;
import com.bkanent.customer.service.CustomerDomainService;
import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.CustomerProfileDTO;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.common.rpc.CustomerRpcService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerRpcService customerRpcService;
    private final CustomerDomainService customerDomainService;

    public CustomerController(CustomerRpcService customerRpcService, CustomerDomainService customerDomainService) {
        this.customerRpcService = customerRpcService;
        this.customerDomainService = customerDomainService;
    }

    @GetMapping("/{id}")
    public ApiResponse<CustomerProfileDTO> detail(@PathVariable Long id) {
        CustomerEntity entity = customerDomainService.getById(id);
        if (entity == null) {
            return ApiResponse.fail("CUSTOMER_404", "customer not found");
        }
        return ApiResponse.ok(new CustomerProfileDTO(
                entity.getId(),
                entity.getName(),
                entity.getMobile(),
                entity.getIntention(),
                entity.getBudgetMin(),
                entity.getBudgetMax()
        ));
    }

    @GetMapping("/{id}/matched-listings")
    public ApiResponse<List<Long>> matchedListings(@PathVariable Long id) {
        return ApiResponse.ok(customerRpcService.matchListingsForCustomer(id));
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("customer-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
