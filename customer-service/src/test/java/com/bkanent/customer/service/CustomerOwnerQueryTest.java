package com.bkanent.customer.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bkanent.customer.entity.CustomerEntity;
import com.bkanent.customer.mapper.CustomerMapper;
import com.bkanent.customer.model.CustomerQueryRequest;
import com.bkanent.customer.service.impl.CustomerDomainServiceImpl;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerOwnerQueryTest {
    @Test
    void ownListUsesUserOwnerAndOnlyNullOwnerLegacyBrokerFallback() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),
                "customer-owner-query-test"), CustomerEntity.class);
        CustomerMapper mapper = mock(CustomerMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        CustomerDomainServiceImpl service = new CustomerDomainServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        service.searchProfiles(new CustomerQueryRequest(null, null, 456L, null, null, null, 42L));

        var captor = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        String segment = captor.getValue().getCustomSqlSegment().toLowerCase();
        assertThat(segment).contains("owner_user_id", "broker_id", " or ");
    }
}
