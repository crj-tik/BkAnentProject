package com.bkanent.contract.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.contract.entity.ContractEntity;
import com.bkanent.contract.mapper.ContractMapper;
import com.bkanent.contract.service.ContractDomainService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 合同领域服务实现。
 */
@Service
public class ContractDomainServiceImpl extends ServiceImpl<ContractMapper, ContractEntity> implements ContractDomainService {

    @Override
    public List<ContractEntity> listPendingSignContracts(int days) {
        LocalDate endDate = LocalDate.now().plusDays(days);
        return list(new LambdaQueryWrapper<ContractEntity>()
                .eq(ContractEntity::getStatus, "PENDING_SIGN")
                .orderByAsc(ContractEntity::getExpiryDate))
                .stream()
                .filter(contract -> isWithinDays(contract.getExpiryDate(), endDate))
                .toList();
    }

    @Override
    public List<ContractEntity> listContracts(String contractType, String status) {
        return list(new LambdaQueryWrapper<ContractEntity>()
                .eq(StringUtils.hasText(contractType), ContractEntity::getContractType, contractType)
                .eq(StringUtils.hasText(status), ContractEntity::getStatus, status)
                .orderByDesc(ContractEntity::getUpdatedAt)
                .orderByDesc(ContractEntity::getId));
    }

    private boolean isWithinDays(String expiryDate, LocalDate endDate) {
        if (!StringUtils.hasText(expiryDate)) {
            return false;
        }
        try {
            LocalDate targetDate = LocalDate.parse(expiryDate);
            return !targetDate.isBefore(LocalDate.now()) && !targetDate.isAfter(endDate);
        } catch (DateTimeParseException exception) {
            return false;
        }
    }
}
