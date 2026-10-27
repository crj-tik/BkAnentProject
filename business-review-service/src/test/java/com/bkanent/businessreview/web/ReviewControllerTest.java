package com.bkanent.businessreview.web;

import com.bkanent.businessreview.service.ReviewApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReviewControllerTest {
    @Test
    void employeeIdInRequestBodyIsIgnored() throws Exception {
        ReviewApplicationService service = mock(ReviewApplicationService.class);
        ReviewController controller = new ReviewController(service);
        ReviewController.CreateReviewRequest request = new ObjectMapper().readValue("""
                {"storeId":23,"datasetId":"store-review-demo-v1","employeeId":"spoofed-employee"}
                """, ReviewController.CreateReviewRequest.class);

        controller.create(request);

        verify(service).create(23L, "store-review-demo-v1");
    }
}
