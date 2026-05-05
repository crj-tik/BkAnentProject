package com.bkanent.customer.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.customer.entity.CustomerEntity;
import com.bkanent.customer.mapper.CustomerMapper;
import com.bkanent.customer.service.CustomerDomainService;
import org.springframework.stereotype.Service;

@Service
public class CustomerDomainServiceImpl extends ServiceImpl<CustomerMapper, CustomerEntity> implements CustomerDomainService {
}
