package com.bkanent.media.service;

import com.bkanent.common.rpc.MediaWorkerRpcService;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
public class MediaWorkerRpcServiceImpl implements MediaWorkerRpcService {

    @Override
    public List<String> generateAssets(Long listingId, String prompt) {
        return List.of(
                "https://minio.local/listings/" + listingId + "/poster-1.png",
                "https://minio.local/listings/" + listingId + "/poster-2.png"
        );
    }
}
