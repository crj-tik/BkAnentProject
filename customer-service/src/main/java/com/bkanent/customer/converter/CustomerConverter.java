package com.bkanent.customer.converter;

import com.bkanent.common.model.CustomerProfileDTO;
import com.bkanent.customer.entity.CustomerEntity;
import com.bkanent.customer.entity.CustomerFavoriteListingEntity;
import com.bkanent.customer.entity.CustomerFollowRecordEntity;
import com.bkanent.customer.entity.OwnerEntrustRecordEntity;
import com.bkanent.customer.model.CustomerFavoriteResponse;
import com.bkanent.customer.model.CustomerFollowRecordResponse;
import com.bkanent.customer.model.OwnerEntrustResponse;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 客户模块对象转换器。
 */
@Component
public class CustomerConverter {

    public CustomerProfileDTO toProfile(CustomerEntity entity) {
        if (entity == null) {
            return null;
        }
        return new CustomerProfileDTO(
                entity.getId(),
                entity.getProfileType(),
                entity.getName(),
                entity.getMobile(),
                entity.getWechatNo(),
                entity.getGender(),
                entity.getIntention(),
                entity.getPreferredArea(),
                entity.getPreferredLayout(),
                entity.getBudgetMin(),
                entity.getBudgetMax(),
                entity.getPreferredAreaMin(),
                entity.getPreferredAreaMax(),
                entity.getBrokerId(),
                entity.getSourceChannel(),
                entity.getRemark()
        );
    }

    public CustomerFollowRecordResponse toFollowResponse(CustomerFollowRecordEntity entity) {
        return new CustomerFollowRecordResponse(
                entity.getId(),
                entity.getCustomerId(),
                entity.getBrokerId(),
                entity.getFollowType(),
                entity.getContent(),
                entity.getResultTag(),
                entity.getNextFollowTime(),
                entity.getCreatedAt()
        );
    }

    public OwnerEntrustResponse toEntrustResponse(OwnerEntrustRecordEntity entity) {
        Long daysToExpire = entity.getEntrustEndDate() == null
                ? null
                : ChronoUnit.DAYS.between(LocalDate.now(), entity.getEntrustEndDate());
        return new OwnerEntrustResponse(
                entity.getId(),
                entity.getCustomerId(),
                entity.getListingId(),
                entity.getContractNo(),
                entity.getEntrustStartDate(),
                entity.getEntrustEndDate(),
                entity.getReminderDays(),
                entity.getStatus(),
                entity.getRemark(),
                daysToExpire
        );
    }

    public CustomerFavoriteResponse toFavoriteResponse(CustomerFavoriteListingEntity entity) {
        return new CustomerFavoriteResponse(
                entity.getId(),
                entity.getCustomerId(),
                entity.getListingId(),
                entity.getFavoriteSource(),
                entity.getRemark()
        );
    }
}
