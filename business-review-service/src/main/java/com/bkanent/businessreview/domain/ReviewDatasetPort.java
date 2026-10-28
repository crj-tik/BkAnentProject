package com.bkanent.businessreview.domain;
import com.bkanent.businessreview.domain.ReviewDomain.ReviewDataset;
public interface ReviewDatasetPort {
    ReviewDataset read(String datasetId, long storeId, String authorizationOperation);
    boolean sourcesCurrent(ReviewDataset dataset, String authorizationOperation);
}
