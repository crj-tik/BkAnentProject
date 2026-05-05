package com.bkanent.listing.service;

import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.rpc.ListingMasterRpcService;
import com.bkanent.listing.entity.ListingEntity;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
public class ListingMasterRpcServiceImpl implements ListingMasterRpcService {

    private final ListingDomainService listingDomainService;

    public ListingMasterRpcServiceImpl(ListingDomainService listingDomainService) {
        this.listingDomainService = listingDomainService;
    }

    @Override
    public ListingDTO getListingById(Long listingId) {
        ListingEntity entity = listingDomainService.getById(listingId);
        return entity == null ? null : toDto(entity);
    }

    @Override
    public List<ListingDTO> searchListings(String keyword) {
        return listingDomainService.searchByKeyword(keyword).stream().map(this::toDto).toList();
    }

    private ListingDTO toDto(ListingEntity entity) {
        return new ListingDTO(
                entity.getId(),
                entity.getTitle(),
                entity.getAddress(),
                entity.getLayout(),
                entity.getArea(),
                entity.getTotalPrice(),
                entity.getStatus()
        );
    }
}
